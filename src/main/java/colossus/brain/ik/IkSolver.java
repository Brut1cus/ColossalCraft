package colossus.brain.ik;

import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

/**
 * Turns a foothold (target in the chain's base frame) into joint coordinates. All solvers respect joint limits every
 * iteration and warm-start from the previous solution so poses do not flip between frames.
 */
public interface IkSolver {

    /**
     * @param reached   end effector within tolerance of the target
     * @param outOfReach target is further than the chain can stretch; the gait planner reads this to shorten stride
     * @param error     remaining distance in metres
     */
    record Result(IkChain.Pose pose, Vec3 effector, double error, boolean reached, boolean outOfReach) {}

    Result solve(IkChain chain, IkChain.Pose warmStart, Vec3 target);

    double TOLERANCE = 0.01;

    /** Picks the blueprint's default solver for a chain shape. */
    static IkSolver defaultFor(IkChain chain) {
        boolean allHinge = chain.links.stream().allMatch(l -> l.kind() == IkChain.Kind.HINGE);
        boolean anySlider = chain.links.stream().anyMatch(l -> l.kind() == IkChain.Kind.SLIDER);
        if (chain.size() == 2 && allHinge) return new Analytic2Bone();
        if (anySlider) return new DampedLeastSquares();
        if (chain.size() >= 3 && chain.size() <= 12) return new Fabrik();
        return new Ccd();
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Rotate (or slide) joint i so that the point {@code from} (currently attached to joint i's subtree) moves toward
     * {@code to}, then clamp the joint. Positions are in the base frame.
     */
    static void align(IkChain chain, IkChain.Pose pose, int i, Vec3 from, Vec3 to) {
        IkChain.Fk fk = chain.forward(pose);
        IkChain.Link l = chain.links.get(i);
        Vec3 jp = fk.positions()[i];
        Quat parent = fk.parentFrames()[i];
        switch (l.kind()) {
            case HINGE -> {
                Vec3 ax = parent.rotate(l.axis());
                Vec3 a = from.sub(jp).rejectFrom(ax), b = to.sub(jp).rejectFrom(ax);
                if (a.lengthSq() < 1e-12 || b.lengthSq() < 1e-12) return;
                a = a.normalize(); b = b.normalize();
                double ang = Math.atan2(a.cross(b).dot(ax), a.dot(b));
                pose.value[i] += ang;
            }
            case BALL -> {
                Vec3 a = from.sub(jp).normalize(), b = to.sub(jp).normalize();
                if (a.lengthSq() == 0 || b.lengthSq() == 0) return;
                Quat world = Quat.fromTo(a, b);
                Quat local = parent.conjugate().mul(world).mul(parent);
                pose.ball[i] = local.mul(pose.ball[i]).normalize();
            }
            case SLIDER -> {
                Vec3 ax = parent.rotate(l.axis());
                pose.value[i] += to.sub(from).dot(ax);
            }
        }
        chain.clamp(pose);
    }

    static Result finish(IkChain chain, IkChain.Pose pose, Vec3 target) {
        Vec3 eff = chain.forward(pose).effector();
        double err = eff.distance(target);
        return new Result(pose, eff, err, err < TOLERANCE, target.length() > chain.reach() + TOLERANCE);
    }
}
