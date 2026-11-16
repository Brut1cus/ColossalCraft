package colossus.physics.xpbd;

import colossus.physics.api.*;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java fallback backend: Extended Position-Based Dynamics for articulated rigid bodies
 * (Müller et al., "Detailed Rigid Body Simulation with Extended Position Based Dynamics", 2020).
 * <p>
 * One {@link #step(double)} = {@code substeps} small substeps with one constraint iteration each, which keeps long
 * stiff chains (legs, tails) stable without a global solver. Joint motors are PD torque servos applied as external
 * torques, matching the blueprint's τ = Kp·(θt − θ) − Kd·ω clamped by the joint's rated torque.
 * <p>
 * Implemented: dynamic and static bodies, gravity, external forces, hinge/swivel/ball/universal/slider/spring/fixed/dock
 * joints with limits, motors (scalar for hinge/swivel/slider, 3-axis orientation for ball/universal), breaking, voxel terrain contacts with static and dynamic Coulomb friction, restitution,
 * sleeping, contact reporting.
 * Not yet: body-vs-body contacts, CCD, islands on worker threads (see docs/ROADMAP.md, Phase 1 → Phase 2).
 */
public final class XpbdBackend implements PhysicsBackend {
    private final Map<Integer, XpbdBody> bodies = new LinkedHashMap<>();
    private final Map<Integer, XpbdJoint> joints = new LinkedHashMap<>();
    private int nextBody = 1, nextJoint = 1;
    private TerrainQuery terrain = TerrainQuery.NONE;
    private Vec3 gravity = new Vec3(0, -9.81, 0);
    private ContactListener contactListener = ContactListener.NONE;
    private final int substeps;

    /** Linear and angular speed below which a body counts as still; 1 s of stillness puts it to sleep. */
    private static final double SLEEP_SPEED = 0.03;
    private static final int SLEEP_STEPS = 60;

    public XpbdBackend() { this(12); }

    public XpbdBackend(int substeps) { this.substeps = Math.max(1, substeps); }

    @Override public String id() { return "xpbd"; }

    @Override public BodyHandle createBody(BodyDesc desc) {
        int id = nextBody++;
        bodies.put(id, new XpbdBody(id, desc));
        return new BodyHandle(id);
    }

    @Override public void removeBody(BodyHandle body) {
        bodies.remove(body.id());
        joints.values().removeIf(j -> j.a.id == body.id() || j.b.id == body.id());
    }

    @Override public ConstraintHandle addConstraint(ConstraintDesc desc) {
        XpbdBody a = bodies.get(desc.a().id()), b = bodies.get(desc.b().id());
        if (a == null || b == null) throw new IllegalArgumentException("unknown body in constraint");
        int id = nextJoint++;
        joints.put(id, new XpbdJoint(id, desc, a, b));
        a.wake(); b.wake();
        return new ConstraintHandle(id);
    }

    @Override public void removeConstraint(ConstraintHandle c) {
        XpbdJoint j = joints.remove(c.id());
        if (j != null) { j.a.wake(); j.b.wake(); }
    }

    @Override public void setTerrain(TerrainQuery t) { terrain = t == null ? TerrainQuery.NONE : t; }

    @Override public void setGravity(Vec3 g) { gravity = g; }

    @Override public void setContactListener(ContactListener l) { contactListener = l == null ? ContactListener.NONE : l; }

    @Override public void applyForce(BodyHandle h, Vec3 f, Vec3 worldPoint) {
        XpbdBody b = bodies.get(h.id());
        if (b == null || b.isStatic) return;
        b.force = b.force.add(f);
        b.torque = b.torque.add(worldPoint.sub(b.x).cross(f));
        if (f.lengthSq() > 1e-6) b.wake();
    }

    @Override public void applyTorque(BodyHandle h, Vec3 t) {
        XpbdBody b = bodies.get(h.id());
        if (b == null || b.isStatic) return;
        b.torque = b.torque.add(t);
        if (t.lengthSq() > 1e-6) b.wake();
    }

    @Override public void setMotorOrientation(ConstraintHandle c, Quat target, double max) {
        XpbdJoint j = joints.get(c.id());
        if (j == null) return;
        j.motorOn = max > 0;
        j.motorRot = target.normalize();
        j.motorMax = max;
        if (j.motorOn) { j.a.wake(); j.b.wake(); }
    }

    @Override public void setMotorTarget(ConstraintHandle c, double target, double max) {
        XpbdJoint j = joints.get(c.id());
        if (j == null) return;
        j.motorOn = max > 0;
        j.motorTarget = target;
        j.motorMax = max;
        j.a.wake(); j.b.wake();
    }

    @Override public void setPose(BodyHandle h, Pose pose) {
        XpbdBody b = bodies.get(h.id());
        if (b == null) return;
        b.x = b.prevX = pose.position();
        b.q = b.prevQ = pose.rotation().normalize();
        b.v = Vec3.ZERO; b.w = Vec3.ZERO;
        b.updateInertia();
        b.wake();
    }

    @Override public void setMassProperties(BodyHandle h, MassProperties m) {
        XpbdBody b = bodies.get(h.id());
        if (b == null) return;
        b.setMass(m);
        b.updateInertia();
        b.wake();
    }

    @Override public BodyState bodyState(BodyHandle h) {
        XpbdBody b = bodies.get(h.id());
        return b == null ? BodyState.MISSING : new BodyState(new Pose(b.x, b.q), b.v, b.w, b.sleeping);
    }

    @Override public ConstraintState constraintState(ConstraintHandle c) {
        XpbdJoint j = joints.get(c.id());
        return j == null ? ConstraintState.MISSING : new ConstraintState(j.position, j.velocity, j.lastForce, j.lastTorque, j.broken);
    }

    @Override public void close() { bodies.clear(); joints.clear(); }

    // =====================================================================================================
    // Simulation
    // =====================================================================================================

    private record Contact(XpbdBody body, Vec3 local, Vec3 normal, double lambdaN, double vnBefore, double mu, Vec3 point) {}

    @Override public void step(double dt) {
        double h = dt / substeps;
        // wake bodies attached to awake bodies through joints
        for (XpbdJoint j : joints.values()) {
            if (j.broken) continue;
            if (!j.a.sleeping && !j.a.isStatic) j.b.wake();
            if (!j.b.sleeping && !j.b.isStatic) j.a.wake();
            j.impulseSum = Vec3.ZERO; j.angularSum = Vec3.ZERO; j.substeps = 0;
        }
        List<Contact> reported = new ArrayList<>();
        for (int s = 0; s < substeps; s++) {
            applyJointForces();
            integrate(h);
            for (XpbdJoint j : joints.values()) if (!j.broken) solveJoint(j, h);
            List<Contact> contacts = solveTerrainContacts(h);
            updateVelocities(h);
            solveContactVelocities(contacts, h);
            if (s == substeps - 1) reported = contacts;
        }
        finishStep(dt, reported, h);
    }

    private void integrate(double h) {
        for (XpbdBody b : bodies.values()) {
            if (b.isStatic || b.sleeping) { b.jointForce = Vec3.ZERO; b.jointTorque = Vec3.ZERO; continue; }
            b.prevX = b.x;
            b.prevQ = b.q;
            Vec3 f = b.force.add(b.jointForce), tq = b.torque.add(b.jointTorque);
            b.jointForce = Vec3.ZERO;
            b.jointTorque = Vec3.ZERO;
            b.v = b.v.add(gravity.mul(h)).addScaled(f, b.invMass * h);
            b.x = b.x.addScaled(b.v, h);
            // ω += h·I⁻¹(τ − ω × Iω)   (world frame)
            var iWorld = b.inertiaLocal.rotated(b.q);
            Vec3 gyro = b.w.cross(iWorld.transform(b.w));
            b.w = b.w.add(b.invInertiaWorld.transform(tq.sub(gyro)).mul(h));
            b.q = b.q.integrate(b.w, h);
            b.updateInertia();
        }
    }

    private void updateVelocities(double h) {
        for (XpbdBody b : bodies.values()) {
            if (b.isStatic || b.sleeping) continue;
            b.v = b.x.sub(b.prevX).mul(1 / h);
            Quat dq = b.q.mul(b.prevQ.conjugate());
            Vec3 w = new Vec3(dq.x(), dq.y(), dq.z()).mul(2 / h);
            b.w = dq.w() >= 0 ? w : w.neg();
        }
    }

    private void finishStep(double dt, List<Contact> contacts, double h) {
        for (XpbdBody b : bodies.values()) {
            b.force = Vec3.ZERO;
            b.torque = Vec3.ZERO;
            if (b.isStatic || b.sleeping) continue;
            if (b.v.length() < SLEEP_SPEED && b.w.length() < SLEEP_SPEED) {
                if (++b.stillSteps > SLEEP_STEPS) { b.sleeping = true; b.v = Vec3.ZERO; b.w = Vec3.ZERO; }
            } else b.stillSteps = 0;
        }
        for (XpbdJoint j : joints.values()) {
            if (j.broken) continue;
            // net impulse over the step / step time = average constraint force (oscillating corrections cancel)
            double stepTime = Math.max(1, j.substeps) * j.substepH * j.substepH;
            j.lastForce = j.impulseSum.length() / stepTime;
            j.lastTorque = j.angularSum.length() / stepTime;
            if (j.d.breakForce() > 0 && j.lastForce > j.d.breakForce()) { j.broken = true; j.a.wake(); j.b.wake(); }
            measure(j);
        }
        for (Contact c : contacts) {
            contactListener.onContact(new BodyHandle(c.body.id), null, c.point, c.normal, c.lambdaN / (h * h));
        }
    }

    // ---------------------------------------------------------------- generic XPBD corrections

    /**
     * Moves the anchor points so that p1 travels along +corr and p2 along −corr until they meet.
     * Returns the positional impulse applied to b1 (Δλ·n); its length divided by h² is the constraint force.
     */
    private static Vec3 positional(XpbdBody b1, XpbdBody b2, Vec3 corr, Vec3 r1, Vec3 r2, double compliance, double h) {
        double c = corr.length();
        if (c < 1e-9) return Vec3.ZERO;
        Vec3 n = corr.mul(1 / c);
        double w = b1.invMassAt(r1, n) + (b2 == null ? 0 : b2.invMassAt(r2, n));
        double alpha = compliance / (h * h);
        if (w + alpha <= 0) return Vec3.ZERO;
        Vec3 p = n.mul(c / (w + alpha));
        b1.applyPositional(p, r1, 1);
        if (b2 != null) b2.applyPositional(p, r2, -1);
        return p;
    }

    /** Rotates b1 by +dphi and b2 by −dphi, weighted by their inverse inertia. Returns the angular impulse on b1. */
    private static Vec3 angular(XpbdBody b1, XpbdBody b2, Vec3 dphi, double compliance, double h) {
        double th = dphi.length();
        if (th < 1e-9) return Vec3.ZERO;
        Vec3 n = dphi.mul(1 / th);
        double w = b1.invMassRot(n) + b2.invMassRot(n);
        double alpha = compliance / (h * h);
        if (w + alpha <= 0) return Vec3.ZERO;
        Vec3 p = n.mul(th / (w + alpha));
        b1.applyRotational(p, 1);
        b2.applyRotational(p, -1);
        return p;
    }

    /** Signed angle from u to v around unit axis n (u, v roughly perpendicular to n). */
    static double signedAngle(Vec3 u, Vec3 v, Vec3 n) {
        Vec3 uu = u.rejectFrom(n).normalize(), vv = v.rejectFrom(n).normalize();
        return Math.atan2(uu.cross(vv).dot(n), uu.dot(vv));
    }

    static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ---------------------------------------------------------------- joints

    private void solveJoint(XpbdJoint j, double h) {
        XpbdBody a = j.a, b = j.b;
        if (a.sleeping && b.sleeping) return;
        ConstraintDesc d = j.d;
        Vec3 lin = Vec3.ZERO, rot = Vec3.ZERO;

        switch (d.type()) {
            case FIXED, DOCK -> {
                rot = rot.add(alignOrientation(j, h));
                lin = lin.add(attach(j, h));
            }
            case HINGE, SWIVEL -> {
                Vec3 a1 = a.q.rotate(d.axisA()), a2 = b.q.rotate(d.axisB());
                rot = rot.add(angular(a, b, a1.cross(a2), 0, h));
                if (d.type() == colossus.physics.api.JointType.HINGE && d.hasLimits()) {
                    a1 = a.q.rotate(d.axisA());
                    double phi = signedAngle(a.q.rotate(j.refA), b.q.rotate(j.refB), a1);
                    double e = phi - Math.max(d.lower(), Math.min(d.upper(), phi));
                    if (e != 0) rot = rot.add(angular(a, b, a1.mul(e), 0, h));
                }
                lin = lin.add(attach(j, h));
            }
            case BALL -> {
                Vec3 a1 = a.q.rotate(d.axisA()), a2 = b.q.rotate(d.axisB());
                double ang = Math.acos(Math.max(-1, Math.min(1, a1.dot(a2))));
                if (ang > d.coneAngle()) {
                    Vec3 n = a1.cross(a2).normalize();
                    if (n.lengthSq() > 0) rot = rot.add(angular(a, b, n.mul(ang - d.coneAngle()), 0, h));
                }
                if (d.twistAngle() < Math.PI) {
                    a1 = a.q.rotate(d.axisA()); a2 = b.q.rotate(d.axisB());
                    Vec3 b1s = Quat.fromTo(a1, a2).rotate(a.q.rotate(j.refA));
                    double phi = signedAngle(b1s, b.q.rotate(j.refB), a2);
                    double e = phi - Math.max(-d.twistAngle(), Math.min(d.twistAngle(), phi));
                    if (e != 0) rot = rot.add(angular(a, b, a2.mul(e), 0, h));
                }
                lin = lin.add(attach(j, h));
            }
            case UNIVERSAL -> {
                Vec3 a1 = a.q.rotate(d.axisA()), a2 = b.q.rotate(d.axisB());
                double dot = a1.dot(a2);
                Vec3 n = a1.cross(a2).normalize();
                if (n.lengthSq() > 0 && Math.abs(dot) > 1e-9) rot = rot.add(angular(a, b, n.mul(-Math.asin(Math.max(-1, Math.min(1, dot)))), 0, h));
                lin = lin.add(attach(j, h));
            }
            case SLIDER, SPRING_DAMPER -> {
                rot = rot.add(alignOrientation(j, h));
                Vec3 r1 = a.q.rotate(d.anchorA()), r2 = b.q.rotate(d.anchorB());
                Vec3 dd = b.x.add(r2).sub(a.x.add(r1));
                Vec3 ax = a.q.rotate(d.axisA());
                double s = dd.dot(ax);
                lin = lin.add(positional(a, b, dd.sub(ax.mul(s)), r1, r2, 0, h));
                if (d.hasLimits()) {
                    r1 = a.q.rotate(d.anchorA()); r2 = b.q.rotate(d.anchorB());
                    dd = b.x.add(r2).sub(a.x.add(r1));
                    ax = a.q.rotate(d.axisA());
                    s = dd.dot(ax);
                    double e = s - Math.max(d.lower(), Math.min(d.upper(), s));
                    if (e != 0) lin = lin.add(positional(a, b, ax.mul(e), r1, r2, 0, h));
                }
            }
        }
        j.impulseSum = j.impulseSum.add(lin);
        j.angularSum = j.angularSum.add(rot);
        j.substeps++;
        j.substepH = h;
    }

    private static Vec3 attach(XpbdJoint j, double h) {
        Vec3 r1 = j.a.q.rotate(j.d.anchorA()), r2 = j.b.q.rotate(j.d.anchorB());
        Vec3 corr = j.b.x.add(r2).sub(j.a.x.add(r1));
        return positional(j.a, j.b, corr, r1, r2, 0, h);
    }

    private static Vec3 alignOrientation(XpbdJoint j, double h) {
        Quat target = j.a.q.mul(j.restRel);
        Quat err = j.b.q.mul(target.conjugate());
        return angular(j.a, j.b, err.toRotationVector(), 0, h);
    }

    /** Joint coordinate and rate for state reads and motors. */
    private static void measure(XpbdJoint j) {
        ConstraintDesc d = j.d;
        XpbdBody a = j.a, b = j.b;
        switch (d.type()) {
            case HINGE, SWIVEL -> {
                Vec3 a1 = a.q.rotate(d.axisA());
                j.position = signedAngle(a.q.rotate(j.refA), b.q.rotate(j.refB), a1);
                j.velocity = b.w.sub(a.w).dot(a1);
            }
            case SLIDER, SPRING_DAMPER -> {
                Vec3 r1 = a.q.rotate(d.anchorA()), r2 = b.q.rotate(d.anchorB());
                Vec3 ax = a.q.rotate(d.axisA());
                j.position = b.x.add(r2).sub(a.x.add(r1)).dot(ax);
                j.velocity = b.pointVelocity(r2).sub(a.pointVelocity(r1)).dot(ax);
            }
            default -> { j.position = 0; j.velocity = 0; }
        }
    }

    /** Motors (PD servos) and springs, applied as external force/torque pairs before integration. */
    private void applyJointForces() {
        for (XpbdJoint j : joints.values()) {
            if (j.broken) continue;
            ConstraintDesc d = j.d;
            XpbdBody a = j.a, b = j.b;
            boolean spring = d.type() == colossus.physics.api.JointType.SPRING_DAMPER;
            if (!j.motorOn && !spring) continue;
            if (a.sleeping && b.sleeping && !j.motorOn) continue;
            measure(j);
            switch (d.type()) {
                case HINGE, SWIVEL -> {
                    Vec3 ax = a.q.rotate(d.axisA());
                    double ieff = 1.0 / Math.max(1e-12, a.invMassRot(ax) + b.invMassRot(ax));
                    double kp = d.stiffness() > 0 ? d.stiffness() : j.motorMax / 0.25;
                    double kd = d.damping() > 0 ? d.damping() : 2 * Math.sqrt(kp * ieff);
                    double err = d.type() == colossus.physics.api.JointType.SWIVEL ? wrap(j.motorTarget - j.position) : j.motorTarget - j.position;
                    double tau = clamp(kp * err - kd * j.velocity, j.motorMax);
                    a.jointTorque = a.jointTorque.sub(ax.mul(tau));
                    b.jointTorque = b.jointTorque.add(ax.mul(tau));
                    if (Math.abs(tau) > 1e-3) { a.wake(); b.wake(); }
                }
                case SLIDER, SPRING_DAMPER -> {
                    Vec3 r1 = a.q.rotate(d.anchorA()), r2 = b.q.rotate(d.anchorB());
                    Vec3 ax = a.q.rotate(d.axisA());
                    double f;
                    if (spring) {
                        f = -d.stiffness() * (j.position - d.restLength()) - d.damping() * j.velocity;
                    } else {
                        double meff = 1.0 / Math.max(1e-12, a.invMass + b.invMass);
                        double kp = d.stiffness() > 0 ? d.stiffness() : j.motorMax / 0.1;
                        double kd = d.damping() > 0 ? d.damping() : 2 * Math.sqrt(kp * meff);
                        f = clamp(kp * (j.motorTarget - j.position) - kd * j.velocity, j.motorMax);
                    }
                    Vec3 fv = ax.mul(f);
                    b.jointForce = b.jointForce.add(fv);
                    b.jointTorque = b.jointTorque.add(r2.cross(fv));
                    a.jointForce = a.jointForce.sub(fv);
                    a.jointTorque = a.jointTorque.sub(r1.cross(fv));
                }
                case BALL, UNIVERSAL -> {
                    // 3-axis PD toward qB* = qA·T·rest; error as a world rotation vector taking qB to qB*
                    Quat target = a.q.mul(j.motorRot).mul(j.restRel);
                    Vec3 err = target.mul(b.q.conjugate()).toRotationVector();
                    Vec3 relW = b.w.sub(a.w);
                    Vec3 u = err.lengthSq() > 1e-12 ? err.normalize() : relW.lengthSq() > 1e-12 ? relW.normalize() : Vec3.Y;
                    double ieff = 1.0 / Math.max(1e-12, a.invMassRot(u) + b.invMassRot(u));
                    double kp = d.stiffness() > 0 ? d.stiffness() : j.motorMax / 0.25;
                    double kd = d.damping() > 0 ? d.damping() : 2 * Math.sqrt(kp * ieff);
                    Vec3 tau = err.mul(kp).sub(relW.mul(kd));
                    double tl = tau.length();
                    if (tl > j.motorMax) tau = tau.mul(j.motorMax / tl);
                    a.jointTorque = a.jointTorque.sub(tau);
                    b.jointTorque = b.jointTorque.add(tau);
                    if (tl > 1e-3) { a.wake(); b.wake(); }
                }
                default -> {}
            }
        }
    }

    private static double clamp(double v, double max) { return Math.max(-max, Math.min(max, v)); }

    // ---------------------------------------------------------------- terrain contacts

    private static final int[][] DIRS = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

    private List<Contact> solveTerrainContacts(double h) {
        List<Contact> out = new ArrayList<>();
        if (terrain == TerrainQuery.NONE) return out;
        for (XpbdBody b : bodies.values()) {
            if (b.isStatic || b.sleeping) continue;
            for (Vec3 local : b.samples) {
                Vec3 p = b.toWorld(local);
                int cx = (int) Math.floor(p.x()), cy = (int) Math.floor(p.y()), cz = (int) Math.floor(p.z());
                if (!terrain.isSolid(cx, cy, cz)) continue;
                // shallowest exit through a face whose neighbour is open
                double best = Double.MAX_VALUE;
                int[] bestDir = null;
                for (int[] dir : DIRS) {
                    if (terrain.isSolid(cx + dir[0], cy + dir[1], cz + dir[2])) continue;
                    double depth = dir[0] > 0 ? cx + 1 - p.x() : dir[0] < 0 ? p.x() - cx
                            : dir[1] > 0 ? cy + 1 - p.y() : dir[1] < 0 ? p.y() - cy
                            : dir[2] > 0 ? cz + 1 - p.z() : p.z() - cz;
                    if (depth < best) { best = depth; bestDir = dir; }
                }
                if (bestDir == null) { bestDir = DIRS[0]; best = cy + 1 - p.y(); }
                Vec3 n = new Vec3(bestDir[0], bestDir[1], bestDir[2]);
                Vec3 r = b.q.rotate(local);
                double vnBefore = b.pointVelocity(r).dot(n);
                double dlN = positional(b, null, n.mul(best), r, null, 0, h).length();
                double mu = Math.sqrt(Math.max(0, b.friction * terrain.friction(cx, cy, cz)));
                // static friction: undo tangential slip of the contact point if the friction cone allows it
                r = b.q.rotate(local);
                Vec3 pNow = b.x.add(r), pPrev = b.prevX.add(b.prevQ.rotate(local));
                Vec3 dp = pNow.sub(pPrev);
                Vec3 dpt = dp.rejectFrom(n);
                double slip = dpt.length();
                if (slip > 1e-9) {
                    Vec3 t = dpt.mul(1 / slip);
                    double wt = b.invMassAt(r, t);
                    if (wt > 0 && slip / wt < mu * dlN) positional(b, null, dpt.neg(), r, null, 0, h);
                }
                out.add(new Contact(b, local, n, dlN, vnBefore, mu, b.toWorld(local)));
            }
        }
        return out;
    }

    private void solveContactVelocities(List<Contact> contacts, double h) {
        for (Contact c : contacts) {
            XpbdBody b = c.body;
            Vec3 r = b.q.rotate(c.local);
            Vec3 v = b.pointVelocity(r);
            double vn = v.dot(c.normal);
            Vec3 vt = v.sub(c.normal.mul(vn));
            Vec3 dv = Vec3.ZERO;
            double vtl = vt.length();
            if (vtl > 1e-9) {
                // friction impulse h·μ·fn changes the contact point's velocity by impulse × generalised inverse mass
                double fn = c.lambdaN / (h * h);
                double wt = b.invMassAt(r, vt.mul(1 / vtl));
                dv = dv.add(vt.mul(-Math.min(h * c.mu * fn * wt, vtl) / vtl));
            }
            double e = Math.abs(c.vnBefore) > 2 * gravity.length() * h ? b.restitution : 0;
            double target = Math.max(-e * c.vnBefore, 0);
            if (vn < target) dv = dv.add(c.normal.mul(target - vn));
            double dvl = dv.length();
            if (dvl < 1e-9) continue;
            Vec3 dir = dv.mul(1 / dvl);
            double w = b.invMassAt(r, dir);
            if (w <= 0) continue;
            b.applyVelocityImpulse(dir.mul(dvl / w), r);
        }
    }

    /** Number of bodies (debug / tests). */
    public int bodyCount() { return bodies.size(); }
}
