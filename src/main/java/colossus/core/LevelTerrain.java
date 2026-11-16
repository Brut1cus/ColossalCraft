package colossus.core;

import colossus.physics.api.TerrainQuery;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe terrain snapshot for the physics thread: one 4096-bit "solid" mask per chunk section, rebuilt by the game
 * thread around moving bodies. Ship space never counts as terrain (the Colossus's own blocks live there).
 */
public final class LevelTerrain implements TerrainQuery {
    private final ConcurrentHashMap<Long, long[]> sections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> builtAt = new ConcurrentHashMap<>();
    private static final long[] EMPTY = new long[0];

    @Override public boolean isSolid(int x, int y, int z) {
        if (ColossusSavedData.PLOTS_REGION.inShipSpace(x, z)) return false;
        long[] bits = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (bits == null || bits.length == 0) return false;
        int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return (bits[i >> 6] & (1L << (i & 63))) != 0;
    }

    /** Make sure every section overlapping the box is cached and no older than {@code maxAgeTicks}. Game thread. */
    public void refresh(ServerLevel level, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, long now, long maxAgeTicks) {
        int sx0 = ((int) Math.floor(minX)) >> 4, sx1 = ((int) Math.floor(maxX)) >> 4;
        int sz0 = ((int) Math.floor(minZ)) >> 4, sz1 = ((int) Math.floor(maxZ)) >> 4;
        int sy0 = Math.max(level.getMinSection(), ((int) Math.floor(minY)) >> 4);
        int sy1 = Math.min(level.getMaxSection() - 1, ((int) Math.floor(maxY)) >> 4);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int sx = sx0; sx <= sx1; sx++) for (int sz = sz0; sz <= sz1; sz++) {
            if (!level.hasChunk(sx, sz)) continue;
            LevelChunk chunk = level.getChunk(sx, sz);
            for (int sy = sy0; sy <= sy1; sy++) {
                long key = SectionPos.asLong(sx, sy, sz);
                Long t = builtAt.get(key);
                if (t != null && now - t < maxAgeTicks) continue;
                LevelChunkSection sec = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                long[] bits;
                if (sec.hasOnlyAir()) {
                    bits = EMPTY;
                } else {
                    bits = new long[64];
                    for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        BlockState s = sec.getBlockState(lx, ly, lz);
                        if (s.isAir()) continue;
                        m.set((sx << 4) + lx, (sy << 4) + ly, (sz << 4) + lz);
                        if (!s.getCollisionShape(level, m).isEmpty()) {
                            int i = (ly << 8) | (lz << 4) | lx;
                            bits[i >> 6] |= 1L << (i & 63);
                        }
                    }
                }
                sections.put(key, bits);
                builtAt.put(key, now);
            }
        }
    }

    /** Forget sections not refreshed for a long time (keeps memory bounded as Colossi travel). */
    public void evictOlderThan(long now, long ageTicks) {
        builtAt.entrySet().removeIf(e -> {
            boolean old = now - e.getValue() > ageTicks;
            if (old) sections.remove(e.getKey());
            return old;
        });
    }
}
