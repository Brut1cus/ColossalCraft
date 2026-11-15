package com.colossalcraft.block;

import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.network.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** The Hull Core. Right click opens the computer screen where the Colossus is set up and assembled. */
public class HullCoreBlock extends BaseEntityBlock {
    public HullCoreBlock(Properties p) { super(p); }
    @Override protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() { return null; }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new HullCoreBlockEntity(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState s, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) Net.sendScan(sp, pos, StructureScan.scan(level, pos));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
