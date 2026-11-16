package colossus.physics.math;

/**
 * Immutable double-precision 3D vector. 1 unit = 1 block = 1 metre.
 * Pure Java on purpose: the physics thread never touches Minecraft or JOML objects.
 */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 X = new Vec3(1, 0, 0);
    public static final Vec3 Y = new Vec3(0, 1, 0);
    public static final Vec3 Z = new Vec3(0, 0, 1);

    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 mul(double s) { return new Vec3(x * s, y * s, z * s); }
    public Vec3 mul(Vec3 o) { return new Vec3(x * o.x, y * o.y, z * o.z); }
    public Vec3 neg() { return new Vec3(-x, -y, -z); }
    public Vec3 addScaled(Vec3 o, double s) { return new Vec3(x + o.x * s, y + o.y * s, z + o.z * s); }
    public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
    public Vec3 cross(Vec3 o) { return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    public double lengthSq() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(lengthSq()); }
    public double distance(Vec3 o) { return sub(o).length(); }

    /** Unit vector, or ZERO if this vector is (almost) zero. */
    public Vec3 normalize() {
        double l = length();
        return l < 1e-12 ? ZERO : mul(1.0 / l);
    }

    public Vec3 lerp(Vec3 o, double t) { return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t); }

    /** Component of this vector perpendicular to the unit vector n. */
    public Vec3 rejectFrom(Vec3 n) { return sub(n.mul(dot(n))); }

    /** Any unit vector perpendicular to this (unit) vector. */
    public Vec3 anyPerpendicular() {
        Vec3 a = Math.abs(x) < 0.9 ? X : Y;
        return cross(a).normalize();
    }

    public double get(int i) { return i == 0 ? x : i == 1 ? y : z; }

    public boolean isFinite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }

    @Override public String toString() { return String.format("(%.3f, %.3f, %.3f)", x, y, z); }
}
