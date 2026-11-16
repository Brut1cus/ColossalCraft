package colossus.physics.math;

/** Immutable row-major 3x3 matrix (inertia tensors, small linear solves). */
public final class Mat3 {
    public static final Mat3 ZERO = new Mat3(0, 0, 0, 0, 0, 0, 0, 0, 0);
    public static final Mat3 IDENTITY = diagonal(1, 1, 1);

    private final double m00, m01, m02, m10, m11, m12, m20, m21, m22;

    public Mat3(double m00, double m01, double m02, double m10, double m11, double m12, double m20, double m21, double m22) {
        this.m00 = m00; this.m01 = m01; this.m02 = m02;
        this.m10 = m10; this.m11 = m11; this.m12 = m12;
        this.m20 = m20; this.m21 = m21; this.m22 = m22;
    }

    public static Mat3 diagonal(double a, double b, double c) { return new Mat3(a, 0, 0, 0, b, 0, 0, 0, c); }

    /** Rotation matrix of a unit quaternion. */
    public static Mat3 rotation(Quat q) {
        double w = q.w(), x = q.x(), y = q.y(), z = q.z();
        return new Mat3(
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y));
    }

    public double get(int r, int c) {
        return switch (r * 3 + c) {
            case 0 -> m00; case 1 -> m01; case 2 -> m02;
            case 3 -> m10; case 4 -> m11; case 5 -> m12;
            case 6 -> m20; case 7 -> m21; default -> m22;
        };
    }

    public Vec3 transform(Vec3 v) {
        return new Vec3(m00 * v.x() + m01 * v.y() + m02 * v.z(),
                m10 * v.x() + m11 * v.y() + m12 * v.z(),
                m20 * v.x() + m21 * v.y() + m22 * v.z());
    }

    public Mat3 add(Mat3 o) {
        return new Mat3(m00 + o.m00, m01 + o.m01, m02 + o.m02, m10 + o.m10, m11 + o.m11, m12 + o.m12, m20 + o.m20, m21 + o.m21, m22 + o.m22);
    }

    public Mat3 scale(double s) {
        return new Mat3(m00 * s, m01 * s, m02 * s, m10 * s, m11 * s, m12 * s, m20 * s, m21 * s, m22 * s);
    }

    public Mat3 mul(Mat3 o) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double s = 0;
            for (int k = 0; k < 3; k++) s += get(i, k) * o.get(k, j);
            r[i * 3 + j] = s;
        }
        return new Mat3(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
    }

    public Mat3 transpose() { return new Mat3(m00, m10, m20, m01, m11, m21, m02, m12, m22); }

    public double determinant() {
        return m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20);
    }

    /** Inverse, or ZERO when singular (an immovable body has zero inverse inertia). */
    public Mat3 inverse() {
        double det = determinant();
        if (Math.abs(det) < 1e-18) return ZERO;
        double id = 1.0 / det;
        return new Mat3(
                (m11 * m22 - m12 * m21) * id, (m02 * m21 - m01 * m22) * id, (m01 * m12 - m02 * m11) * id,
                (m12 * m20 - m10 * m22) * id, (m00 * m22 - m02 * m20) * id, (m02 * m10 - m00 * m12) * id,
                (m10 * m21 - m11 * m20) * id, (m01 * m20 - m00 * m21) * id, (m00 * m11 - m01 * m10) * id);
    }

    /** R * this * R^T: rotate a body-frame tensor into world frame. */
    public Mat3 rotated(Quat q) {
        Mat3 r = rotation(q);
        return r.mul(this).mul(r.transpose());
    }

    @Override public String toString() {
        return String.format("[[%.3f %.3f %.3f] [%.3f %.3f %.3f] [%.3f %.3f %.3f]]", m00, m01, m02, m10, m11, m12, m20, m21, m22);
    }
}
