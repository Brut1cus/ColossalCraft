package colossus.physics.xpbd;

import colossus.physics.api.ConstraintDesc;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

/** Joint state inside the XPBD solver. */
final class XpbdJoint {
    final int id;
    final ConstraintDesc d;
    final XpbdBody a, b;
    /** Rest relative rotation qA⁻¹·qB captured at creation (FIXED, DOCK, SLIDER, SPRING_DAMPER; ball/universal servo reference). */
    final Quat restRel;
    /** Reference vectors perpendicular to the axis, in each body's frame, aligned at creation (angle = 0). */
    final Vec3 refA, refB;

    boolean motorOn;
    double motorTarget, motorMax;
    /** Orientation servo (ball, universal): target rotation relative to {@link #restRel}, in A's frame. */
    Quat motorRot = Quat.IDENTITY;
    boolean broken;

    // per-step accumulators
    Vec3 impulseSum = Vec3.ZERO, angularSum = Vec3.ZERO;
    int substeps;
    double substepH = 1;
    double position, velocity, lastForce, lastTorque;

    XpbdJoint(int id, ConstraintDesc d, XpbdBody a, XpbdBody b) {
        this.id = id;
        this.d = d;
        this.a = a;
        this.b = b;
        this.restRel = a.q.conjugate().mul(b.q);
        this.refA = d.axisA().anyPerpendicular();
        Vec3 refWorld = a.q.rotate(refA);
        this.refB = b.q.inverseRotate(refWorld);
    }
}
