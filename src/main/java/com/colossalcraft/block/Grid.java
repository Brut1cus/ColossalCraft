package com.colossalcraft.block;

import com.colossalcraft.block.SimpleParts.GridBlock;
import com.colossalcraft.tier.Parts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Grid assemblies, like chests that join: bearings, motors, ball halves and sockets placed side by side in their plane
 * join into the biggest squares that fit (2x2, 3x3, up to 4x4). Each block remembers the square's size and its place in it,
 * which picks its model (one big shaft or ball across the whole square). Blocks placed while sneaking stay 1x1.
 */
public final class Grid {
    private Grid() {}

    /** Where block states are read and written: the world, or a built Colossus' block map. */
    public interface Access { BlockState get(BlockPos p); void set(BlockPos p, BlockState s); }

    public static void placed(Level l, BlockPos p, boolean isNew) {
        if (l.isClientSide || !isNew) return;
        regrid(world(l), p);
    }
    public static void removed(Level l, BlockPos p, BlockState was, boolean gone) {
        if (l.isClientSide || !gone || !(was.getBlock() instanceof GridBlock gb)) return;
        Access a = world(l);
        for (Direction.Axis ax : gb.plane(was)) for (int s = -1; s <= 1; s += 2) {
            BlockPos n = p.relative(ax, s);
            BlockState ns = a.get(n);
            if (ns.getBlock() == was.getBlock()) regrid(a, n);
        }
    }
    static Access world(Level l) {
        return new Access() {
            public BlockState get(BlockPos p) { return l.getBlockState(p); }
            public void set(BlockPos p, BlockState s) { l.setBlock(p, s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); }
        };
    }
    /** For a built Colossus' blocks. */
    public static Access map(Map<BlockPos, BlockState> blocks) {
        return new Access() {
            public BlockState get(BlockPos p) { BlockState s = blocks.get(p); return s == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : s; }
            public void set(BlockPos p, BlockState s) { blocks.put(p, s); }
        };
    }

    /** Re-tile the connected group (in its plane) that holds p into squares, biggest first, in reading order. */
    public static void regrid(Access a, BlockPos start) {
        BlockState s0 = a.get(start);
        if (!(s0.getBlock() instanceof GridBlock gb)) return;
        if (s0.getValue(SimpleParts.LONE)) { set(a, start, s0, 1, 0, 0); return; }
        Direction.Axis[] pl = gb.plane(s0);
        Set<BlockPos> comp = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); comp.add(start);
        while (!q.isEmpty() && comp.size() < 256) {
            BlockPos p = q.poll();
            for (Direction.Axis ax : pl) for (int s = -1; s <= 1; s += 2) {
                BlockPos n = p.relative(ax, s);
                if (!comp.contains(n) && gb.joins(s0, a.get(n))) { comp.add(n); q.add(n); }
            }
        }
        List<BlockPos> order = new ArrayList<>(comp);
        order.sort(Comparator.comparingInt((BlockPos p) -> coord(p, pl[1])).thenComparingInt(p -> coord(p, pl[0])));
        Set<BlockPos> done = new HashSet<>();
        for (BlockPos c : order) {
            if (done.contains(c)) continue;
            int k = 1;
            grow:
            while (k < Parts.MAX) {
                for (int du = 0; du <= k; du++) for (int dv = 0; dv <= k; dv++) {
                    if (du < k && dv < k) continue; // only the new row and column
                    BlockPos x = c.relative(pl[0], du).relative(pl[1], dv);
                    if (!comp.contains(x) || done.contains(x)) break grow;
                }
                k++;
            }
            for (int du = 0; du < k; du++) for (int dv = 0; dv < k; dv++) {
                BlockPos x = c.relative(pl[0], du).relative(pl[1], dv);
                done.add(x);
                set(a, x, a.get(x), k, du, dv);
            }
        }
    }
    private static int coord(BlockPos p, Direction.Axis a) { return a.choose(p.getX(), p.getY(), p.getZ()); }
    private static void set(Access a, BlockPos p, BlockState s, int k, int u, int v) {
        BlockState n = s.setValue(SimpleParts.SIZE, k).setValue(SimpleParts.GU, u).setValue(SimpleParts.GV, v);
        if (n != s) a.set(p, n);
    }

    /** The first block (gu = gv = 0) of the square p belongs to. */
    public static BlockPos origin(BlockPos p, BlockState s) {
        if (!(s.getBlock() instanceof GridBlock gb)) return p;
        Direction.Axis[] pl = gb.plane(s);
        return p.relative(pl[0], -s.getValue(SimpleParts.GU)).relative(pl[1], -s.getValue(SimpleParts.GV));
    }
    public static int size(BlockState s) { return s.hasProperty(SimpleParts.SIZE) ? s.getValue(SimpleParts.SIZE) : 1; }
}
