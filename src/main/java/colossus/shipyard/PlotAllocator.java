package colossus.shipyard;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Ship-space plots: far-away regions in the same level as the Colossus (the Valkyrien Skies approach), starting at
 * X = Z = 20,000,000 on a 1024-block grid. Each Segment gets one plot (several side by side if it is taller than the
 * level's build height). Freed plots are reused, lowest index first, so the region stays compact.
 */
public final class PlotAllocator {
    public static final int DEFAULT_ORIGIN = 20_000_000;
    public static final int DEFAULT_GRID = 1024;
    /** Vanilla world border limit; plots stay inside it. */
    public static final int WORLD_LIMIT = 29_999_984;

    /** A reserved region: {@code [minX, minX + size) × [minZ, minZ + size)} over the level's full height. */
    public record Plot(int index, int minX, int minZ, int size) {
        public int centerX() { return minX + size / 2; }
        public int centerZ() { return minZ + size / 2; }
        public boolean contains(int x, int z) { return x >= minX && x < minX + size && z >= minZ && z < minZ + size; }
        /** First and last chunk coordinates covered (for force-loading tickets). */
        public int minChunkX() { return minX >> 4; }
        public int minChunkZ() { return minZ >> 4; }
        public int maxChunkX() { return (minX + size - 1) >> 4; }
        public int maxChunkZ() { return (minZ + size - 1) >> 4; }
    }

    private final int origin, grid, perRow;
    private final BitSet used = new BitSet();

    public PlotAllocator() { this(DEFAULT_ORIGIN, DEFAULT_GRID); }

    public PlotAllocator(int origin, int grid) {
        this.origin = origin;
        this.grid = grid;
        this.perRow = (WORLD_LIMIT - origin) / grid;
        if (perRow <= 0) throw new IllegalArgumentException("origin too close to the world limit");
    }

    public int capacity() { return perRow * perRow; }

    public Plot plot(int index) {
        int gx = index % perRow, gz = index / perRow;
        return new Plot(index, origin + gx * grid, origin + gz * grid, grid);
    }

    public Plot allocate() {
        int i = used.nextClearBit(0);
        if (i >= capacity()) throw new IllegalStateException("ship space is full");
        used.set(i);
        return plot(i);
    }

    /** {@code count} plots for one tall Segment ("stacked plots side by side"), stitched into one body by the caller. */
    public List<Plot> allocate(int count) {
        List<Plot> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(allocate());
        return out;
    }

    public void free(int index) { used.clear(index); }

    public boolean isUsed(int index) { return used.get(index); }

    public void markUsed(int index) { used.set(index); }

    /** Plot containing a block position, or null when outside ship space. */
    public Plot at(int x, int z) {
        if (x < origin || z < origin) return null;
        int gx = (x - origin) / grid, gz = (z - origin) / grid;
        if (gx >= perRow || gz >= perRow) return null;
        return plot(gz * perRow + gx);
    }

    /** True if a world position lies in the ship-space region (terrain physics and mob spawning ignore it). */
    public boolean inShipSpace(int x, int z) { return at(x, z) != null; }

    public long[] save() { return used.toLongArray(); }

    public void load(long[] bits) {
        used.clear();
        used.or(BitSet.valueOf(bits));
    }

    /** Plots needed for a Segment of the given height in a level with this build height. */
    public static int plotsForHeight(int segmentHeight, int levelHeight) {
        return Math.max(1, (segmentHeight + levelHeight - 1) / levelHeight);
    }
}
