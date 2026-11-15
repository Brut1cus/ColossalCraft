package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import com.colossalcraft.tier.EngineTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Single-block engine (tiers I-III). Right click with fuel to fill. */
public class PowerBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public final EngineTier tier;

    public PowerBlock(EngineTier tier, Properties p) {
        super(p); this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() { return null; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite()); }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PowerBlockEntity(pos, state); }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }

    static ItemInteractionResult fuel(ItemStack stack, Level level, BlockPos pos, Player player) {
        if (level.getBlockEntity(pos) instanceof PowerBlockEntity be && be.addFuel(stack, true)) {
            if (!level.isClientSide) {
                int n = player.isShiftKeyDown() ? stack.getCount() : 1;
                for (int i = 0; i < n && be.addFuel(stack, false); i++) if (!player.getAbilities().instabuild) stack.shrink(1);
                be.describe(player);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState s, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return fuel(stack, level, pos, player);
    }
    @Override protected InteractionResult useWithoutItem(BlockState s, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PowerBlockEntity be) be.describe(player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
