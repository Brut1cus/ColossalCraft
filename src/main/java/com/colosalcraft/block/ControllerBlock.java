package com.colosalcraft.block;

import com.colosalcraft.ModRegistry;
import com.colosalcraft.multiblock.MultiblockDef;
import com.colosalcraft.tier.EngineTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Front block of a multiblock (big engines and gyro structures). */
public class ControllerBlock extends PowerBlock {
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");
    public final MultiblockDef def;
    @Nullable public final EngineTier engine;
    public final double gyroCapacity; // kg it can stabilise (0 for engines)

    public ControllerBlock(MultiblockDef def, @Nullable EngineTier engine, double gyroCapacity, Properties p) {
        super(engine, p.lightLevel(s -> s.getValue(FORMED) ? 12 : 0));
        this.def = def; this.engine = engine; this.gyroCapacity = gyroCapacity;
        registerDefaultState(defaultBlockState().setValue(FORMED, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, FORMED); }

    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState s, BlockEntityType<T> type) {
        return level.isClientSide || type != ModRegistry.POWER_BE.get() ? null : (l, p, st, be) -> PowerBlockEntity.serverTick(l, p, st, (PowerBlockEntity) be);
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState s, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return engine == null ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : fuel(stack, level, pos, player);
    }
}
