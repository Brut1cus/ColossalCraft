package colossus.brain.balance;

import colossus.physics.math.Vec3;

import java.util.List;

/**
 * Holds the torso up and steers it with forces from stance legs. The torso is never placed:
 * <ol>
 *   <li>support polygon = convex hull of planted feet</li>
 *   <li>desired CoM above a point inside it (capture point for dynamic gaits)</li>
 *   <li>PD control on height, attitude and horizontal position → desired wrench on the body</li>
 *   <li>wrench split across stance feet within friction cones</li>
 *   <li>each foot force mapped to joint torques with τ = JᵀF (see {@link #jacobianTranspose})</li>
 * </ol>
 */
public final class BalanceController {

    /** PD gains, normally auto-tuned from body mass and inertia. */
    public record Gains(double kpPos, double kdPos, double kpHeight, double kdHeight, double kpAtt, double kdAtt) {
        /** Critically damped gains for a body of mass m and inertia i with natural frequency ω (rad/s). */
        public static Gains autoTune(double mass, double inertia, double omega) {
            return new Gains(mass * omega * omega, 2 * mass * omega, mass * omega * omega * 2, 2 * mass * omega * 1.4,
                    inertia * omega * omega, 2 * inertia * omega);
        }
    }

    /** Desired net force and torque on the torso (world frame). */
    public record Wrench(Vec3 force, Vec3 torque) {}

    /** Body state the controller reads (measured from physics). */
    public record BodyState(Vec3 com, Vec3 velocity, Vec3 attitudeError, Vec3 angularVelocity) {}

    public static final double G = 9.81;

    /** Capture point: CoM + v·√(h/g) projected on the ground. Where the robot must step to come to rest. */
    public static Vec3 capturePoint(Vec3 com, Vec3 velocity, double groundY) {
        double h = Math.max(0.01, com.y() - groundY);
        double t = Math.sqrt(h / G);
        return new Vec3(com.x() + velocity.x() * t, groundY, com.z() + velocity.z() * t);
    }

    /**
     * Desired wrench.
     *
     * @param target     desired CoM position (x, z inside the support polygon; y = ride height)
     * @param targetVel  desired CoM velocity (commanded twist)
     * @param mass       total mass
     * @param dynamic    use the capture point instead of the CoM for the horizontal error
     */
    public static Wrench wrench(BodyState s, Vec3 target, Vec3 targetVel, double mass, Gains g, boolean dynamic, double groundY) {
        Vec3 ref = dynamic ? capturePoint(s.com(), s.velocity(), groundY) : s.com();
        double ex = target.x() - ref.x(), ez = target.z() - ref.z();
        double evx = targetVel.x() - s.velocity().x(), evz = targetVel.z() - s.velocity().z();
        double fx = g.kpPos() * ex + g.kdPos() * evx;
        double fz = g.kpPos() * ez + g.kdPos() * evz;
        double fy = mass * G + g.kpHeight() * (target.y() - s.com().y()) + g.kdHeight() * (targetVel.y() - s.velocity().y());
        Vec3 tq = s.attitudeError().mul(g.kpAtt()).sub(s.angularVelocity().mul(g.kdAtt()));
        return new Wrench(new Vec3(fx, Math.max(0, fy), fz), tq);
    }

    /**
     * Split a wrench over stance feet. Solves the 6×3n least-squares problem min Σ|f_i|² s.t. Σf_i = F, Σ r_i×f_i = τ
     * via the normal equations (pseudo-inverse), then projects each force into its friction cone (no pulling on the
     * ground, tangential ≤ μ·normal).
     *
     * @param feet   foot contact points (world)
     * @param com    point the torque is taken about
     * @param mu     ground friction
     */
    public static Vec3[] distribute(Wrench w, List<Vec3> feet, Vec3 com, double mu) {
        int n = feet.size();
        Vec3[] out = new Vec3[n];
        if (n == 0) return out;
        int m = 3 * n;
        // A (6 x m): rows 0..2 sum of forces, rows 3..5 sum of r × f
        double[][] a = new double[6][m];
        for (int i = 0; i < n; i++) {
            Vec3 r = feet.get(i).sub(com);
            for (int k = 0; k < 3; k++) a[k][3 * i + k] = 1;
            // r × f = [ry fz − rz fy, rz fx − rx fz, rx fy − ry fx]
            a[3][3 * i + 1] = -r.z(); a[3][3 * i + 2] = r.y();
            a[4][3 * i] = r.z();      a[4][3 * i + 2] = -r.x();
            a[5][3 * i] = -r.y();     a[5][3 * i + 1] = r.x();
        }
        double[] b = {w.force().x(), w.force().y(), w.force().z(), w.torque().x(), w.torque().y(), w.torque().z()};
        // f = Aᵀ (A Aᵀ + εI)⁻¹ b
        double[][] aat = new double[6][6];
        for (int r = 0; r < 6; r++) for (int c = 0; c < 6; c++) {
            double s = 0;
            for (int k = 0; k < m; k++) s += a[r][k] * a[c][k];
            aat[r][c] = s + (r == c ? 1e-6 : 0);
        }
        double[] y = solve(aat, b);
        for (int i = 0; i < n; i++) {
            double fx = 0, fy = 0, fz = 0;
            for (int r = 0; r < 6; r++) {
                fx += a[r][3 * i] * y[r];
                fy += a[r][3 * i + 1] * y[r];
                fz += a[r][3 * i + 2] * y[r];
            }
            out[i] = frictionCone(new Vec3(fx, fy, fz), mu);
        }
        return out;
    }

    /** Clamp a foot force into the friction cone with +Y normal. */
    public static Vec3 frictionCone(Vec3 f, double mu) {
        double fn = Math.max(0, f.y());
        double ft = Math.hypot(f.x(), f.z());
        double max = mu * fn;
        if (ft <= max || ft < 1e-12) return new Vec3(f.x(), fn, f.z());
        double s = max / ft;
        return new Vec3(f.x() * s, fn, f.z() * s);
    }

    /**
     * Joint torques for a desired foot force: τ_i = J_iᵀ F. For a revolute joint at position p_i with world axis a_i,
     * J_i = a_i × (foot − p_i), so τ_i = (a_i × (foot − p_i)) · F. The foot pushes the ground with F, so pass −F_body.
     */
    public static double[] jacobianTranspose(Vec3[] jointPositions, Vec3[] jointAxes, Vec3 foot, Vec3 force) {
        double[] tau = new double[jointPositions.length];
        for (int i = 0; i < tau.length; i++) tau[i] = jointAxes[i].cross(foot.sub(jointPositions[i])).dot(force);
        return tau;
    }

    /** Gaussian elimination with partial pivoting for small dense systems. */
    static double[] solve(double[][] m, double[] b) {
        int n = b.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) { System.arraycopy(m[i], 0, a[i], 0, n); a[i][n] = b[i]; }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            double[] t = a[c]; a[c] = a[p]; a[p] = t;
            if (Math.abs(a[c][c]) < 1e-15) continue;
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                for (int k = c; k <= n; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = Math.abs(a[i][i]) < 1e-15 ? 0 : a[i][n] / a[i][i];
        return x;
    }

    /**
     * Fall detection: true if the capture point is outside every foothold the legs can reach within {@code reach}.
     * Triggers a recovery step (fastest leg swings to the capture point) or a controlled crouch.
     */
    public static boolean falling(Vec3 capturePoint, SupportPolygon support, List<Vec3> hips, double reach) {
        if (support.contains(capturePoint)) return false;
        for (Vec3 h : hips) if (Math.hypot(h.x() - capturePoint.x(), h.z() - capturePoint.z()) <= reach) return false;
        return true;
    }
}
