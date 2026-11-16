package colossus.brain.ik;

import colossus.physics.math.Mat3;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

/**
 * Damped Least Squares (Levenberg–Marquardt Jacobian IK): Δθ = Jᵀ(JJᵀ + λ²I)⁻¹e.
 * Handles mixed degrees of freedom (hinges, ball joints as 3 parameters, sliders) and stays well-behaved near
 * singularities (straight legs). The Jacobian is built numerically, so any joint type works.
 */
public final class DampedLeastSquares implements IkSolver {
    private final int iterations;
    private final double lambda;

    public DampedLeastSquares() { this(40, 0.15); }
    public DampedLeastSquares(int iterations, double lambda) { this.iterations = iterations; this.lambda = lambda; }

    @Override public Result solve(IkChain chain, IkChain.Pose warm, Vec3 target) {
        IkChain.Pose pose = warm.copy();
        chain.clamp(pose);
        int m = 0;
        for (IkChain.Link l : chain.links) m += l.kind() == IkChain.Kind.BALL ? 3 : 1;
        final double eps = 1e-5;
        for (int it = 0; it < iterations; it++) {
            Vec3 eff = chain.forward(pose).effector();
            Vec3 e = target.sub(eff);
            if (e.length() < TOLERANCE * 0.5) break;
            // clamp the step so linearisation stays valid
            if (e.length() > 1.0) e = e.normalize();
            Vec3[] cols = new Vec3[m];
            int c = 0;
            for (int i = 0; i < chain.size(); i++) {
                int dof = chain.links.get(i).kind() == IkChain.Kind.BALL ? 3 : 1;
                for (int k = 0; k < dof; k++) {
                    IkChain.Pose t = pose.copy();
                    perturb(chain, t, i, k, eps);
                    cols[c++] = chain.forward(t).effector().sub(eff).mul(1 / eps);
                }
            }
            // JJᵀ + λ²I  (3x3)
            double[][] a = new double[3][3];
            for (Vec3 col : cols) for (int r = 0; r < 3; r++) for (int s = 0; s < 3; s++) a[r][s] += col.get(r) * col.get(s);
            double l2 = lambda * lambda;
            Mat3 inv = new Mat3(a[0][0] + l2, a[0][1], a[0][2], a[1][0], a[1][1] + l2, a[1][2], a[2][0], a[2][1], a[2][2] + l2).inverse();
            Vec3 y = inv.transform(e);
            c = 0;
            for (int i = 0; i < chain.size(); i++) {
                int dof = chain.links.get(i).kind() == IkChain.Kind.BALL ? 3 : 1;
                for (int k = 0; k < dof; k++) perturb(chain, pose, i, k, cols[c++].dot(y));
            }
            chain.clamp(pose);
        }
        return IkSolver.finish(chain, pose, target);
    }

    private static void perturb(IkChain chain, IkChain.Pose p, int i, int k, double d) {
        if (chain.links.get(i).kind() == IkChain.Kind.BALL) {
            Vec3 axis = k == 0 ? Vec3.X : k == 1 ? Vec3.Y : Vec3.Z;
            p.ball[i] = Quat.axisAngle(axis, d).mul(p.ball[i]).normalize();
        } else {
            p.value[i] += d;
        }
    }
}
