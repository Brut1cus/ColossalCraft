package colossus.brain.ik;

import colossus.physics.math.Vec3;

/**
 * Hip, knee, hock (reversed hinge) chain with the hock angle locked to {@code ratio × knee angle}, which keeps the
 * animal look. Solved as a two-bone problem whose second bone is the knee→foot span at the coupled angle, repeated
 * until the coupling settles.
 */
public final class Analytic3BoneDigitigrade implements IkSolver {
    private final double ratio;

    public Analytic3BoneDigitigrade(double ratio) { this.ratio = ratio; }

    @Override public Result solve(IkChain chain, IkChain.Pose warm, Vec3 target) {
        if (chain.size() != 3) return new Fabrik().solve(chain, warm, target);
        IkChain.Pose pose = warm.copy();
        for (int it = 0; it < 10; it++) {
            pose.value[2] = ratio * pose.value[1];
            chain.clamp(pose);
            IkChain.Fk fk = chain.forward(pose);
            Vec3 root = fk.positions()[0], knee = fk.positions()[1], foot = fk.effector();
            double l1 = knee.distance(root), l2 = foot.distance(knee);
            Vec3 bend = chain.pole != null ? chain.pole : knee.sub(root.add(foot).mul(0.5));
            Vec3 k = Analytic2Bone.knee(root, target, l1, l2, bend);
            IkSolver.align(chain, pose, 0, knee, k);
            IkSolver.align(chain, pose, 1, chain.forward(pose).effector(), target);
            if (chain.forward(pose).effector().distance(target) < TOLERANCE * 0.5
                    && Math.abs(pose.value[2] - ratio * pose.value[1]) < 1e-3) break;
        }
        pose.value[2] = ratio * pose.value[1];
        chain.clamp(pose);
        return IkSolver.finish(chain, pose, target);
    }
}
