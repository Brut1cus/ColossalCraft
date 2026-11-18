package com.colossalcraft.multiblock;

import com.colossalcraft.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/**
 * A cube of size N (odd). The controller sits in the middle of one face, its front facing out.
 * The shell must be casing, the inside must be fill, and the very centre may need a special block.
 */
public enum MultiblockDef {
    ION_DRIVE(3, () -> ModRegistry.VOID_CASING.get(), () -> ModRegistry.ENDER_COIL.get(), null),
    FISSION(5, () -> ModRegistry.LEADLINED_CASING.get(), () -> ModRegistry.FUEL_ROD_ASSEMBLY.get(), null),
    FUSION(5, () -> ModRegistry.CONTAINMENT_CASING.get(), () -> ModRegistry.PLASMA_COIL.get(), null),
    ZERO_POINT(5, () -> ModRegistry.SINGULARITY_CASING.get(), () -> ModRegistry.VACUUM_LATTICE.get(), () -> ModRegistry.NULL_SINGULARITY.get());

    public final int size;
    private final Supplier<Block> casing, fill, center;

    MultiblockDef(int size, Supplier<Block> casing, Supplier<Block> fill, Supplier<Block> center) {
        this.size = size; this.casing = casing; this.fill = fill; this.center = center;
    }

    /** @return null when formed, otherwise a short message naming the first wrong block. */
    public String validate(Level level, BlockPos controller, Direction front) {
        int h = size / 2;
        BlockPos c = controller.relative(front.getOpposite(), h);
        for (int dx = -h; dx <= h; dx++) for (int dy = -h; dy <= h; dy++) for (int dz = -h; dz <= h; dz++) {
            BlockPos p = c.offset(dx, dy, dz);
            if (p.equals(controller)) continue;
            boolean inside = Math.abs(dx) < h && Math.abs(dy) < h && Math.abs(dz) < h;
            Block want = inside ? (center != null && dx == 0 && dy == 0 && dz == 0 ? center.get() : fill.get()) : casing.get();
            BlockState s = level.getBlockState(p);
            if (!s.is(want)) return "Need " + want.getName().getString() + " at " + p.getX() + " " + p.getY() + " " + p.getZ();
        }
        return null;
    }

    public int blockCount() { return size * size * size; }
}
