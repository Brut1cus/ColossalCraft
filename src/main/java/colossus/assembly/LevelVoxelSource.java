package colossus.assembly;

import colossus.assembly.graph.VoxelSource;
import colossus.core.ColossusRegistries;
import colossus.core.ColossusTags;
import colossus.core.data.BlockPhysicsTable;
import colossus.core.data.ColossusData;
import colossus.core.data.SoundTypeNames;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collection;
import java.util.List;

/** {@link VoxelSource} over a live Level, for the Hull Core scan. Game thread only. */
public final class LevelVoxelSource implements VoxelSource {
    private final Level level;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    public LevelVoxelSource(Level level) { this.level = level; }

    private BlockState state(int x, int y, int z) { return level.getBlockState(m.set(x, y, z)); }

    @Override public Kind kind(int x, int y, int z) {
        m.set(x, y, z);
        if (level.isOutsideBuildHeight(m) || !level.isLoaded(m)) return Kind.EXCLUDED;
        if (ColossusSavedData.PLOTS_REGION.inShipSpace(x, z)) return Kind.EXCLUDED;
        BlockState s = level.getBlockState(m);
        if (s.isAir() || s.getBlock() instanceof LiquidBlock || s.canBeReplaced()) return Kind.EMPTY;
        if (s.is(ColossusTags.NON_ASSEMBLABLE)) return Kind.EXCLUDED;
        if (s.getBlock() instanceof HullCoreBlock) return Kind.CORE;
        if (s.getBlock() instanceof JointBlock) return Kind.JOINT;
        return Kind.BLOCK;
    }

    @Override public JointInfo joint(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (!(s.getBlock() instanceof JointBlock jb)) return new JointInfo("fixed_weld", 1);
        return new JointInfo(jb.kind.id, s.getValue(JointBlock.AXIS).ordinal());
    }

    @Override public double mass(int x, int y, int z) {
        BlockState s = state(x, y, z);
        BlockPos pos = m.immutable();
        return ColossusData.BLOCK_PHYSICS.mass(key(level, pos, s));
    }

    @Override public boolean isLattice(int x, int y, int z) { return state(x, y, z).is(ColossusRegistries.CORE_LATTICE.get()); }

    @Override public boolean isSupportingGround(int x, int y, int z) {
        m.set(x, y, z);
        if (!level.isLoaded(m)) return false;
        BlockState s = level.getBlockState(m);
        return !s.getCollisionShape(level, m).isEmpty();
    }

    /** Block-physics lookup key for any block state. */
    public static BlockPhysicsTable.BlockKey key(Level level, BlockPos pos, BlockState s) {
        return new BlockPhysicsTable.BlockKey() {
            @Override public String id() { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(); }
            @Override public Collection<String> tags() { return s.getTags().map(t -> t.location().toString()).toList(); }
            @Override public String soundType() { return SoundTypeNames.name(s.getSoundType()); }
            @Override public double shapeVolume() { return volume(level, pos, s); }
        };
    }

    /** Collision-shape volume (falls back to the outline shape for blocks you can walk through, e.g. torches). */
    static double volume(Level level, BlockPos pos, BlockState s) {
        VoxelShape shape = s.getCollisionShape(level, pos);
        if (shape.isEmpty()) shape = s.getShape(level, pos);
        if (shape.isEmpty()) return 0.05;
        double v = 0;
        List<AABB> boxes = shape.toAabbs();
        for (AABB b : boxes) v += b.getXsize() * b.getYsize() * b.getZsize();
        return Math.max(0.05, Math.min(1, v));
    }
}
