package colossus.physics.math;

/** Immutable unit quaternion (w, x, y, z) for Segment orientation. */
public record Quat(double w, double x, double y, double z) {
    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    public static Quat axisAngle(Vec3 axis, double radians) {
        Vec3 a = axis.normalize();
        double h = radians * 0.5, s = Math.sin(h);
        return new Quat(Math.cos(h), a.x() * s, a.y() * s, a.z() * s);
    }

    /** Shortest rotation taking unit vector a onto unit vector b. */
    public static Quat fromTo(Vec3 a, Vec3 b) {
        double d = a.dot(b);
        if (d > 1 - 1e-12) return IDENTITY;
        if (d < -1 + 1e-12) return axisAngle(a.anyPerpendicular(), Math.PI);
        Vec3 c = a.cross(b);
        return new Quat(1 + d, c.x(), c.y(), c.z()).normalize();
    }

    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conjugate() { return new Quat(w, -x, -y, -z); }

    public double dot(Quat o) { return w * o.w + x * o.x + y * o.y + z * o.z; }

    public Quat normalize() {
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        return l < 1e-12 ? IDENTITY : new Quat(w / l, x / l, y / l, z / l);
    }

    public Quat neg() { return new Quat(-w, -x, -y, -z); }

    /** Rotate a vector by this quaternion. */
    public Vec3 rotate(Vec3 v) {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        Vec3 q = new Vec3(x, y, z);
        Vec3 t = q.cross(v).mul(2);
        return v.add(t.mul(w)).add(q.cross(t));
    }

    public Vec3 inverseRotate(Vec3 v) { return conjugate().rotate(v); }

    /** Vector part (x, y, z). */
    public Vec3 vec() { return new Vec3(x, y, z); }

    /**
     * Integrate an angular velocity over dt (XPBD style): q += 0.5 * dt * [ω,0] * q, then normalize.
     */
    public Quat integrate(Vec3 omega, double dt) {
        Quat dq = new Quat(0, omega.x(), omega.y(), omega.z()).mul(this);
        double h = 0.5 * dt;
        return new Quat(w + h * dq.w, x + h * dq.x, y + h * dq.y, z + h * dq.z).normalize();
    }

    /** Rotation angle in radians, in [0, π]. */
    public double angle() {
        double cw = Math.min(1, Math.abs(w));
        return 2 * Math.acos(cw);
    }

    /** Rotation vector (axis * angle), shortest path. */
    public Vec3 toRotationVector() {
        Quat q = w < 0 ? neg() : this;
        double s = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
        if (s < 1e-12) return new Vec3(2 * q.x, 2 * q.y, 2 * q.z);
        double a = 2 * Math.atan2(s, q.w);
        return new Vec3(q.x / s * a, q.y / s * a, q.z / s * a);
    }

    public Quat slerp(Quat o, double t) {
        double d = dot(o);
        Quat b = o;
        if (d < 0) { d = -d; b = o.neg(); }
        if (d > 0.9995) {
            return new Quat(w + (b.w - w) * t, x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t).normalize();
        }
        double th = Math.acos(d), s = Math.sin(th);
        double a1 = Math.sin((1 - t) * th) / s, a2 = Math.sin(t * th) / s;
        return new Quat(w * a1 + b.w * a2, x * a1 + b.x * a2, y * a1 + b.y * a2, z * a1 + b.z * a2);
    }

    /** Yaw (rotation around +Y) in radians, Minecraft-style X/Z plane. */
    public double yaw() {
        Vec3 f = rotate(Vec3.Z);
        return Math.atan2(-f.x(), f.z());
    }

    @Override public String toString() { return String.format("Quat(%.4f, %.4f, %.4f, %.4f)", w, x, y, z); }
}
