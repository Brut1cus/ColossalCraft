package colossus.physics.xpbd;

import colossus.physics.api.BodyDesc;
import colossus.physics.api.Box;
import colossus.physics.api.MassProperties;
import colossus.physics.math.Mat3;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

import java.util.List;

/** One rigid body in the XPBD solver. Package-private state, mutated only by {@link XpbdBackend} on the physics thread. */
final class XpbdBody {
    final int id;
    Vec3 x, prevX, v = Vec3.ZERO;
    Quat q, prevQ;
    Vec3 w = Vec3.ZERO;
    double invMass;
    Mat3 inertiaLocal = Mat3.ZERO, invInertiaLocal = Mat3.ZERO;
    Mat3 invInertiaWorld = Mat3.ZERO;
    List<Box> shape;
    double friction, restitution;
    final boolean isStatic;
    final int group;
    Vec3 force = Vec3.ZERO, torque = Vec3.ZERO;
    /** Joint motor and spring loads, recomputed every substep. */
    Vec3 jointForce = Vec3.ZERO, jointTorque = Vec3.ZERO;
    boolean sleeping;
    int stillSteps;
    /** Surface sample points (local, relative to the centre of mass), regenerated when the shape changes. */
    Vec3[] samples = new Vec3[0];

    XpbdBody(int id, BodyDesc d) {
        this.id = id;
        this.x = d.pose().position();
        this.q = d.pose().rotation().normalize();
        this.prevX = x;
        this.prevQ = q;
        this.shape = List.copyOf(d.shape());
        this.friction = d.friction();
        this.restitution = d.restitution();
        this.isStatic = d.isStatic() || d.mass().mass() <= 0;
        this.group = d.group();
        setMass(d.mass());
        rebuildSamples();
        updateInertia();
    }

    void setMass(MassProperties m) {
        if (isStatic) {
            invMass = 0; inertiaLocal = Mat3.ZERO; invInertiaLocal = Mat3.ZERO;
        } else {
            invMass = 1.0 / m.mass();
            inertiaLocal = m.inertia();
            invInertiaLocal = m.inertia().inverse();
        }
    }

    void updateInertia() { invInertiaWorld = isStatic ? Mat3.ZERO : invInertiaLocal.rotated(q); }

    Vec3 toWorld(Vec3 local) { return x.add(q.rotate(local)); }

    /** Velocity of a point at world offset r from the centre of mass. */
    Vec3 pointVelocity(Vec3 r) { return v.add(w.cross(r)); }

    /** Generalised inverse mass for a positional correction along unit n at world offset r. */
    double invMassAt(Vec3 r, Vec3 n) {
        if (isStatic) return 0;
        Vec3 rn = r.cross(n);
        return invMass + rn.dot(invInertiaWorld.transform(rn));
    }

    /** Generalised inverse mass for a rotational correction about unit n. */
    double invMassRot(Vec3 n) {
        if (isStatic) return 0;
        return n.dot(invInertiaWorld.transform(n));
    }

    void applyPositional(Vec3 p, Vec3 r, double sign) {
        if (isStatic) return;
        x = x.addScaled(p, invMass * sign);
        rotateBy(invInertiaWorld.transform(r.cross(p)).mul(sign));
    }

    void applyRotational(Vec3 p, double sign) {
        if (isStatic) return;
        rotateBy(invInertiaWorld.transform(p).mul(sign));
    }

    private void rotateBy(Vec3 dphi) {
        Quat dq = new Quat(0, dphi.x(), dphi.y(), dphi.z()).mul(q);
        q = new Quat(q.w() + 0.5 * dq.w(), q.x() + 0.5 * dq.x(), q.y() + 0.5 * dq.y(), q.z() + 0.5 * dq.z()).normalize();
        updateInertia();
    }

    void applyVelocityImpulse(Vec3 p, Vec3 r) {
        if (isStatic) return;
        v = v.addScaled(p, invMass);
        w = w.add(invInertiaWorld.transform(r.cross(p)));
    }

    void wake() { sleeping = false; stillSteps = 0; }

    /** Points on each box surface, spaced at most one block apart. Used for terrain contacts. */
    void rebuildSamples() {
        java.util.ArrayList<Vec3> pts = new java.util.ArrayList<>();
        for (Box b : shape) {
            Vec3 s = b.size();
            int nx = Math.max(1, (int) Math.ceil(s.x())), ny = Math.max(1, (int) Math.ceil(s.y())), nz = Math.max(1, (int) Math.ceil(s.z()));
            for (int i = 0; i <= nx; i++) for (int j = 0; j <= ny; j++) for (int k = 0; k <= nz; k++) {
                boolean surface = i == 0 || i == nx || j == 0 || j == ny || k == 0 || k == nz;
                if (!surface) continue;
                pts.add(new Vec3(b.min().x() + s.x() * i / nx, b.min().y() + s.y() * j / ny, b.min().z() + s.z() * k / nz));
            }
        }
        samples = pts.toArray(new Vec3[0]);
    }
}
