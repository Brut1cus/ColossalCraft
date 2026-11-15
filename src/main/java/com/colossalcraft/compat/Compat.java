package com.colossalcraft.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/** Soft hooks for other mods. Each mod's code only loads when that mod is installed. */
public final class Compat {
    public static boolean CREATE;

    public static void init(IEventBus modBus) {
        CREATE = ModList.get().isLoaded("create");
        if (CREATE) com.colossalcraft.compat.create.CreateCompat.init(modBus);
    }

    /** Called on a saved block-entity tag when its block is lifted into a Colossus. */
    public static void onLift(BlockEntity be, CompoundTag tag) {
        if (CREATE) com.colossalcraft.compat.create.CreateCompat.onLift(be, tag);
    }

    /** Called after a block entity is put back into the world on landing. */
    public static void onLand(Level level, BlockPos pos, BlockEntity be) {
        if (CREATE) com.colossalcraft.compat.create.CreateCompat.onLand(level, pos, be);
    }
}
