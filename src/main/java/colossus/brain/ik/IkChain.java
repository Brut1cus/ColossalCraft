package colossus.brain.ik;

import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A limb as the IK solvers see it: an ordered chain of joints from the root Segment to the end effector (foot, claw).
 * Frames are expressed relative to the chain's base (the root Segment's frame at the first joint).
 */
public final class IkChain {

    public enum Kind { HINGE, BALL, SLIDER }

    /**
     * One joint plus the bone that follows it.
     *
     * @param kind   joint type
     * @param axis   hinge axis / slider direction / ball cone axis, in the parent frame at zero pose (unit)
     * @param bone   vector from this joint to the next joint (or end effector), in this joint's frame at zero pose
     * @param min    hinge lower limit (rad) or slider lower limit (m); ball: unused
     * @param max    hinge upper limit (rad) or slider upper limit (m); ball: cone half-angle (rad)
     */
    public record Link(Kind kind, Vec3 axis, Vec3 bone, double min, double max) {
        public static Link hinge(Vec3 axis, Vec3 bone, double min, double max) { return new Link(Kind.HINGE, axis.normalize(), bone, min, max); }
        public static Link ball(Vec3 coneAxis, Vec3 bone, double cone) { return new Link(Kind.BALL, coneAxis.normalize(), bone, 0, cone); }
        public static Link slider(Vec3 dir, Vec3 bone, double min, double max) { return new Link(Kind.SLIDER, dir.normalize(), bone, min, max); }
    }

    /** Joint coordinates. Hinge: angle; slider: extension; ball: local rotation. */
    public static final class Pose {
        public final double[] value;
        public final Quat[] ball;

        public Pose(int n) {
            value = new double[n];
            ball = new Quat[n];
            java.util.Arrays.fill(ball, Quat.IDENTITY);
        }

        public Pose copy() {
            Pose p = new Pose(value.length);
            System.arraycopy(value, 0, p.value, 0, value.length);
            System.arraycopy(ball, 0, p.ball, 0, ball.length);
            return p;
        }
    }

    public final List<Link> links;
    /** Preferred bend direction for knees (in the base frame); null = no preference. */
    public Vec3 pole;

    public IkChain(List<Link> links) { this.links = List.copyOf(links); }

    public int size() { return links.size(); }

    public double reach() {
        double r = 0;
        for (Link l : links) r += l.bone.length() + (l.kind == Kind.SLIDER ? Math.max(0, l.max) : 0);
        return r;
    }

    public Quat localRotation(Pose p, int i) {
        Link l = links.get(i);
        return switch (l.kind) {
            case HINGE -> Quat.axisAngle(l.axis, p.value[i]);
            case BALL -> p.ball[i];
            case SLIDER -> Quat.IDENTITY;
        };
    }

    /** Forward kinematics: world-frame (base-frame) joint positions [0..n−1], end effector at [n], and orientations. */
    public Fk forward(Pose p) {
        int n = size();
        Vec3[] pos = new Vec3[n + 1];
        Quat[] frame = new Quat[n + 1];
        Vec3 at = Vec3.ZERO;
        Quat q = Quat.IDENTITY;
        for (int i = 0; i < n; i++) {
            Link l = links.get(i);
            if (l.kind == Kind.SLIDER) at = at.add(q.rotate(l.axis).mul(p.value[i]));
            pos[i] = at;
            Quat parent = q;
            q = q.mul(localRotation(p, i));
            frame[i] = parent;
            at = at.add(q.rotate(l.bone));
        }
        pos[n] = at;
        frame[n] = q;
        return new Fk(pos, frame, q);
    }

    /**
     * @param positions joint positions, end effector last
     * @param parentFrames orientation of each joint's parent frame (the frame its axis is expressed in)
     * @param end end-effector orientation
     */
    public record Fk(Vec3[] positions, Quat[] parentFrames, Quat end) {
        public Vec3 effector() { return positions[positions.length - 1]; }
    }

    /** Clamp every joint into its limits. */
    public void clamp(Pose p) {
        for (int i = 0; i < size(); i++) {
            Link l = links.get(i);
            switch (l.kind) {
                case HINGE, SLIDER -> { if (l.min <= l.max) p.value[i] = Math.max(l.min, Math.min(l.max, p.value[i])); }
                case BALL -> p.ball[i] = clampCone(p.ball[i], l.axis, l.max);
            }
        }
    }

    /** Limit the swing of a ball rotation so the rotated cone axis stays within {@code cone} of the rest axis. */
    static Quat clampCone(Quat q, Vec3 axis, double cone) {
        Vec3 r = q.rotate(axis);
        double ang = Math.acos(Math.max(-1, Math.min(1, r.dot(axis))));
        if (ang <= cone) return q;
        Quat swing = Quat.fromTo(axis, r);
        Quat twist = swing.conjugate().mul(q);
        Vec3 n = axis.cross(r).normalize();
        if (n.lengthSq() == 0) n = axis.anyPerpendicular();
        return Quat.axisAngle(n, cone).mul(twist).normalize();
    }

    /** Builder for the common 2- and 3-segment legs. */
    public static IkChain of(Link... links) {
        List<Link> l = new ArrayList<>(List.of(links));
        return new IkChain(l);
    }
}
