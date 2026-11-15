package com.colosalcraft.block;

import com.colosalcraft.tier.MaterialTier;
import com.colosalcraft.tier.ThrusterTier;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** Small block classes that only carry stats. */
public final class SimpleParts {
    private SimpleParts() {}

    /** Bearing joint. AXIS = hinge axis. Joint blocks split the Colossus into Segments. */
    public static class JointBlock extends RotatedPillarBlock {
        public final MaterialTier tier;
        public JointBlock(MaterialTier t, Properties p) { super(p); tier = t; }
    }

    /** Servo motor. Touching a joint: drives that joint. Not touching a joint: drives the Colossus over ground. */
    public static class MotorBlock extends Block {
        public final MaterialTier tier;
        public MotorBlock(MaterialTier t, Properties p) { super(p); tier = t; }
    }

    /** Thruster. FACING = nozzle direction; push goes the other way. */
    public static class ThrusterBlock extends DirectionalBlock {
        public final ThrusterTier tier;
        public ThrusterBlock(ThrusterTier t, Properties p) { super(p); tier = t; registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN)); }
        @Override protected com.mojang.serialization.MapCodec<? extends DirectionalBlock> codec() { return null; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getNearestLookingDirection().getOpposite()); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    }

    /** Pilot Throne. FACING = forward of the Colossus. */
    public static class PilotSeatBlock extends HorizontalDirectionalBlock {
        public PilotSeatBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
        @Override protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() { return null; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection()); }
    }

    /** One-block gyro. */
    public static class GyroBlock extends Block {
        public final double capacity;
        public GyroBlock(double cap, Properties p) { super(p); capacity = cap; }
    }

    /** Lift surface. */
    public static class SailBlock extends Block {
        public SailBlock(Properties p) { super(p); }
    }
}
