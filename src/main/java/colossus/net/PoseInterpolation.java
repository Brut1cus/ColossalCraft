package colossus.net;

import colossus.physics.math.Pose;

/**
 * Client-side pose interpolation between the last two {@link PoseSync} packets. Pure Java (unit-tested).
 * <p>
 * The client renders one sync interval behind the newest packet: at the moment a packet arrives it shows the previous
 * pose, and over the following interval it slides to the newest one. That keeps motion smooth with no extrapolation.
 */
public final class PoseInterpolation {
    /** Pose sync interval: one server tick. */
    public static final long INTERVAL_NANOS = 50_000_000L;

    private PoseInterpolation() {}

    /** Blend factor in [0, 1] from the time since the newest packet arrived. */
    public static double alpha(long nowNanos, long receivedAtNanos, long intervalNanos) {
        if (intervalNanos <= 0) return 1;
        double a = (double) (nowNanos - receivedAtNanos) / intervalNanos;
        return a <= 0 ? 0 : a >= 1 ? 1 : a;
    }

    public static Pose interpolate(Pose previous, Pose latest, double alpha) {
        if (alpha <= 0) return previous;
        if (alpha >= 1) return latest;
        return previous.interpolate(latest, alpha);
    }
}
