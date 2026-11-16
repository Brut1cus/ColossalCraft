package colossus.physics.api;

import colossus.physics.math.Vec3;

/**
 * A joint between two bodies.
 *
 * @param type         joint kind
 * @param a            parent body
 * @param b            child body
 * @param anchorA      joint point in A's local frame (relative to A's centre of mass)
 * @param anchorB      the same point in B's local frame
 * @param axisA        joint axis in A's frame (hinge axis, slider direction, ball cone axis)
 * @param axisB        the same axis in B's frame
 * @param lower        lower limit: radians (hinge) or metres (slider); ignored if {@code lower > upper}
 * @param upper        upper limit
 * @param coneAngle    ball joint swing limit in radians
 * @param twistAngle   ball joint twist limit (±) in radians
 * @param stiffness    spring stiffness N/m (spring damper) — also the motor P gain (N·m/rad or N/m)
 * @param damping      spring damping N·s/m — also the motor D gain
 * @param restLength   spring rest offset in metres
 * @param breakForce   constraint force in N above which the joint breaks (≤0 = unbreakable). Stress handling is game-side,
 *                     this is the absolute hard cap.
 * @param collideConnected if false (default) A and B never collide with each other
 */
public record ConstraintDesc(JointType type, BodyHandle a, BodyHandle b, Vec3 anchorA, Vec3 anchorB, Vec3 axisA, Vec3 axisB,
                             double lower, double upper, double coneAngle, double twistAngle,
                             double stiffness, double damping, double restLength, double breakForce, boolean collideConnected) {

    public static ConstraintDesc of(JointType type, BodyHandle a, BodyHandle b, Vec3 anchorA, Vec3 anchorB, Vec3 axisA, Vec3 axisB) {
        return new ConstraintDesc(type, a, b, anchorA, anchorB, axisA.normalize(), axisB.normalize(), 1, -1,
                Math.PI, Math.PI, 0, 0, 0, 0, false);
    }

    public ConstraintDesc withLimits(double lo, double hi) {
        return new ConstraintDesc(type, a, b, anchorA, anchorB, axisA, axisB, lo, hi, coneAngle, twistAngle, stiffness, damping, restLength, breakForce, collideConnected);
    }

    public ConstraintDesc withCone(double cone, double twist) {
        return new ConstraintDesc(type, a, b, anchorA, anchorB, axisA, axisB, lower, upper, cone, twist, stiffness, damping, restLength, breakForce, collideConnected);
    }

    public ConstraintDesc withSpring(double k, double c, double rest) {
        return new ConstraintDesc(type, a, b, anchorA, anchorB, axisA, axisB, lower, upper, coneAngle, twistAngle, k, c, rest, breakForce, collideConnected);
    }

    public ConstraintDesc withBreakForce(double f) {
        return new ConstraintDesc(type, a, b, anchorA, anchorB, axisA, axisB, lower, upper, coneAngle, twistAngle, stiffness, damping, restLength, f, collideConnected);
    }

    public boolean hasLimits() { return lower <= upper; }
}
