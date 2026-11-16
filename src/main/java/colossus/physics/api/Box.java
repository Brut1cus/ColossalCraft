package colossus.physics.api;

import colossus.physics.math.Vec3;

/** Axis-aligned box in a body's local frame (relative to its centre of mass). Collision shapes are compounds of these. */
public record Box(Vec3 min, Vec3 max) {
    public Vec3 center() { return min.add(max).mul(0.5); }
    public Vec3 size() { return max.sub(min); }
    public double volume() { Vec3 s = size(); return s.x() * s.y() * s.z(); }
    public Box offset(Vec3 d) { return new Box(min.add(d), max.add(d)); }
}
