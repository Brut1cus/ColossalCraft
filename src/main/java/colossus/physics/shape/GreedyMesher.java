package colossus.physics.shape;

import colossus.physics.api.Box;
import colossus.physics.math.Vec3;

import java.util.*;

/**
 * Merges a Segment's full-block cells into as few axis-aligned boxes as practical (grow along X, then Z, then Y).
 * Collision compounds stay small even for thousands of blocks. Partial blocks (slabs, stairs) keep their own shapes
 * and are added by the caller.
 */
public final class GreedyMesher {
    private GreedyMesher() {}

    /**
     * @param cells  integer block positions (min corners), within ±1,000,000 (pass positions relative to the core)
     * @param origin subtracted from every box (usually the Segment's centre of mass)
     */
    public static List<Box> mesh(Collection<int[]> cells, Vec3 origin) {
        Set<Long> todo = new HashSet<>();
        for (int[] c : cells) todo.add(key(c[0], c[1], c[2]));
        List<int[]> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.<int[]>comparingInt(c -> c[1]).thenComparingInt(c -> c[2]).thenComparingInt(c -> c[0]));
        List<Box> out = new ArrayList<>();
        for (int[] c : sorted) {
            if (!todo.contains(key(c[0], c[1], c[2]))) continue;
            int x0 = c[0], y0 = c[1], z0 = c[2];
            int x1 = x0;
            while (todo.contains(key(x1 + 1, y0, z0))) x1++;
            int z1 = z0;
            while (rowFull(todo, x0, x1, y0, z1 + 1)) z1++;
            int y1 = y0;
            while (layerFull(todo, x0, x1, y1 + 1, z0, z1)) y1++;
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) todo.remove(key(x, y, z));
            out.add(new Box(new Vec3(x0, y0, z0).sub(origin), new Vec3(x1 + 1, y1 + 1, z1 + 1).sub(origin)));
        }
        return out;
    }

    private static boolean rowFull(Set<Long> s, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) if (!s.contains(key(x, y, z))) return false;
        return true;
    }

    private static boolean layerFull(Set<Long> s, int x0, int x1, int y, int z0, int z1) {
        for (int z = z0; z <= z1; z++) if (!rowFull(s, x0, x1, y, z)) return false;
        return true;
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF); }
}
