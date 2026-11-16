package colossus.brain.gait;

import java.util.function.IntPredicate;

/**
 * Advances a phase clock at a frequency set by commanded speed, picks the gait for that speed with hysteresis, and
 * enforces the stability-margin rule: in a static gait, a leg may not lift if the remaining support polygon would leave
 * the centre of mass unsupported. A leg that cannot reach its foothold delays its lift-off.
 */
public final class GaitPlanner {
    private final GaitPattern[] gaits;
    private int current;
    private double clock;
    private final boolean[] wasStance;
    private final boolean[] held;
    /** Stride length at which one gait cycle covers it (metres); the walker's leg length sets this. */
    private final double strideLength;
    /** Speed hysteresis band in m/s. */
    private final double hysteresis;

    public GaitPlanner(GaitPattern[] gaitsSlowestFirst, double strideLength) {
        this(gaitsSlowestFirst, strideLength, 0.25);
    }

    public GaitPlanner(GaitPattern[] gaitsSlowestFirst, double strideLength, double hysteresis) {
        if (gaitsSlowestFirst.length == 0) throw new IllegalArgumentException("no gaits");
        int legs = gaitsSlowestFirst[0].legs();
        for (GaitPattern g : gaitsSlowestFirst) if (g.legs() != legs) throw new IllegalArgumentException("gaits disagree on leg count");
        this.gaits = gaitsSlowestFirst.clone();
        this.strideLength = strideLength;
        this.hysteresis = hysteresis;
        this.wasStance = new boolean[legs];
        this.held = new boolean[legs];
        java.util.Arrays.fill(wasStance, true);
    }

    public GaitPattern gait() { return gaits[current]; }

    public double clock() { return clock; }

    public int legs() { return wasStance.length; }

    /** Cycle frequency (Hz) for a ground speed: one stride per stance phase. */
    public double frequency(double speed) {
        GaitPattern g = gait();
        double stanceDistance = strideLength;
        double stanceTime = stanceDistance / Math.max(0.05, Math.abs(speed));
        return Math.min(3.0, g.dutyFactor() / stanceTime);
    }

    /** Choose the gait for a speed, moving up/down only past the hysteresis band. */
    void selectGait(double speed) {
        double s = Math.abs(speed);
        while (current < gaits.length - 1 && s > gaits[current].maxSpeed() + hysteresis) current++;
        while (current > 0 && s < gaits[current].minSpeed() - hysteresis) current--;
    }

    /**
     * Advance by dt seconds.
     *
     * @param speed          commanded ground speed (m/s)
     * @param canLift        leg → true if lifting it now keeps the CoM supported (static rule); ignored for dynamic gaits
     * @param footholdReady  leg → true if that leg has a valid reachable foothold to swing to
     * @return per-leg contact plan for this tick
     */
    public boolean[] advance(double dt, double speed, IntPredicate canLift, IntPredicate footholdReady) {
        selectGait(speed);
        GaitPattern g = gait();
        boolean moving = Math.abs(speed) > 0.02;
        double next = moving ? clock + frequency(speed) * dt : clock;
        boolean[] stance = new boolean[legs()];
        boolean anyHeld = false;
        for (int leg = 0; leg < legs(); leg++) {
            boolean wantStance = !moving || g.inStance(leg, next);
            if (wasStance[leg] && !wantStance) {
                boolean ok = footholdReady.test(leg) && (g.dynamic() || canLift.test(leg));
                if (!ok) { wantStance = true; anyHeld = true; }
            }
            held[leg] = wasStance[leg] && wantStance && moving && !g.inStance(leg, next);
            stance[leg] = wantStance;
        }
        // a held leg pauses the clock so neighbours keep their phase relationship (they shorten stride instead)
        if (!anyHeld) clock = next % 1.0;
        System.arraycopy(stance, 0, wasStance, 0, stance.length);
        return stance;
    }

    /** True if the leg wanted to lift last tick but was held down. */
    public boolean isHeld(int leg) { return held[leg]; }

    /** Swing progress 0..1 for a leg in swing at the current clock, −1 in stance. */
    public double swingProgress(int leg) { return wasStance[leg] ? -1 : Math.max(0, gait().swingProgress(leg, clock)); }

    /** Seconds until this leg next touches down (used for foothold prediction). */
    public double timeToTouchdown(int leg, double speed) {
        GaitPattern g = gait();
        double p = g.legPhase(leg, clock);
        double remaining = 1 - p; // touchdown at phase 0 (== 1)
        return remaining / Math.max(1e-3, frequency(speed));
    }

    /** Stance duration in seconds at a speed (Raibert term). */
    public double stanceTime(double speed) { return gait().dutyFactor() / Math.max(1e-3, frequency(speed)); }
}
