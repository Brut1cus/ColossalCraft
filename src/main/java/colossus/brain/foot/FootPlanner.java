package colossus.brain.foot;

import colossus.physics.math.Vec3;

import java.util.Optional;

/**
 * Where to step. Predicts the body at touchdown, adds the Raibert term that places the foot further ahead when moving
 * faster than desired, raycasts the ground, and spirals outward to the best alternative if the ideal spot is rejected.
 */
public final class FootPlanner {

    /** Ground hit returned by the world probe (a raycast down against terrain and other Colossi). */
    public record GroundHit(Vec3 point, Vec3 normal, boolean fluid, boolean unsteppable, boolean occupied) {}

    /** World access for foot targeting; implemented by the game layer on a thread-safe terrain cache. */
    @FunctionalInterface
    public interface GroundProbe {
        /** Cast straight down from {@code from} for at most {@code maxDistance}; empty if nothing was hit. */
        Optional<GroundHit> castDown(Vec3 from, double maxDistance);
    }

    /** Tunables (Rigging GUI / data). */
    public record Settings(double raibertGain, double maxSlopeDegrees, double legReach, double probeHeight, double searchRadius, double searchStep) {
        public static Settings defaults(double legReach) { return new Settings(0.3, 45, legReach, 2.0, 2.5, 0.5); }
    }

    private final Settings s;

    public FootPlanner(Settings s) { this.s = s; }

    /**
     * Ideal foothold (before the ground check).
     *
     * @param homeWorld     the limb's home point, at the current body pose, in world space
     * @param velocity      body velocity
     * @param desiredVel    commanded velocity
     * @param yawRate       body yaw rate (rad/s)
     * @param bodyCenter    body centre (pivot for the yaw prediction)
     * @param timeToLand    seconds until this foot touches down
     * @param stanceTime    seconds the foot will spend on the ground
     */
    public Vec3 idealFoothold(Vec3 homeWorld, Vec3 velocity, Vec3 desiredVel, double yawRate, Vec3 bodyCenter, double timeToLand, double stanceTime) {
        // predicted home point at touchdown: translate with velocity, rotate around the body centre with yaw rate
        Vec3 rel = homeWorld.sub(bodyCenter);
        double yaw = yawRate * timeToLand;
        double c = Math.cos(yaw), sn = Math.sin(yaw);
        Vec3 relRot = new Vec3(rel.x() * c - rel.z() * sn, rel.y(), rel.x() * sn + rel.z() * c);
        Vec3 predicted = bodyCenter.add(velocity.mul(timeToLand)).add(relRot);
        // neutral point: foot lands half a stance ahead so the body passes over it symmetrically
        Vec3 flatVel = new Vec3(velocity.x(), 0, velocity.z());
        Vec3 neutral = flatVel.mul(stanceTime * 0.5);
        // Raibert correction k·(v − v_desired)
        Vec3 err = new Vec3(velocity.x() - desiredVel.x(), 0, velocity.z() - desiredVel.z());
        return predicted.add(neutral).add(err.mul(s.raibertGain));
    }

    /** True if a ground hit is a legal foothold. */
    public boolean accept(GroundHit hit, Vec3 hip) {
        if (hit.fluid() || hit.unsteppable() || hit.occupied()) return false;
        double slope = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, hit.normal().normalize().y()))));
        if (slope > s.maxSlopeDegrees) return false;
        return hit.point().distance(hip) <= s.legReach;
    }

    /**
     * Final foothold: the ideal point if legal, otherwise the best candidate on an outward spiral, ranked by distance
     * from the ideal, flatness and height change. Empty if nothing within the search radius is legal.
     */
    public Optional<GroundHit> choose(Vec3 ideal, Vec3 hip, GroundProbe probe) {
        Optional<GroundHit> direct = probeAt(ideal, hip, probe);
        if (direct.isPresent() && accept(direct.get(), hip)) return direct;
        GroundHit best = null;
        double bestScore = Double.MAX_VALUE;
        for (double r = s.searchStep; r <= s.searchRadius + 1e-9; r += s.searchStep) {
            int samples = Math.max(8, (int) Math.ceil(2 * Math.PI * r / s.searchStep));
            for (int i = 0; i < samples; i++) {
                double a = 2 * Math.PI * i / samples;
                Vec3 c = ideal.add(new Vec3(Math.cos(a) * r, 0, Math.sin(a) * r));
                Optional<GroundHit> h = probeAt(c, hip, probe);
                if (h.isEmpty() || !accept(h.get(), hip)) continue;
                GroundHit g = h.get();
                double flat = 1 - g.normal().normalize().y();
                double score = g.point().sub(ideal).rejectFrom(Vec3.Y).length() + 2 * flat + 0.5 * Math.abs(g.point().y() - ideal.y());
                if (score < bestScore) { bestScore = score; best = g; }
            }
            if (best != null) break; // nearest ring with a legal spot wins
        }
        return Optional.ofNullable(best);
    }

    private Optional<GroundHit> probeAt(Vec3 p, Vec3 hip, GroundProbe probe) {
        Vec3 from = new Vec3(p.x(), Math.max(p.y(), hip.y()) + s.probeHeight, p.z());
        return probe.castDown(from, s.probeHeight + s.legReach * 2);
    }

    /** Re-target mid-swing only if the ideal moved more than this (prevents jitter). */
    public static boolean shouldRetarget(Vec3 committed, Vec3 newIdeal, double threshold) {
        return committed.sub(newIdeal).rejectFrom(Vec3.Y).length() > threshold;
    }
}
