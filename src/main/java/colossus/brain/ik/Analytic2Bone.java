package colossus.brain.ik;

import colossus.physics.math.Vec3;

/**
 * Exact two-bone solver (thigh + shin) by the law of cosines. The knee is placed in the plane spanned by the
 * root→target line and the chain's pole vector (or the current knee position when no pole is set).
 * Also used as the inner step of {@link Analytic3BoneDigitigrade}.
 */
public final class Analytic2Bone implements IkSolver {

    /** Knee position for bones of length l1, l2 reaching from {@code root} to {@code target}, bending toward {@code bendDir}. */
    public static Vec3 knee(Vec3 root, Vec3 target, double l1, double l2, Vec3 bendDir) {
        Vec3 d = target.sub(root);
        double dist = Math.max(1e-9, Math.min(d.length(), l1 + l2 - 1e-9));
        dist = Math.max(dist, Math.abs(l1 - l2) + 1e-9);
        Vec3 dir = d.normalize();
        if (dir.lengthSq() == 0) dir = new Vec3(0, -1, 0);
        double a = (l1 * l1 - l2 * l2 + dist * dist) / (2 * dist);     // distance from root to the knee's foot point
        double hgt = Math.sqrt(Math.max(0, l1 * l1 - a * a));
        Vec3 bend = bendDir.rejectFrom(dir).normalize();
        if (bend.lengthSq() == 0) bend = dir.anyPerpendicular();
        return root.add(dir.mul(a)).add(bend.mul(hgt));
    }

    @Override public Result solve(IkChain chain, IkChain.Pose warm, Vec3 target) {
        if (chain.size() != 2) return new Fabrik().solve(chain, warm, target);
        IkChain.Pose pose = warm.copy();
        chain.clamp(pose);
        IkChain.Fk fk = chain.forward(pose);
        Vec3 root = fk.positions()[0];
        double l1 = fk.positions()[1].distance(root), l2 = fk.effector().distance(fk.positions()[1]);
        Vec3 bend = chain.pole != null ? chain.pole : fk.positions()[1].sub(root.add(fk.effector()).mul(0.5));
        Vec3 k = knee(root, target, l1, l2, bend);
        IkSolver.align(chain, pose, 0, chain.forward(pose).positions()[1], k);
        IkSolver.align(chain, pose, 1, chain.forward(pose).effector(), target);
        // a second pass absorbs the small error from hinge axes that are not exactly perpendicular to the plane
        IkSolver.align(chain, pose, 0, chain.forward(pose).effector(), target);
        return IkSolver.finish(chain, pose, target);
    }
}
