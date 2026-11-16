package colossus.physics.math;

/** Rigid transform: position of the body's centre of mass plus orientation. */
public record Pose(Vec3 position, Quat rotation) {
    public static final Pose IDENTITY = new Pose(Vec3.ZERO, Quat.IDENTITY);

    /** Local point (relative to the centre of mass) to world. */
    public Vec3 toWorld(Vec3 local) { return position.add(rotation.rotate(local)); }

    /** World point to local (relative to the centre of mass). */
    public Vec3 toLocal(Vec3 world) { return rotation.inverseRotate(world.sub(position)); }

    public Pose interpolate(Pose o, double t) {
        return new Pose(position.lerp(o.position, t), rotation.slerp(o.rotation, t));
    }
}
