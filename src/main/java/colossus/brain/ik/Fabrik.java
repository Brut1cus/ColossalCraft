package colossus.brain.ik;

import colossus.physics.math.Vec3;

/**
 * FABRIK (Forward And Backward Reaching IK) for chains of 3 to 12 joints. Solves joint positions first, then converts
 * them to constrained joint coordinates bone by bone (projecting onto each joint's limits), and finishes with a short
 * CCD polish so the limited result still lands on the target when it can.
 */
public final class Fabrik implements IkSolver {
    private final int iterations;

    public Fabrik() { this(16); }
    public Fabrik(int iterations) { this.iterations = iterations; }

    @Override public Result solve(IkChain chain, IkChain.Pose warm, Vec3 target) {
        IkChain.Pose pose = warm.copy();
        chain.clamp(pose);
        int n = chain.size();
        Vec3[] p = chain.forward(pose).positions().clone();
        double[] len = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) { len[i] = p[i + 1].distance(p[i]); total += len[i]; }
        Vec3 root = p[0];

        if (chain.pole != null) {
            // nudge interior joints toward the pole so the chain bends the intended way
            for (int i = 1; i < n; i++) p[i] = p[i].add(chain.pole.normalize().mul(0.05 * total));
        }

        if (target.distance(root) >= total) {
            Vec3 dir = target.sub(root).normalize();
            for (int i = 0; i < n; i++) p[i + 1] = p[i].add(dir.mul(len[i]));
        } else {
            for (int it = 0; it < iterations; it++) {
                p[n] = target;
                for (int i = n - 1; i >= 0; i--) p[i] = p[i + 1].add(p[i].sub(p[i + 1]).normalize().mul(len[i]));
                p[0] = root;
                for (int i = 0; i < n; i++) p[i + 1] = p[i].add(p[i + 1].sub(p[i]).normalize().mul(len[i]));
                if (chain.pole != null) applyPole(p, chain.pole);
                if (p[n].distance(target) < TOLERANCE * 0.5) break;
            }
        }

        // positions -> constrained joint coordinates
        for (int i = 0; i < n; i++) {
            Vec3[] cur = chain.forward(pose).positions();
            IkSolver.align(chain, pose, i, cur[i + 1], p[i + 1]);
        }
        // polish against the real target with limits enforced
        return new Ccd(6).solve(chain, pose, target);
    }

    /**
     * Pole constraint: swing each interior joint around the line through its neighbours onto the pole side.
     * Distances to both neighbours are unchanged, so bone lengths stay exact.
     */
    static void applyPole(Vec3[] p, Vec3 pole) {
        for (int i = 1; i < p.length - 1; i++) {
            Vec3 a = p[i - 1], b = p[i + 1];
            Vec3 line = b.sub(a).normalize();
            if (line.lengthSq() == 0) continue;
            Vec3 foot = a.add(line.mul(p[i].sub(a).dot(line)));
            Vec3 off = p[i].sub(foot);
            Vec3 want = pole.rejectFrom(line).normalize();
            if (want.lengthSq() == 0 || off.lengthSq() < 1e-12) continue;
            p[i] = foot.add(want.mul(off.length()));
        }
    }
}
