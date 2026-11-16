package colossus.client.render;

import colossus.client.ClientColossi;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * A Segment's blocks as a tiny level for the block model renderer, in the core frame. Everything outside the Segment is
 * air, so faces against other Segments are kept (they move apart). Light is the world light sampled at the Segment's
 * centre of mass (plus each block's own emission, added by the renderer); tints come from the biome there.
 */
final class SegmentBlockGetter implements BlockAndTintGetter {
    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    private final Long2IntOpenHashMap states;
    private final ClientLevel level;
    private final BlockPos worldSample;
    private final int sky, block;

    SegmentBlockGetter(ClientColossi.SegmentData data, ClientLevel level, BlockPos worldSample, int sky, int block) {
        this.states = new Long2IntOpenHashMap(data.size());
        this.states.defaultReturnValue(-1);
        for (int i = 0; i < data.size(); i++) states.put(data.cell(i), data.state(i));
        this.level = level;
        this.worldSample = worldSample;
        this.sky = sky;
        this.block = block;
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        int id = states.get(pos.asLong());
        return id < 0 ? AIR : Block.stateById(id);
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

    // block entity data is not synced yet; models fall back to their defaults
    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }

    @Override public float getShade(Direction dir, boolean shade) { return level.getShade(dir, shade); }

    @Override public LevelLightEngine getLightEngine() { return level.getLightEngine(); }

    @Override public int getBrightness(LightLayer layer, BlockPos pos) { return layer == LightLayer.SKY ? sky : block; }

    @Override public int getRawBrightness(BlockPos pos, int skyDarken) { return Math.max(sky - skyDarken, block); }

    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return level.getBlockTint(worldSample, resolver); }

    // cells are core-relative and may sit far from y = 0; give the renderer a range that never clips them
    @Override public int getHeight() { return 1 << 20; }

    @Override public int getMinBuildHeight() { return -(1 << 19); }
}
