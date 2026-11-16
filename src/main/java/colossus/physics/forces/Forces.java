package colossus.physics.forces;

import colossus.physics.math.Vec3;

/**
 * Closed-form force models from the blueprint ("Forces applied every substep" and "Propulsion"). All SI units.
 * Pure functions; the per-Segment accumulators in the game layer call these and push results to the backend.
 */
public final class Forces {
    private Forces() {}

    public static final double AIR_DENSITY_SEA_LEVEL = 1.225;  // kg/m³
    public static final double WATER_DENSITY = 1000.0;
    public static final double LAVA_DENSITY = 3100.0;

    /**
     * Air density by altitude: exponential atmosphere with a configurable scale height (in blocks).
     * Sea level is Y = 63. Default scale height 600 blocks gives aircraft a natural ceiling well above build height.
     */
    public static double airDensity(double y, double scaleHeight) {
        double h = Math.max(0, y - 63);
        return AIR_DENSITY_SEA_LEVEL * Math.exp(-h / scaleHeight);
    }

    /** Quadratic drag F = −½ρ|v|²·Cd·A along −v̂, where A is the windward projected area. */
    public static Vec3 drag(Vec3 velocity, double density, double cd, double area) {
        double v2 = velocity.lengthSq();
        if (v2 < 1e-12) return Vec3.ZERO;
        return velocity.normalize().mul(-0.5 * density * v2 * cd * area);
    }

    /**
     * Projected area of an axis-aligned voxel body seen along a direction: the sum of exposed face areas weighted by
     * |n·d|. {@code exposedFaces} = number of exposed faces with normals +X,−X,+Y,−Y,+Z,−Z.
     */
    public static double projectedArea(int[] exposedFaces, Vec3 dir) {
        Vec3 d = dir.normalize();
        return Math.max(0, d.x()) * exposedFaces[0] + Math.max(0, -d.x()) * exposedFaces[1]
                + Math.max(0, d.y()) * exposedFaces[2] + Math.max(0, -d.y()) * exposedFaces[3]
                + Math.max(0, d.z()) * exposedFaces[4] + Math.max(0, -d.z()) * exposedFaces[5];
    }

    /** Buoyancy F = ρ_fluid·g·V_submerged, pointing against gravity. */
    public static Vec3 buoyancy(double fluidDensity, double submergedVolume, Vec3 gravity) {
        return gravity.mul(-fluidDensity * submergedVolume);
    }

    /**
     * Lift coefficient: linear in angle of attack up to stall, then falling off.
     * C_L = 2π·α (thin-airfoil) up to {@code stallAngle}, then decays to 40 % over the next 15°.
     */
    public static double liftCoefficient(double alpha, double stallAngle) {
        double a = Math.abs(alpha), s = Math.signum(alpha);
        if (a <= stallAngle) return s * 2 * Math.PI * a;
        double peak = 2 * Math.PI * stallAngle;
        double t = Math.min(1, (a - stallAngle) / Math.toRadians(15));
        return s * peak * (1 - 0.6 * t);
    }

    /** Wing lift L = ½ρv²·S·C_L(α). */
    public static double lift(double density, double airspeed, double area, double alpha, double stallAngle) {
        return 0.5 * density * airspeed * airspeed * area * liftCoefficient(alpha, stallAngle);
    }

    /** Induced drag coefficient C_Di = C_L² / (π·e·AR). */
    public static double inducedDragCoefficient(double cl, double aspectRatio, double oswald) {
        return cl * cl / (Math.PI * oswald * Math.max(0.1, aspectRatio));
    }

    /**
     * Propeller thrust T = C_T·ρ·n²·D⁴ (simplified momentum theory). n = rpm/60 (signed: reverse spin = reverse thrust).
     */
    public static double propellerThrust(double ct, double density, double rpm, double diameter) {
        double n = rpm / 60.0;
        return Math.signum(n) * ct * density * n * n * Math.pow(diameter, 4);
    }

    /**
     * Thrust coefficient from blade count and pitch. Solidity saturates past about 4 blades (diminishing returns).
     */
    public static double thrustCoefficient(int blades, double pitchRadians) {
        double solidity = 1 - Math.exp(-blades / 3.0);
        return 0.12 * solidity * Math.sin(2 * Math.max(-Math.PI / 4, Math.min(Math.PI / 4, pitchRadians)));
    }

    /** Rotor reaction torque Q ≈ T·D·k (k ≈ 0.05): the counter-torque a single rotor puts on its Segment. */
    public static double rotorReactionTorque(double thrust, double diameter) { return thrust * diameter * 0.05; }

    /** Airflow speed in a propeller's wash, fading with distance along the axis. */
    public static double washSpeed(double rpm, double diameter, double pitchRadians, double distance) {
        double tip = Math.abs(rpm) / 60.0 * Math.PI * diameter;
        double v0 = tip * Math.sin(Math.abs(pitchRadians)) * 0.5;
        double fade = distance <= diameter ? 1 : Math.exp(-(distance - diameter) / Math.max(1, diameter));
        return v0 * fade;
    }

    /** Lighter-than-air lift = V·(ρ_air − ρ_gas)·g. */
    public static double balloonLift(double gasVolume, double airDensity, double gasDensity, double g) {
        return gasVolume * Math.max(0, airDensity - gasDensity) * g;
    }

    /** Coulomb friction coefficient of two surfaces: geometric mean. */
    public static double combinedFriction(double a, double b) { return Math.sqrt(Math.max(0, a) * Math.max(0, b)); }
}
