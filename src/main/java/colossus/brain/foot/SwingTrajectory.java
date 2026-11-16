package colossus.brain.foot;

import colossus.physics.math.Vec3;

/**
 * Swing-phase foot path: a cubic Bézier from lift-off to foothold whose apex clears max(step height, highest obstacle
 * along the path + clearance). The IK target follows this curve; joint motors chase the IK angles.
 */
public final class SwingTrajectory {
    private final Vec3 p0, p1, p2, p3;
    public final double apexHeight;

    /**
     * @param liftOff        foot position at lift-off
     * @param foothold       committed foothold
     * @param stepHeight     minimum lift (Rigging GUI)
     * @param obstacleTop    highest ground point found by sweeping along the path (world Y), or −∞ if none
     * @param clearance      extra height above obstacles
     */
    public SwingTrajectory(Vec3 liftOff, Vec3 foothold, double stepHeight, double obstacleTop, double clearance) {
        double base = Math.max(liftOff.y(), foothold.y());
        double apex = Math.max(base + stepHeight, obstacleTop + clearance);
        this.apexHeight = apex;
        // With both inner control points at height H, the curve's height at t = ½ is 0.125·(y0 + y3) + 0.75·H
        // (its maximum when the ends are level), so pick H to put that point at the apex.
        double h = (apex - 0.125 * (liftOff.y() + foothold.y())) / 0.75;
        Vec3 d = foothold.sub(liftOff);
        this.p0 = liftOff;
        this.p1 = new Vec3(liftOff.x() + d.x() * 0.1, h, liftOff.z() + d.z() * 0.1);
        this.p2 = new Vec3(liftOff.x() + d.x() * 0.9, h, liftOff.z() + d.z() * 0.9);
        this.p3 = foothold;
    }

    /** Foot position at swing progress t ∈ [0, 1]. */
    public Vec3 at(double t) {
        double u = Math.max(0, Math.min(1, t)), v = 1 - u;
        return p0.mul(v * v * v).add(p1.mul(3 * v * v * u)).add(p2.mul(3 * v * u * u)).add(p3.mul(u * u * u));
    }

    /** Smoothstep time warp so the foot accelerates gently off the ground and decelerates before touchdown. */
    public Vec3 atEased(double t) {
        double u = Math.max(0, Math.min(1, t));
        return at(u * u * (3 - 2 * u));
    }
}
