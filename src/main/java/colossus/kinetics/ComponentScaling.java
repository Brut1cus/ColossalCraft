package colossus.kinetics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Size-based mechanical scaling (blueprint § "Size-Based Mechanical Scaling", which replaces material tiers).
 * <p>
 * All components share one baseline material. An N×N footprint of identical parts merges into one larger part whose
 * torque and holding load grow as a power of N (cross-section × radius ≈ N³ for torque by default). RPM is never
 * limited by size. Every coefficient is data-driven: {@code data/<ns>/colossus/component_scaling/*.json}.
 */
public final class ComponentScaling {

    public enum Component { BEARING, JOINT, GEARBOX, SHAFT, MOTOR, PROPELLER_HUB, WHEEL, PISTON }

    /**
     * @param baseTorque     1x1 max torque in N·m
     * @param baseLoad       1x1 max holding load in N
     * @param torqueExponent torque multiplier = N^torqueExponent
     * @param loadExponent   load multiplier = N^loadExponent
     */
    public record Coefficients(double baseTorque, double baseLoad, double torqueExponent, double loadExponent) {
        public double torque(int n) { return baseTorque * Math.pow(Math.max(1, n), torqueExponent); }
        public double load(int n) { return baseLoad * Math.pow(Math.max(1, n), loadExponent); }
        public double torqueMultiplier(int n) { return Math.pow(Math.max(1, n), torqueExponent); }
    }

    private final Map<Component, Coefficients> table = new EnumMap<>(Component.class);

    public ComponentScaling() { resetToDefaults(); }

    /**
     * Built-in defaults; datapacks override per component. Calibrated against block masses (iron 7.8 t, planks 0.4 t):
     * a 1x1 bearing holds a few hundred kilonewton-metres' worth of machine, so a plank-built walker on 2x2 joints walks.
     */
    public void resetToDefaults() {
        table.put(Component.BEARING, new Coefficients(100_000, 250_000, 3.0, 2.5));
        table.put(Component.JOINT, new Coefficients(100_000, 250_000, 3.0, 2.5));
        table.put(Component.GEARBOX, new Coefficients(75_000, 200_000, 3.0, 2.5));
        table.put(Component.SHAFT, new Coefficients(50_000, 150_000, 3.0, 2.0));
        table.put(Component.MOTOR, new Coefficients(40_000, 150_000, 3.0, 2.0));
        table.put(Component.PROPELLER_HUB, new Coefficients(60_000, 200_000, 3.0, 2.5));
        table.put(Component.WHEEL, new Coefficients(50_000, 300_000, 3.0, 2.5));
        table.put(Component.PISTON, new Coefficients(0, 600_000, 0, 2.5));
    }

    public void set(Component c, Coefficients k) { table.put(c, k); }

    public Coefficients get(Component c) { return table.get(c); }

    public double torqueCapacity(Component c, int footprint) { return table.get(c).torque(footprint); }

    public double loadCapacity(Component c, int footprint) { return table.get(c).load(footprint); }

    /**
     * Footprint N of a merged part from its block count, for a filled N×N square. Non-square groups do not merge
     * (each block stays 1x1), matching the "filled N×N square, same axis" rule.
     */
    public static int footprintOf(int blocks) {
        int n = (int) Math.round(Math.sqrt(blocks));
        return n * n == blocks ? n : 1;
    }
}
