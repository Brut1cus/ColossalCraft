package colossus.brain.walker;

import colossus.brain.balance.SupportPolygon;
import colossus.brain.foot.SwingTrajectory;
import colossus.brain.gait.GaitPattern;
import colossus.brain.gait.GaitPlanner;
import colossus.brain.ik.IkChain;
import colossus.brain.ik.IkSolver;
import colossus.physics.PhysicsWorld;
import colossus.physics.api.*;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walks a legged Colossus. Runs on the physics thread every step ({@link PhysicsWorld.StepController}). Pure Java.
 * <p>
 * Pipeline per step: commanded velocity → a <b>desired body pose</b> (upright, at stand height, moving at that velocity,
 * never more than {@code maxLead} ahead of the real body) → gait planner (phase clock, gait by speed, static stability
 * rule) → for each leg a world-space foot target (planted foothold in stance, a Bézier swing toward the next foothold in
 * swing) → that target expressed in the <i>desired</i> body frame → leg IK → joint servo targets.
 * <p>
 * Because stance feet stay planted in the world while their targets are computed against the desired pose, the stance
 * legs push the real body toward it: that is what moves the machine, and it also rights it when it tilts.
 * <p>
 * Static gaits (the quadruped crawl) sway the body toward the feet that will carry it before each lift, with a time
 * constant proportional to the time between lift-offs. Legs with an ankle keep their foot level; legs without one rock
 * on their foot edges as the knees bend, which is why walkers should be built with ankles (universal joints).
 * <p>
 * Frames: forward is the core frame's +Z (south at assembly), left is +X. The body is assumed upright at assembly.
 */
public final class WalkerBrain implements PhysicsWorld.StepController {

    /**
     * @param crouch       stand this fraction of the leg length lower than assembled, so legs have room to step
     * @param stepHeight   swing lift above the higher of lift-off and foothold, m
     * @param maxLead      the desired pose may run at most this far ahead of the real body, m
     * @param strideScale  stride length as a fraction of leg length
     * @param minMargin    static gaits: a leg lifts only if the CoM and its capture point stay this far inside the
     *                     remaining support, m
     * @param swayTime     static gaits: time constant of the body sway toward the next support triangle, as a fraction
     *                     of the time between two lift-offs (a fixed time is too slow for a faster gait, too jerky for
     *                     a slower one)
     * @param reachUse     footholds are kept within this fraction of the leg's reach from the hip
     * @param maxSpeed     commanded ground speed is capped here, m/s (faster dynamic gaits are not tuned yet)
     */
    public record Params(double crouch, double stepHeight, double maxLead, double strideScale, double minMargin, double swayTime,
                         double reachUse, double maxSpeed) {
        /** Tuned on a quadruped with ankles: about 0.5 m/s, under 5° of tilt. */
        public static final Params DEFAULT = new Params(0.1, 0.5, 0.6, 0.3, 0.15, 1.4, 0.9, 0.5);
    }

    /**
     * A driven joint of a leg.
     *
     * @param handle      the joint's physics handle
     * @param type        joint type (hinge, ball, ...)
     * @param maxEffort   servo torque (or force) limit: the joint's size-scaled rating
     * @param parent      parent body (toward the root)
     * @param child       child body (toward the foot)
     * @param anchorLocal joint centre in the parent body's frame
     * @param axis        joint axis in the parent body's frame
     */
    public record Drive(ConstraintHandle handle, JointType type, double maxEffort, BodyHandle parent, BodyHandle child,
                        Vec3 anchorLocal, Vec3 axis) {}

    /** Runtime state of one leg. */
    public static final class Leg {
        public final Limb limb;
        final Drive[] drives;
        /** The foot Segment's body and its centre of mass in the core frame, to read where the foot really is. */
        final BodyHandle footBody;
        final Vec3 footComRel;
        IkChain.Pose warm;
        Vec3 planted, foothold;
        SwingTrajectory swing;
        boolean stance = true;
        /** Heading (rad about +Y) the foot was planted with; a planted foot keeps it while the body turns. */
        double footHeading;
        volatile double lastError;

        /** The ankle's drive (keeps the foot level), or null for a leg without an ankle. */
        final Drive ankle;

        public Leg(Limb limb, Drive[] drives, BodyHandle footBody, Vec3 footComRel) { this(limb, drives, null, footBody, footComRel); }

        public Leg(Limb limb, Drive[] drives, Drive ankle, BodyHandle footBody, Vec3 footComRel) {
            if (drives.length != limb.chain().size()) throw new IllegalArgumentException("one drive per IK link");
            if (limb.hasAnkle() != (ankle != null)) throw new IllegalArgumentException("ankle drive must match the limb");
            this.limb = limb;
            this.drives = drives;
            this.ankle = ankle;
            this.footBody = footBody;
            this.footComRel = footComRel;
            this.warm = new IkChain.Pose(limb.chain().size());
        }

        public boolean inStance() { return stance; }

        public Vec3 planted() { return planted; }

        /** Current foot target in the world (planted foothold, or the point on the swing curve). */
        public volatile Vec3 target;

        /** The last IK solution (joint targets sent to the servos). */
        public IkChain.Pose pose() { return warm; }

        /** IK miss of the last step, m. */
        public double lastError() { return lastError; }
    }

    private final BodyHandle root;
    private final Vec3 rootComRel;
    private final List<Leg> legs;
    private final TerrainQuery terrain;
    private final Params params;
    private final GaitPlanner planner;
    private final double legLength;

    private volatile double cmdForward, cmdStrafe, cmdYawRate;
    private boolean initialised;
    private Vec3 desiredPos, basePos;
    private double desiredYaw, standDrop, footBaseY;

    /** Legs are re-ordered front to back, left before right (LF, RF, LH, RH, ...), the order gaits expect. */
    /**
     * @param machineMass the whole Colossus's mass, kg: sets the servo budget (see {@link #servoBudget})
     */
    public WalkerBrain(BodyHandle root, Vec3 rootComRel, double machineMass, List<Leg> legs, TerrainQuery terrain, Params params) {
        if (legs.size() < 2) throw new IllegalArgumentException("a walker needs at least two legs");
        this.root = root;
        this.rootComRel = rootComRel;
        List<Leg> ordered = new ArrayList<>(legs);
        // front (higher z) first; within a row, left (higher x) first
        ordered.sort(Comparator.comparingDouble((Leg l) -> -Math.round(l.limb.hip().z() * 2) / 2.0).thenComparingDouble(l -> -l.limb.hip().x()));
        this.legs = List.copyOf(ordered);
        this.terrain = terrain;
        this.params = params;
        double len = 0;
        for (Leg l : legs) len += l.limb.restLength();
        this.legLength = len / legs.size();
        double r = 0;
        for (Leg l : legs) { Vec3 h = l.limb.hip().sub(rootComRel); r += Math.hypot(h.x(), h.z()); }
        this.turnRadius = Math.max(0.5, r / legs.size());
        this.budget = servoBudget(machineMass, legLength, legs.size());
        this.planner = new GaitPlanner(GaitPattern.defaultsFor(legs.size()), Math.max(0.3, legLength * params.strideScale()));
        for (Leg l : this.legs) l.limb.chain().pole = new Vec3(0, 0, 1); // knees forward
    }

    public List<Leg> legs() { return legs; }

    public GaitPlanner planner() { return planner; }

    public Params params() { return params; }

    /**
     * Walking command in the body frame: forward (+Z) and left (+X) speed in m/s, yaw rate in rad/s (positive turns
     * left). Safe to call from any thread.
     */
    public void command(double forward, double strafe, double yawRate) {
        cmdForward = forward;
        cmdStrafe = strafe;
        cmdYawRate = yawRate;
    }

    public Vec3 desiredPosition() { return desiredPos; }

    /** Desired heading, rad about +Y (diagnostics and tests). */
    public double desiredHeading() { return desiredYaw; }

    @Override public void beforeStep(PhysicsBackend backend, double dt) {
        BodyState s = backend.bodyState(root);
        if (s == BodyState.MISSING) return;
        Pose body = s.pose();
        if (!initialised) initialise(body);

        // 1. desired body pose
        // foot speed: straight-line speed plus the turn (feet circle the body centre at about the hip spread). The total
        // is capped, the turn included: an over-fast turn would push the gait out of its stable crawl
        double fwd = cmdForward, side = cmdStrafe, yawRate = cmdYawRate;
        double footSpeed = Math.hypot(fwd, side) + Math.abs(yawRate) * turnRadius;
        if (footSpeed > params.maxSpeed()) { double k = params.maxSpeed() / footSpeed; fwd *= k; side *= k; yawRate *= k; }
        speedNow = Math.hypot(fwd, side) + Math.abs(yawRate) * turnRadius;
        // the heading target pauses like the position does: while a leg is held, or when the body lags the turn by more
        // than MAX_HEADING_LEAD (a target that keeps turning without the legs makes the body spin to catch up)
        boolean held = false;
        for (int i = 0; i < legs.size(); i++) held |= planner.isHeld(i);
        double headingLag = LegIk.wrap(desiredYaw + yawRate * dt - headingOf(body.rotation()));
        if (held || Math.abs(headingLag) > MAX_HEADING_LEAD && headingLag * yawRate > 0) yawRate = 0;
        desiredYaw += yawRate * dt;
        turnRate = yawRate;
        Quat yaw = Quat.axisAngle(Vec3.Y, desiredYaw);
        Vec3 vel = yaw.rotate(new Vec3(side, 0, fwd));
        // commanded progress pauses while the body lags more than maxLead behind; the target is never dragged back
        // to the body (that would let a disturbance push the whole machine away for good)
        Vec3 next = basePos.add(vel.mul(dt));
        // measured against the swayed target the body is meant to follow, not the bare base (sway is not lag)
        Vec3 lead = new Vec3(next.x() + sway.x() - body.position().x(), 0, next.z() + sway.z() - body.position().z());
        if (lead.length() > params.maxLead() && lead.dot(vel) > 0) { next = basePos; pausedLead++; }
        // a leg waiting for the body to sway over its neighbours pauses the gait clock; the body must wait too, or it
        // walks past the feet it is standing on
        for (int i = 0; i < legs.size(); i++) if (planner.isHeld(i)) { next = basePos; pausedHeld++; break; }
        basePos = next;
        double plantedY = 0;
        int planted = 0;
        for (Leg l : legs) if (l.stance) { plantedY += l.planted.y(); planted++; }
        double groundShift = planted == 0 ? 0 : plantedY / planted - footBaseY;
        crouchNow = Math.min(standDrop, crouchNow + standDrop * dt / CROUCH_SECONDS); // ease down, no jolt
        desiredPos = new Vec3(next.x(), standY0 - crouchNow + groundShift, next.z());

        // static gaits sway: before a leg lifts, move the body over the feet that will carry it (a square stance has
        // the CoM exactly on the diagonal of every remaining triangle, so without sway no leg could ever lift)
        Vec3 swayTarget = Vec3.ZERO;
        boolean moving = speedNow > 0.02;
        if (moving && !planner.gait().dynamic() && legs.size() >= 4) {
            int nextLeg = nextToLift();
            if (nextLeg >= 0) {
                Vec3 all = Vec3.ZERO, rest = Vec3.ZERO;
                int na = 0, nr = 0;
                for (int i = 0; i < legs.size(); i++) {
                    if (!legs.get(i).stance) continue;
                    all = all.add(legs.get(i).planted); na++;
                    if (i != nextLeg) { rest = rest.add(legs.get(i).planted); nr++; }
                }
                if (na > 0 && nr >= 3) {
                    Vec3 d = rest.mul(1.0 / nr).sub(all.mul(1.0 / na));
                    swayTarget = new Vec3(d.x(), 0, d.z());
                }
            }
        }
        double liftInterval = 1 / Math.max(1e-3, planner.frequency(Math.max(0.05, speedNow)) * legs.size());
        // standing still the sway eases back to centre on a fixed short time (the gait-based one would take ages)
        double swayTau = moving ? Math.max(0.3, params.swayTime() * liftInterval) : 0.5;
        sway = sway.add(swayTarget.sub(sway).mul(Math.min(1, dt / swayTau)));
        desiredPos = desiredPos.add(sway);

        // 2. gait
        double speed = speedNow;
        Vec3 capture = colossus.brain.balance.BalanceController.capturePoint(body.position(), s.linearVelocity(), footBaseY + groundShift);
        // deadlock breaker: a leg held longer than HOLD_RELAX only needs the CoM inside the remaining feet (no margin,
        // no capture point); otherwise a machine can stand forever with every lift vetoed (seen in game after ~15 blocks)
        boolean relaxed = heldSeconds > HOLD_RELAX;
        boolean[] stance = planner.advance(dt, speed,
                leg -> relaxed ? canLift(leg, body.position(), 0) : canLift(leg, body.position(), params.minMargin()) && canLift(leg, capture, params.minMargin()),
                leg -> true);
        boolean anyHeld = false;
        for (int i = 0; i < legs.size(); i++) anyHeld |= planner.isHeld(i);
        heldSeconds = anyHeld ? heldSeconds + dt : 0;

        // 3. foot targets
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            Vec3 actual = footWorld(backend, l);
            if (l.stance && !stance[i]) liftOff(l, i, vel, speed, yaw, body.position());
            else if (!l.stance && stance[i]) {
                // plant where the foot really came down (a short or blocked swing lands early), on the ground. It is
                // not re-read later: re-anchoring a stance foot (even once, after it settles) destabilised the walk
                l.planted = actual == null ? l.foothold : new Vec3(actual.x(), groundY(actual), actual.z());
                l.footHeading = desiredYaw;
                l.swing = null;
            }
            // a stance foot is never re-anchored to where it slid: following a sliding foot removes the force that would
            // stop it, and it runs away (seen: a planted hind foot dragged 2.7 m forward under the body)
            l.stance = stance[i];
        }

        // 4. legs: IK against the desired pose (upright, desired heading), then servos
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            Vec3 world = l.stance ? l.planted : l.swing.atEased(Math.max(0, planner.swingProgress(i)));
            l.target = world;
            solveAndDrive(backend, l, world, desiredPos, yaw, 1);
        }
    }

    /**
     * Solve one leg for a world foot target with the IK base frame at {@code basePos}/{@code baseRot} (the desired root
     * pose), drive its joints at {@code share} of their rating, and keep its foot level at the desired heading.
     * <p>
     * Solving against the desired pose (not the real one) is what moves and rights the body: stance feet stay planted,
     * so stiff legs push the body toward the pose they were solved for.
     */
    private void solveAndDrive(PhysicsBackend backend, Leg l, Vec3 footWorld, Vec3 basePos, Quat baseRot, double share) {
        // a planted foot keeps the heading it landed with; a swinging foot turns with the body
        Quat heading = Quat.axisAngle(Vec3.Y, l.stance ? l.footHeading : desiredYaw);
        // with an ankle the chain ends at the ankle, which sits above the (level) foot
        Vec3 effector = l.limb.hasAnkle() ? footWorld.sub(heading.rotate(l.limb.footBelowAnkle())) : footWorld;
        Quat inv = baseRot.conjugate();
        Quat legTurn = inv.mul(heading).normalize();
        IkSolver.Result r = LegIk.solve(l.limb.chain(), l.warm, inv.rotate(effector.sub(basePos)).sub(l.limb.hip().sub(rootComRel)), legTurn);
        l.warm = r.pose();
        l.lastError = r.error();
        drive(backend, l, r.pose(), share);
        if (l.ankle != null && l.ankle.handle() != null) {
            // foot orientation relative to the shin so that, in the world, the foot is level and faces the heading
            Quat shin = l.limb.chain().forward(r.pose()).end();
            Quat footInBase = inv.mul(heading);
            // swing only: a universal joint cannot twist about its through axis, and a servo asked to would push at full
            // effort against its own constraint until the strain meter snaps it
            Quat level = swingOnly(shin.conjugate().mul(footInBase).normalize(), l.ankle.axis());
            backend.setMotorOrientation(l.ankle.handle(), level, effort(l.ankle) * share);
        }
    }

    private double standY0, crouchNow;
    private Vec3 sway = Vec3.ZERO;
    private double speedNow, turnRate, heldSeconds;
    /** Mean horizontal distance from the root's centre of mass to the hips: the radius feet circle when turning. */
    private final double turnRadius;
    /** Most torque any leg servo is asked for, N·m (see {@link #servoBudget}). */
    private final double budget;

    /**
     * Servo torque the legs need, with margin: about 1.5× the machine's weight over a leg's length, shared by the legs.
     * Servos use the smaller of this and their joint's rating. Servo stiffness follows the torque limit, so a light
     * machine on big joints would otherwise be driven by springs sized for a far heavier load and shake itself apart.
     */
    public static double servoBudget(double machineMass, double legLength, int legs) {
        return 1.5 * machineMass * 9.81 * legLength / Math.max(1, legs);
    }
    /** Steps on which commanded progress paused, by reason (diagnostics). */
    public int pausedLead, pausedHeld;
    private static final double CROUCH_SECONDS = 1.0, ANKLELESS_CROUCH = 0.05, MAX_HEADING_LEAD = 0.3, HOLD_RELAX = 1.5;

    private void initialise(Pose body) {
        initialised = true;
        desiredPos = body.position();
        basePos = body.position();
        standY0 = body.position().y();
        // legs without an ankle tilt their feet onto an edge as the knees bend: crouch them only a little
        boolean ankles = legs.stream().allMatch(l -> l.limb.hasAnkle());
        standDrop = (ankles ? params.crouch() : Math.min(params.crouch(), ANKLELESS_CROUCH)) * legLength;
        desiredYaw = headingOf(body.rotation());
        double y = 0;
        for (Leg l : legs) {
            l.planted = body.toWorld(l.limb.footRest().sub(rootComRel));
            l.footHeading = desiredYaw;
            y += l.planted.y();
        }
        footBaseY = y / legs.size();
    }

    /** {@code q} with its twist about {@code axis} removed (swing–twist decomposition): q = swing · twist. */
    static Quat swingOnly(Quat q, Vec3 axis) {
        Vec3 a = axis.normalize();
        double p = q.x() * a.x() + q.y() * a.y() + q.z() * a.z();
        Quat twist = new Quat(q.w(), a.x() * p, a.y() * p, a.z() * p);
        double n = Math.sqrt(twist.w() * twist.w() + p * p);
        if (n < 1e-12) return q; // a half turn of pure swing: no twist to remove
        twist = new Quat(twist.w() / n, twist.x() / n, twist.y() / n, twist.z() / n);
        return q.mul(twist.conjugate()).normalize();
    }

    /**
     * Heading of a body as an angle about +Y in the same sense as {@code Quat.axisAngle(Vec3.Y, a)}.
     * ({@link Quat#yaw()} is Minecraft-style and has the opposite sign.)
     */
    static double headingOf(Quat q) { return -q.yaw(); }

    /** The stance leg whose phase reaches lift-off soonest, or −1. */
    private int nextToLift() {
        GaitPattern g = planner.gait();
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < legs.size(); i++) {
            if (!legs.get(i).stance) continue;
            double gap = g.dutyFactor() - g.legPhase(i, planner.clock());
            if (gap < 0) gap += 1;
            if (gap < bestGap) { bestGap = gap; best = i; }
        }
        return best;
    }

    /** Static rule: the point (CoM, or its capture point) must stay inside the feet that would remain planted. */
    private boolean canLift(int leg, Vec3 com, double margin) {
        List<Vec3> feet = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) if (i != leg && legs.get(i).stance) feet.add(legs.get(i).planted);
        if (feet.size() < 3) return feet.size() >= 1 && legs.size() <= 2; // bipeds are dynamic anyway
        return new SupportPolygon(feet).margin(com) >= margin;
    }

    /** Where the foot point really is, from physics; null if its body is gone. */
    private static Vec3 footWorld(PhysicsBackend backend, Leg l) {
        if (l.footBody == null) return null;
        BodyState fs = backend.bodyState(l.footBody);
        return fs == BodyState.MISSING ? null : fs.pose().toWorld(l.limb.footRest().sub(l.footComRel));
    }

    private void liftOff(Leg l, int i, Vec3 vel, double speed, Quat yaw, Vec3 bodyNow) {
        // home point: where this foot stood at assembly, under the desired body
        Vec3 home = desiredPos.add(yaw.rotate(l.limb.footRest().sub(rootComRel))).add(new Vec3(0, crouchNow, 0));
        double swingTime = planner.timeToTouchdown(i, speed);
        double stanceTime = planner.stanceTime(speed);
        double turn = turnRate * (swingTime + stanceTime * 0.5);
        Vec3 rel = home.sub(desiredPos);
        Vec3 relTurned = Quat.axisAngle(Vec3.Y, turn).rotate(rel);
        Vec3 ideal = desiredPos.add(relTurned).add(vel.mul(swingTime + stanceTime * 0.5));
        double ground = groundY(ideal);
        Vec3 foothold = new Vec3(ideal.x(), ground, ideal.z());
        // keep it reachable from where the hip will really be at touchdown (the body can lag its target)
        Vec3 bodyAtTouchdown = bodyNow.add(vel.mul(swingTime));
        Vec3 hip = bodyAtTouchdown.add(yaw.rotate(l.limb.hip().sub(rootComRel)));
        double reach = l.limb.chain().reach() * params.reachUse();
        Vec3 fromHip = foothold.sub(hip);
        if (fromHip.length() > reach) {
            double vertical = Math.min(reach * 0.999, Math.abs(fromHip.y()));
            double horizontal = Math.sqrt(reach * reach - vertical * vertical);
            Vec3 flat = new Vec3(fromHip.x(), 0, fromHip.z());
            Vec3 clamped = flat.length() < 1e-9 ? flat : flat.normalize().mul(Math.min(flat.length(), horizontal));
            foothold = new Vec3(hip.x() + clamped.x(), ground, hip.z() + clamped.z());
        }
        l.foothold = foothold;
        double obstacle = Math.max(groundY(l.planted.add(l.foothold).mul(0.5)), ground);
        l.swing = new SwingTrajectory(l.planted, l.foothold, params.stepHeight(), obstacle, 0.25);
    }

    /** Top of the ground under a point: the first solid block scanning down from 2 m above it (or its own height). */
    double groundY(Vec3 p) {
        int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
        int top = (int) Math.floor(p.y() + 2);
        for (int y = top; y > top - 8; y--) if (terrain.isSolid(x, y, z)) return y + 1;
        return p.y();
    }

    private void drive(PhysicsBackend backend, Leg l, IkChain.Pose pose, double share) {
        for (int k = 0; k < l.drives.length; k++) {
            Drive d = l.drives[k];
            if (d.handle() == null) continue;
            double effort = effort(d) * share;
            if (d.type().orientationMotor()) backend.setMotorOrientation(d.handle(), pose.ball[k], effort);
            else backend.setMotorTarget(d.handle(), pose.value[k], effort);
        }
    }

    /**
     * Servo limit for a drive: no more than the machine needs, and never more than {@link #RATING_SHARE} of the joint's
     * rating, so the brain cannot drive its own joints into the strain meter's snap zone (only outside loads can).
     */
    private double effort(Drive d) {
        double rated = d.maxEffort() * RATING_SHARE;
        return d.type() == JointType.SLIDER ? rated : Math.min(rated, budget);
    }

    /** Share of a joint's rating the brain's servos may use. */
    static final double RATING_SHARE = 0.7;
}
