package colossus.gyro;

import colossus.brain.balance.GyroStabilizer;
import colossus.core.ColossusConfig;
import colossus.core.ColossusRegistries;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rotor speed of one Gyro Stabilizer block, as a fraction of full speed. In the world it spins itself up or down from
 * redstone power; on board an assembled Colossus the physics-thread rotor owns the speed and writes it back here, so
 * it survives disassembly and server restarts (block entity data travels with the block).
 */
public class GyroStabilizerBlockEntity extends BlockEntity {
    /** Largest connected group searched when working out the merged footprint in the world. */
    private static final int MAX_GROUP = 256;
    private double spin;
    private int footprint = 1, recount;

    public GyroStabilizerBlockEntity(BlockPos pos, BlockState state) { super(ColossusRegistries.GYRO_BE.get(), pos, state); }

    /** Rotor speed as a fraction of full speed, 0..1. */
    public double spin() { return spin; }

    public void setSpin(double s) {
        s = Math.max(0, Math.min(1, s));
        if (Math.abs(s - spin) > 1e-4) { spin = s; setChanged(); }
    }

    public int footprint() { return footprint; }

    static void serverTick(Level level, BlockPos pos, BlockState state, GyroStabilizerBlockEntity be) {
        // on board, ColossusManager runs the rotor every physics step and writes the speed back
        if (ColossusSavedData.PLOTS_REGION.inShipSpace(pos.getX(), pos.getZ())) return;
        if (--be.recount <= 0) { be.footprint = footprintAt(level, pos); be.recount = 40; }
        GyroStabilizer.Rotor model = new GyroStabilizer.Rotor(be.footprint, ColossusConfig.gyroParams());
        double target = level.getBestNeighborSignal(pos) / 15.0;
        double tc = model.timeConstant();
        if (Double.isInfinite(tc)) return;
        be.setSpin(target + (be.spin - target) * Math.exp(-0.05 / tc));
    }

    /** Footprint of the rotor this block belongs to, by the same N×N-slab rule the assembled Colossus uses. */
    static int footprintAt(Level level, BlockPos start) {
        List<BlockPos> group = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && group.size() < MAX_GROUP) {
            BlockPos p = queue.poll();
            group.add(p);
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (seen.add(n) && level.isLoaded(n) && level.getBlockState(n).is(ColossusRegistries.GYRO_STABILIZER.get())) queue.add(n);
            }
        }
        List<int[]> cells = new ArrayList<>(group.size());
        for (BlockPos p : group) cells.add(new int[]{p.getX(), p.getY(), p.getZ()});
        for (GyroStabilizer.Unit u : GyroStabilizer.units(cells)) for (int i : u.cells()) if (i == 0) return u.footprint();
        return 1;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Spin", spin);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        spin = Math.max(0, Math.min(1, tag.getDouble("Spin")));
    }
}
