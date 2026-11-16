package colossus.kinetics;

import java.util.*;

/**
 * The mod's own rotational network: RPM (signed) plus torque, like Create's speed + stress but with sag instead of a
 * hard stop. Shaft groups rotate together; gear links multiply speed by a ratio (and divide torque). Sources declare a
 * stall torque and a no-load target speed; consumers declare torque demand at the current speed.
 * <p>
 * {@link #solve()} finds the operating speed where supplied torque equals demanded torque (both referred to one
 * reference group), reports per-group speed and transmitted torque, a jam if gear ratios or source directions conflict,
 * and every part whose transmitted torque exceeds its rating.
 */
public final class KineticNetwork {

    /** A source: linear torque curve τ(ω) = stallTorque · (1 − ω/targetRpm), 0 beyond target. */
    public record Source(int group, double targetRpm, double stallTorque) {}

    /** A consumer: torque demand as a function of its own RPM (absolute value). */
    public record Consumer(int group, java.util.function.DoubleUnaryOperator demandAtRpm) {
        public static Consumer constant(int group, double torque) { return new Consumer(group, rpm -> torque); }
        /** Propeller-like load: τ = k·rpm². */
        public static Consumer quadratic(int group, double k) { return new Consumer(group, rpm -> k * rpm * rpm); }
    }

    /** A rated part in a group (shaft, bearing, gearbox...). */
    public record Part(String name, int group, double ratedTorque) {}

    /** speed(b) = ratio · speed(a). Negative ratio = reversing gear. */
    public record GearLink(int a, int b, double ratio) {}

    public record GroupState(double rpm, double torque) {}

    public record State(boolean jammed, String jamReason, double referenceRpm, Map<Integer, GroupState> groups,
                        List<Part> overloaded, double powerWatts) {}

    private final Set<Integer> groups = new TreeSet<>();
    private final List<Source> sources = new ArrayList<>();
    private final List<Consumer> consumers = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();
    private final List<GearLink> links = new ArrayList<>();

    public KineticNetwork addGroup(int g) { groups.add(g); return this; }
    public KineticNetwork add(Source s) { groups.add(s.group()); sources.add(s); return this; }
    public KineticNetwork add(Consumer c) { groups.add(c.group()); consumers.add(c); return this; }
    public KineticNetwork add(Part p) { groups.add(p.group()); parts.add(p); return this; }
    public KineticNetwork link(int a, int b, double ratio) { groups.add(a); groups.add(b); links.add(new GearLink(a, b, ratio)); return this; }

    public State solve() {
        if (groups.isEmpty()) return new State(false, null, 0, Map.of(), List.of(), 0);
        // 1. ratios relative to a reference group, detecting inconsistent gear loops
        int ref = sources.isEmpty() ? groups.iterator().next() : sources.get(0).group();
        Map<Integer, Double> ratio = new HashMap<>();
        ratio.put(ref, 1.0);
        Deque<Integer> queue = new ArrayDeque<>(List.of(ref));
        while (!queue.isEmpty()) {
            int g = queue.poll();
            for (GearLink l : links) {
                int other; double r;
                if (l.a() == g) { other = l.b(); r = ratio.get(g) * l.ratio(); }
                else if (l.b() == g) { other = l.a(); r = ratio.get(g) / l.ratio(); }
                else continue;
                Double known = ratio.get(other);
                if (known == null) { ratio.put(other, r); queue.add(other); }
                else if (Math.abs(known - r) > 1e-6 * Math.max(1, Math.abs(r))) return jam("conflicting gear ratios between groups " + g + " and " + other, ratio);
            }
        }
        // 2. sources referred to the reference: target speed and torque
        double refTarget = Double.POSITIVE_INFINITY;
        int dir = 0;
        for (Source s : sources) {
            Double r = ratio.get(s.group());
            if (r == null || s.targetRpm() == 0) continue;
            double t = s.targetRpm() / r;   // reference-group speed at which this source reaches its target
            int sd = (int) Math.signum(t);
            if (dir != 0 && sd != dir) return jam("sources drive in opposite directions", ratio);
            dir = sd;
            refTarget = Math.min(refTarget, Math.abs(t));
        }
        if (dir == 0) return finish(0, ratio, false, null);

        // 3. bisection on |ω_ref| in [0, refTarget] for supply(ω) = demand(ω), both as reference-referred torque
        double lo = 0, hi = refTarget;
        if (netTorque(hi, dir, ratio) >= 0) return finish(dir * hi, ratio, false, null);
        if (netTorque(0, dir, ratio) <= 0) return finish(0, ratio, false, null); // stalled
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (netTorque(mid, dir, ratio) > 0) lo = mid; else hi = mid;
        }
        return finish(dir * 0.5 * (lo + hi), ratio, false, null);
    }

    /** Supplied minus demanded torque at reference speed w (≥ 0), referred to the reference group (power-equivalent). */
    private double netTorque(double w, int dir, Map<Integer, Double> ratio) {
        double supply = 0, demand = 0;
        for (Source s : sources) {
            Double r = ratio.get(s.group());
            if (r == null) continue;
            double rpm = Math.abs(w * r);
            double frac = Math.abs(s.targetRpm()) == 0 ? 0 : Math.max(0, 1 - rpm / Math.abs(s.targetRpm()));
            supply += s.stallTorque() * frac * Math.abs(r);
        }
        for (Consumer c : consumers) {
            Double r = ratio.get(c.group());
            if (r == null) continue;
            demand += c.demandAtRpm().applyAsDouble(Math.abs(w * r)) * Math.abs(r);
        }
        return supply - demand;
    }

    private State finish(double refRpm, Map<Integer, Double> ratio, boolean jammed, String why) {
        Map<Integer, GroupState> out = new TreeMap<>();
        // transmitted torque per group ≈ demand of everything at and beyond that group, approximated by the total
        // demand referred to the group (conservative for tree-shaped trains)
        double totalRefDemand = 0;
        for (Consumer c : consumers) {
            Double r = ratio.get(c.group());
            if (r != null) totalRefDemand += c.demandAtRpm().applyAsDouble(Math.abs(refRpm * r)) * Math.abs(r);
        }
        for (int g : groups) {
            Double r = ratio.get(g);
            if (r == null) { out.put(g, new GroupState(0, 0)); continue; }
            out.put(g, new GroupState(refRpm * r, totalRefDemand / Math.abs(r)));
        }
        List<Part> over = new ArrayList<>();
        for (Part p : parts) {
            GroupState s = out.get(p.group());
            if (s != null && s.torque() > p.ratedTorque()) over.add(p);
        }
        double power = totalRefDemand * Math.abs(refRpm) * 2 * Math.PI / 60;
        return new State(jammed, why, refRpm, out, over, power);
    }

    private State jam(String why, Map<Integer, Double> ratio) {
        Map<Integer, GroupState> out = new TreeMap<>();
        for (int g : groups) out.put(g, new GroupState(0, 0));
        return new State(true, why, 0, out, List.of(), 0);
    }

    /** Watts from torque (N·m) and RPM. */
    public static double power(double torque, double rpm) { return torque * rpm * 2 * Math.PI / 60; }
}
