package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import com.colossalcraft.colossus.StructureScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/** Battery that charges from any mod's Forge Energy (FE). 1 FE = 1 CJ. */
public class FluxAccumulatorBlock extends BaseEntityBlock implements ColossalBattery {
    public static final long CAPACITY = 4_000_000; public static final int OUTPUT = 2_000;

    public FluxAccumulatorBlock(Properties p) { super(p); }
    @Override protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() { return null; }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BE(pos, state); }
    @Override public long capacity() { return CAPACITY; }
    @Override public int output() { return OUTPUT; }
    @Override public String title() { return "Flux Accumulator"; }

    @Override protected InteractionResult useWithoutItem(BlockState s, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BE be)
            player.displayClientMessage(Component.literal(String.format("Flux Accumulator: %s / %s CJ (charge with any FE source)", StructureScan.kilo(be.energy), StructureScan.kilo(CAPACITY))), true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static class BE extends BlockEntity {
        public long energy;
        public BE(BlockPos pos, BlockState state) { super(ModRegistry.FLUX_BE.get(), pos, state); }
        @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) { super.saveAdditional(t, r); t.putLong("Energy", energy); }
        @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) { super.loadAdditional(t, r); energy = t.getLong("Energy"); }

        public final IEnergyStorage fe = new IEnergyStorage() {
            public int receiveEnergy(int max, boolean sim) {
                int got = (int) Math.min(max, Math.min(CAPACITY - energy, 100_000));
                if (!sim && got > 0) { energy += got; setChanged(); }
                return Math.max(0, got);
            }
            public int extractEnergy(int max, boolean sim) { return 0; }
            public int getEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, energy); }
            public int getMaxEnergyStored() { return (int) CAPACITY; }
            public boolean canExtract() { return false; }
            public boolean canReceive() { return true; }
        };
    }
}
