package colossus.brain.gait;

import java.util.Arrays;

/**
 * A gait: for each leg a phase offset in [0, 1) and one duty factor (fraction of the cycle a foot is on the ground).
 * Leg order for quadrupeds is LF, RF, LH, RH. Loaded from {@code data/<ns>/colossus/gaits/*.json} or built in.
 *
 * @param dynamic dynamic gaits (trot, gallop, biped walk) may lift a leg even if the remaining support polygon does not
 *                contain the centre of mass; the balance controller uses the capture point instead
 * @param minSpeed lower edge of the speed band this gait is chosen for (m/s)
 * @param maxSpeed upper edge (m/s)
 */
public record GaitPattern(String name, double[] phaseOffsets, double dutyFactor, boolean dynamic, double minSpeed, double maxSpeed) {

    public int legs() { return phaseOffsets.length; }

    /** Leg phase in [0, 1): 0 = touchdown, stance until dutyFactor, swing until 1. */
    public double legPhase(int leg, double clock) {
        double p = (clock + phaseOffsets[leg]) % 1.0;
        return p < 0 ? p + 1 : p;
    }

    public boolean inStance(int leg, double clock) { return legPhase(leg, clock) < dutyFactor; }

    /** Swing progress 0..1 (0 at lift-off), or −1 while in stance. */
    public double swingProgress(int leg, double clock) {
        double p = legPhase(leg, clock);
        return p < dutyFactor ? -1 : (p - dutyFactor) / (1 - dutyFactor);
    }

    // ------------------------------------------------------------------ presets from the blueprint

    /** Lateral-sequence walk (blueprint): legs lift LH, LF, RH, RF, a quarter cycle apart, so the body sways mostly fore-aft. */
    public static GaitPattern quadWalk() { return new GaitPattern("walk", new double[]{0.5, 0, 0.75, 0.25}, 0.75, false, 0, 1.5); }
    public static GaitPattern quadTrot() { return new GaitPattern("trot", new double[]{0, 0.5, 0.5, 0}, 0.5, true, 1.2, 4); }
    public static GaitPattern quadPace() { return new GaitPattern("pace", new double[]{0, 0.5, 0, 0.5}, 0.5, true, 1.2, 4); }
    public static GaitPattern quadGallop() { return new GaitPattern("gallop", new double[]{0, 0.1, 0.6, 0.5}, 0.35, true, 3.5, 99); }
    public static GaitPattern bipedWalk() { return new GaitPattern("biped_walk", new double[]{0, 0.5}, 0.6, true, 0, 2); }
    public static GaitPattern bipedRun() { return new GaitPattern("biped_run", new double[]{0, 0.5}, 0.4, true, 1.8, 99); }

    /** Tripod: one leg at a time (statically stable with 2 feet down when the CoM is inside). */
    public static GaitPattern tripodStep() { return new GaitPattern("tripod_step", new double[]{0, 1 / 3.0, 2 / 3.0}, 2 / 3.0, false, 0, 99); }

    /** Hexapod alternating tripod: legs ordered L1, R1, L2, R2, L3, R3. */
    public static GaitPattern alternatingTripod() {
        return new GaitPattern("alternating_tripod", new double[]{0, 0.5, 0.5, 0, 0, 0.5}, 0.5, false, 0.8, 99);
    }

    /** Wave gait for n legs: phase i/n, duty (n−1)/n. Most stable, slowest. */
    public static GaitPattern wave(int n) {
        double[] o = new double[n];
        for (int i = 0; i < n; i++) o[i] = (double) i / n;
        return new GaitPattern("wave", o, (n - 1.0) / n, false, 0, 1);
    }

    /** Ripple gait: left and right sides half a cycle apart, each side a wave. Legs ordered L1, R1, L2, R2, ... */
    public static GaitPattern ripple(int n) {
        int perSide = Math.max(1, n / 2);
        double[] o = new double[n];
        for (int i = 0; i < n; i++) {
            int idx = i / 2;
            o[i] = ((double) idx / perSide + (i % 2 == 0 ? 0 : 0.5)) % 1.0;
        }
        return new GaitPattern("ripple", o, Math.max(0.5, 1 - 2.0 / n), false, 0.5, 2);
    }

    /** Default gait set for a leg count, slowest first. */
    public static GaitPattern[] defaultsFor(int legs) {
        return switch (legs) {
            case 1 -> new GaitPattern[]{new GaitPattern("hop", new double[]{0}, 0.5, true, 0, 99)};
            case 2 -> new GaitPattern[]{bipedWalk(), bipedRun()};
            case 3 -> new GaitPattern[]{tripodStep()};
            case 4 -> new GaitPattern[]{quadWalk(), quadTrot(), quadGallop()};
            case 6 -> new GaitPattern[]{wave(6), ripple(6), alternatingTripod()};
            default -> new GaitPattern[]{wave(legs), ripple(legs)};
        };
    }

    @Override public String toString() { return name + Arrays.toString(phaseOffsets) + " duty=" + dutyFactor; }

    @Override public boolean equals(Object o) {
        return o instanceof GaitPattern g && g.name.equals(name) && Arrays.equals(g.phaseOffsets, phaseOffsets) && g.dutyFactor == dutyFactor;
    }

    @Override public int hashCode() { return name.hashCode() * 31 + Arrays.hashCode(phaseOffsets); }
}
