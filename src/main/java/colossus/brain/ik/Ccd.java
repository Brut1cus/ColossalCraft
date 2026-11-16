package colossus.brain.ik;

import colossus.physics.math.Vec3;

/**
 * Cyclic Coordinate Descent: simple and stable, the default for tails, tentacles and cranes.
 * Relies on the warm start for bend direction: a dead-straight chain against a one-sided knee limit is a local minimum.
 */
public final class Ccd implements IkSolver {
    private final int iterations;

    public Ccd() { this(24); }
    public Ccd(int iterations) { this.iterations = iterations; }

    @Override public Result solve(IkChain chain, IkChain.Pose warm, Vec3 target) {
        IkChain.Pose p = warm.copy();
        chain.clamp(p);
        IkChain.Pose tipFirst = sweep(chain, p.copy(), target, false);
        if (chain.forward(tipFirst).effector().distance(target) < TOLERANCE) return IkSolver.finish(chain, tipFirst, target);
        // tip-first sweeps can fold the chain into a local minimum (a slewing base facing away from the target);
        // a root-first pass from the same warm start usually escapes it. Keep whichever lands closer.
        IkChain.Pose rootFirst = sweep(chain, p.copy(), target, true);
        double a = chain.forward(tipFirst).effector().distance(target), b = chain.forward(rootFirst).effector().distance(target);
        return IkSolver.finish(chain, b < a ? rootFirst : tipFirst, target);
    }

    private IkChain.Pose sweep(IkChain chain, IkChain.Pose p, Vec3 target, boolean rootFirstOnce) {
        for (int it = 0; it < iterations; it++) {
            boolean forward = rootFirstOnce && it == 0;
            for (int k = 0; k < chain.size(); k++) {
                int i = forward ? k : chain.size() - 1 - k;
                Vec3 end = chain.forward(p).effector();
                IkSolver.align(chain, p, i, end, target);
            }
            if (chain.forward(p).effector().distance(target) < TOLERANCE * 0.5) break;
        }
        return p;
    }
}
