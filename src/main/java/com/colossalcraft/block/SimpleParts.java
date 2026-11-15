package com.colossalcraft.block;

import com.colossalcraft.tier.MaterialTier;
import com.colossalcraft.tier.ThrusterTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
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
    /** Servo motor. AXIS = its shaft. Motors of the same material and axis side by side work as one bigger motor. */
    public static class MotorBlock extends RotatedPillarBlock {
        public final MaterialTier tier;
        public MotorBlock(MaterialTier t, Properties p) { super(p); tier = t; }
    }

    /** Gearbox: placed between Servo Motors and a joint. Multiplies torque by ratio (times efficiency) and divides speed by ratio. Chains multiply. */
    public static class GearboxBlock extends RotatedPillarBlock {
        public final double ratio, efficiency; public final String label;
        /** Where the input gear (model tintindex 9) and output gear (tintindex 10) sit, in pixels across the axis (u, v). Must match the model. */
        public final double inU, inV, outU, outV, outFactor; public final boolean inAcross;
        public GearboxBlock(double ratio, double efficiency, String label, Properties p) {
            super(p); this.ratio = ratio; this.efficiency = efficiency; this.label = label;
            if (ratio == 0.5) { inU = 5.5; inV = 8; outU = 11.5; outV = 8; outFactor = -2; inAcross = false; }        // overdrive: big drives small
            else if (ratio == 2) { inU = 4.5; inV = 8; outU = 10.5; outV = 8; outFactor = -0.5; inAcross = false; }   // spur
            else if (ratio == 5) { inU = 8; inV = 8; outU = 8; outV = 8; outFactor = 0.2; inAcross = false; }          // planetary: carrier orbits with the sun
            else { inU = 8; inV = 3.5; outU = 8; outV = 10.5; outFactor = -1 / ratio; inAcross = true; }              // worm: the worm turns across the block
        }
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

    /** Slab joint: bottom or top half. Axis comes from the face you click. */
    public static class SlabJointBlock extends JointBlock {
        public static final net.minecraft.world.level.block.state.properties.BooleanProperty TOP = net.minecraft.world.level.block.state.properties.BooleanProperty.create("top");
        private static final VoxelShape LOW = Block.box(0, 0, 0, 16, 8, 16), HIGH = Block.box(0, 8, 0, 16, 16, 16);
        public SlabJointBlock(MaterialTier t, Properties p) { super(t, JointShape.SLAB, p); registerDefaultState(defaultBlockState().setValue(TOP, false)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { super.createBlockStateDefinition(b); b.add(TOP); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
            Direction f = c.getClickedFace();
            boolean top = f == Direction.DOWN || (f != Direction.UP && c.getClickLocation().y - c.getClickedPos().getY() > 0.5);
            return defaultBlockState().setValue(AXIS, f.getAxis()).setValue(TOP, top);
        }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return s.getValue(TOP) ? HIGH : LOW; }
    }

    /** Panel joint: a vertical half slab against the N/E/S/W side of its block. Axis = through the panel; sneak while placing for a vertical (door-hinge) axis. */
    public static class PanelJointBlock extends JointBlock {
        public static final DirectionProperty SIDE = BlockStateProperties.HORIZONTAL_FACING;
        public PanelJointBlock(MaterialTier t, Properties p) { super(t, JointShape.PANEL, p); registerDefaultState(defaultBlockState().setValue(SIDE, Direction.NORTH)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { super.createBlockStateDefinition(b); b.add(SIDE); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
            Direction f = c.getClickedFace();
            Direction side = f.getAxis().isHorizontal() ? f.getOpposite() : c.getHorizontalDirection();
            return defaultBlockState().setValue(SIDE, side).setValue(AXIS, c.isSecondaryUseActive() ? Direction.Axis.Y : side.getAxis());
        }
        public static VoxelShape half(Direction d) {
            return switch (d) {
                case SOUTH -> Block.box(0, 0, 8, 16, 16, 16); case WEST -> Block.box(0, 0, 0, 8, 16, 16);
                case EAST -> Block.box(8, 0, 0, 16, 16, 16); default -> Block.box(0, 0, 0, 16, 16, 8);
            };
        }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return half(s.getValue(SIDE)); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return super.rotate(s, r).setValue(SIDE, r.rotate(s.getValue(SIDE))); }
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
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite()) /* faces the player who places it */; }
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

    /** Propeller / rotor blade: put on the turning side of a Spin joint. Thrust grows with blade area and speed squared. */
    public static class RotorBladeBlock extends SailBlock {
        private static final java.util.EnumMap<Direction.Axis, VoxelShape> SH = new java.util.EnumMap<>(Direction.Axis.class);
        static { SH.put(Direction.Axis.X, Block.box(0, 6, 3, 16, 10, 13)); SH.put(Direction.Axis.Y, Block.box(3, 0, 6, 13, 16, 10)); SH.put(Direction.Axis.Z, Block.box(3, 6, 0, 13, 10, 16)); }
        public static final net.minecraft.world.level.block.state.properties.EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
        public RotorBladeBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(AXIS); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(AXIS, c.getClickedFace().getAxis()); }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SH.get(s.getValue(AXIS)); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return RotatedPillarBlock.rotatePillar(s, r); }
    }

    /** Gantry Hoist: hangs from scaffolding. Right-click to grab the Colossus below and lift it clear of the ground, again to let go. */
    public static class GantryHoistBlock extends Block {
        public static final net.minecraft.world.level.block.state.properties.BooleanProperty ENGAGED = net.minecraft.world.level.block.state.properties.BooleanProperty.create("engaged");
        private static final VoxelShape SHAPE = Shapes.or(Block.box(0, 10, 0, 16, 16, 16), Block.box(5, 2, 5, 11, 10, 11));
        public GantryHoistBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(ENGAGED, false)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(ENGAGED); }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
        @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState s, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
            if (!level.isClientSide) {
                boolean on = !s.getValue(ENGAGED);
                level.setBlock(pos, s.setValue(ENGAGED, on), 3);
                if (on) level.scheduleTick(pos, this, 1);
                com.colossalcraft.colossus.ColossusEntity c = on ? com.colossalcraft.colossus.ColossusEntity.below(level, pos) : null;
                if (!on) com.colossalcraft.colossus.ColossusEntity.releaseFrom(level, pos);
                else if (c != null) c.holdBy(pos);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(on ? (c != null ? "Hoist engaged: lifting the Colossus off the ground." : "Hoist engaged: waiting for a Colossus below (up to 32 blocks).") : "Hoist released."), true);
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
        @Override protected void tick(BlockState s, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource r) {
            if (!s.getValue(ENGAGED)) return;
            com.colossalcraft.colossus.ColossusEntity c = com.colossalcraft.colossus.ColossusEntity.below(level, pos);
            if (c != null && c.heldBy() == null) c.holdBy(pos);
            level.scheduleTick(pos, this, 10);
        }
        @Override protected void onRemove(BlockState s, net.minecraft.world.level.Level level, BlockPos pos, BlockState ns, boolean moving) {
            if (!ns.is(this)) com.colossalcraft.colossus.ColossusEntity.releaseFrom(level, pos);
            super.onRemove(s, level, pos, ns, moving);
        }
    }
}
