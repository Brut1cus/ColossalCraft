package colossus.physics.api;

import colossus.physics.math.Mat3;
import colossus.physics.math.Vec3;

import java.util.List;

/**
 * Mass, centre of mass and inertia tensor of one Segment, summed from block cells
 * (each cell a uniform cuboid, moved to the common centre of mass with the parallel-axis theorem).
 *
 * @param mass          total mass in kg
 * @param centerOfMass  in the Segment's block frame (ship-space coordinates relative to the plot origin)
 * @param inertia       about the centre of mass, body frame, kg·m²
 */
public record MassProperties(double mass, Vec3 centerOfMass, Mat3 inertia) {

    public static final MassProperties NONE = new MassProperties(0, Vec3.ZERO, Mat3.ZERO);

    /** Incremental builder: add() per block cell, remove() when a block is broken, build() at the end. */
    public static final class Builder {
        private double mass;
        private double sx, sy, sz;           // Σ m·c
        // Σ over cells of (own inertia about own centre) + m·(|c|² I − c cᵀ)   (about the frame origin)
        private double ixx, iyy, izz, ixy, ixz, iyz;

        /** Adds a cuboid cell of mass m centred at c with dimensions (dx, dy, dz). */
        public Builder add(double m, Vec3 c, double dx, double dy, double dz) {
            return accumulate(m, c, dx, dy, dz, 1);
        }

        /** Unit block cell whose min corner is the integer position (x, y, z). */
        public Builder addBlock(double m, int x, int y, int z) {
            return add(m, new Vec3(x + 0.5, y + 0.5, z + 0.5), 1, 1, 1);
        }

        public Builder remove(double m, Vec3 c, double dx, double dy, double dz) {
            return accumulate(m, c, dx, dy, dz, -1);
        }

        private Builder accumulate(double m, Vec3 c, double dx, double dy, double dz, int sign) {
            double s = sign * m;
            mass += s;
            sx += s * c.x(); sy += s * c.y(); sz += s * c.z();
            double own = s / 12.0;
            ixx += own * (dy * dy + dz * dz) + s * (c.y() * c.y() + c.z() * c.z());
            iyy += own * (dx * dx + dz * dz) + s * (c.x() * c.x() + c.z() * c.z());
            izz += own * (dx * dx + dy * dy) + s * (c.x() * c.x() + c.y() * c.y());
            ixy -= s * c.x() * c.y();
            ixz -= s * c.x() * c.z();
            iyz -= s * c.y() * c.z();
            return this;
        }

        public double mass() { return mass; }

        public MassProperties build() {
            if (mass <= 1e-9) return NONE;
            Vec3 com = new Vec3(sx / mass, sy / mass, sz / mass);
            // shift from frame origin to the centre of mass: I_com = I_o − M(|c|² I − c cᵀ)
            double cx = com.x(), cy = com.y(), cz = com.z();
            double jxx = ixx - mass * (cy * cy + cz * cz);
            double jyy = iyy - mass * (cx * cx + cz * cz);
            double jzz = izz - mass * (cx * cx + cy * cy);
            double jxy = ixy + mass * cx * cy;
            double jxz = ixz + mass * cx * cz;
            double jyz = iyz + mass * cy * cz;
            return new MassProperties(mass, com, new Mat3(jxx, jxy, jxz, jxy, jyy, jyz, jxz, jyz, jzz));
        }
    }

    /** Convenience: mass properties of a list of unit blocks with given masses. */
    public static MassProperties ofBlocks(List<int[]> positions, List<Double> masses) {
        Builder b = new Builder();
        for (int i = 0; i < positions.size(); i++) {
            int[] p = positions.get(i);
            b.addBlock(masses.get(i), p[0], p[1], p[2]);
        }
        return b.build();
    }

    /** Solid box of uniform density, for tests and simple shapes. */
    public static MassProperties solidBox(double mass, Vec3 size) {
        double x = size.x(), y = size.y(), z = size.z();
        return new MassProperties(mass, Vec3.ZERO, Mat3.diagonal(mass / 12 * (y * y + z * z), mass / 12 * (x * x + z * z), mass / 12 * (x * x + y * y)));
    }
}
