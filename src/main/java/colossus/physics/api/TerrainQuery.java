package colossus.physics.api;

/**
 * Static world geometry as seen by the physics thread. The game thread builds an immutable, thread-safe view
 * (cached per chunk section, invalidated on block change) so the physics thread never reads a live Level.
 */
public interface TerrainQuery {
    /** True if the unit cell whose min corner is (x, y, z) blocks movement. */
    boolean isSolid(int x, int y, int z);

    /** Surface friction coefficient of that cell (ice ≈ 0.02, stone ≈ 0.7). */
    default double friction(int x, int y, int z) { return 0.6; }

    TerrainQuery NONE = (x, y, z) -> false;

    /** Infinite flat ground: every cell with y below {@code topY} is solid. */
    static TerrainQuery flat(int topY, double friction) {
        return new TerrainQuery() {
            @Override public boolean isSolid(int x, int y, int z) { return y < topY; }
            @Override public double friction(int x, int y, int z) { return friction; }
        };
    }
}
