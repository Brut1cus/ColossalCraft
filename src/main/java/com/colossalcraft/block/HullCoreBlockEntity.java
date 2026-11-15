package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Stores the setup chosen in the Hull Core screen (joint modes, hover assist). */
public class HullCoreBlockEntity extends BlockEntity {
    public CompoundTag joints = new CompoundTag();
    public boolean hover = true;

    public HullCoreBlockEntity(BlockPos pos, BlockState state) { super(ModRegistry.HULL_CORE_BE.get(), pos, state); }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg); tag.put("Joints", joints); tag.putBoolean("Hover", hover);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg); joints = tag.getCompound("Joints"); hover = !tag.contains("Hover") || tag.getBoolean("Hover");
    }
}
