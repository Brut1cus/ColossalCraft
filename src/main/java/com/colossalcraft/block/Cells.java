package com.colossalcraft.block;

import com.colossalcraft.tier.Parts;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A grid block's place in its assembly, packed into one number so big assemblies (up to 6x6) don't need millions of
 * block states. Flat squares (bearings, motors): CELL numbers every (side n, gu, gv). Boxes (ball joints, gyros): BOX numbers
 * every (side n, layers h <= n, gu, gv, gh). The model generator (tools/gen_data.py) numbers them in the same order.
 */
public final class Cells {
    private Cells() {}
    static final int N = Parts.MAX;
    private static final int[][] FLAT, BOXES; // index -> {n, gu, gv} / {n, h, gu, gv, gh}
    private static final int[] FLAT_BASE = new int[N + 2];
    private static final java.util.Map<Long, Integer> BOX_INDEX = new java.util.HashMap<>();
    static {
        java.util.List<int[]> f = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
        for (int n = 1; n <= N; n++) { FLAT_BASE[n] = f.size(); for (int u = 0; u < n; u++) for (int v = 0; v < n; v++) f.add(new int[]{n, u, v}); }
        for (int n = 1; n <= N; n++) for (int h = 1; h <= n; h++) for (int gh = 0; gh < h; gh++) for (int u = 0; u < n; u++) for (int v = 0; v < n; v++) {
            BOX_INDEX.put(key(n, h, u, v, gh), b.size()); b.add(new int[]{n, h, u, v, gh});
        }
        FLAT = f.toArray(new int[0][]); BOXES = b.toArray(new int[0][]);
    }
    private static long key(int n, int h, int u, int v, int gh) { return (((n * 8L + h) * 8 + u) * 8 + v) * 8 + gh; }
    public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, FLAT.length - 1);
    public static final IntegerProperty BOX = IntegerProperty.create("box", 0, BOXES.length - 1);

    private static int[] of(BlockState s) {
        if (s.hasProperty(BOX)) return BOXES[s.getValue(BOX)];
        if (s.hasProperty(CELL)) { int[] c = FLAT[s.getValue(CELL)]; return new int[]{c[0], 1, c[1], c[2], 0}; }
        return new int[]{1, 1, 0, 0, 0};
    }
    public static int size(BlockState s) { return of(s)[0]; }
    public static int height(BlockState s) { return of(s)[1]; }
    public static int gu(BlockState s) { return of(s)[2]; }
    public static int gv(BlockState s) { return of(s)[3]; }
    public static int gh(BlockState s) { return of(s)[4]; }
    /** The state with its place in an assembly set (h and gh only matter for boxes). */
    public static BlockState with(BlockState s, int n, int u, int v, int h, int gh) {
        if (s.hasProperty(BOX)) { Integer i = BOX_INDEX.get(key(n, Math.min(h, n), u, v, Math.min(gh, h - 1))); return s.setValue(BOX, i == null ? 0 : i); }
        if (s.hasProperty(CELL)) return s.setValue(CELL, FLAT_BASE[n] + u * n + v);
        return s;
    }
}
