package com.colossalcraft.block;

import com.colossalcraft.tier.MaterialTier;
import com.colossalcraft.tier.ThrusterTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** Small block classes that only carry stats. */
public final class SimpleParts {
    private SimpleParts() {}

    /** Joint shapes. Strength = share of a full joint's load it can carry. */
    public enum JointShape {
        FULL("joint", "Bearing Joint", 1.0), SLAB("joint_slab", "Slab Joint", 0.5), PANEL("joint_panel", "Panel Joint", 0.5), PIN("joint_pin", "Pin Joint", 0.25);
        public final String suffix, title; public final double strength;
        JointShape(String s, String t, double k) { suffix = s; title = t; strength = k; }
    }

    /** Hollow bearing joint. AXIS = hinge axis. Joint blocks split the Colossus into Segments. */
    public static class JointBlock extends RotatedPillarBlock {
        public final MaterialTier tier; public final JointShape shape;
        private final java.util.EnumMap<Direction.Axis, VoxelShape> shapes = new java.util.EnumMap<>(Direction.Axis.class);
        public JointBlock(MaterialTier t, JointShape shape, Properties p) {
            super(p); tier = t; this.shape = shape;
            for (Direction.Axis a : Direction.Axis.values()) shapes.put(a, outline(shape, a));
        }
        public static VoxelShape outline(JointShape shape, Direction.Axis a) {
            return switch (shape) {
                case FULL -> Shapes.block();
                case SLAB -> Block.box(0, 0, 0, 16, 8, 16);
                case PANEL -> a == Direction.Axis.X ? Block.box(4, 0, 0, 12, 16, 16) : a == Direction.Axis.Y ? Block.box(0, 4, 0, 16, 12, 16) : Block.box(0, 0, 4, 16, 16, 12);
                case PIN -> a == Direction.Axis.X ? Block.box(0, 5, 5, 16, 11, 11) : a == Direction.Axis.Y ? Block.box(5, 0, 5, 11, 16, 11) : Block.box(5, 5, 0, 11, 11, 16);
            };
        }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return shapes.get(s.getValue(AXIS)); }
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

    /** Pilot Throne: a chair. FACING = the way the pilot looks (forward of the Colossus). Right-click to sit. */
    public static class PilotSeatBlock extends HorizontalDirectionalBlock {
        private static final java.util.EnumMap<Direction, VoxelShape> SHAPES = new java.util.EnumMap<>(Direction.class);
        static {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                VoxelShape seat = Block.box(1, 0, 1, 15, 8, 15);
                VoxelShape back = rotate(d, 1, 8, 13, 15, 22, 15);
                VoxelShape armL = rotate(d, 0, 8, 2, 2, 12, 13), armR = rotate(d, 14, 8, 2, 16, 12, 13);
                SHAPES.put(d, Shapes.or(seat, back, armL, armR));
            }
        }
        /** Box given for a north-facing chair, turned to face d. */
        private static VoxelShape rotate(Direction d, double x0, double y0, double z0, double x1, double y1, double z1) {
            return switch (d) {
                case SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
                case EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
                case WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
                default -> Block.box(x0, y0, z0, x1, y1, z1);
            };
        }
        public PilotSeatBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
        @Override protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() { return null; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection()); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPES.get(s.getValue(FACING)); }
        @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState s, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
            if (!level.isClientSide && !player.isPassenger()) com.colossalcraft.block.SeatEntity.sit(level, pos, player);
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /** Gantry Scaffold: never becomes part of a Colossus. Build on it, assemble, then knock it down. */
    public static class ScaffoldBlock extends Block {
        public ScaffoldBlock(Properties p) { super(p); }
        @Override protected VoxelShape getVisualShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return Shapes.empty(); }
        @Override protected float getShadeBrightness(BlockState s, BlockGetter l, BlockPos p) { return 1f; }
        @Override protected boolean propagatesSkylightDown(BlockState s, BlockGetter l, BlockPos p) { return true; }
    }

    /** Thin sail. */
    public static class ThinSailBlock extends SailBlock {
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 3, 16);
        public ThinSailBlock(Properties p) { super(p); }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
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
