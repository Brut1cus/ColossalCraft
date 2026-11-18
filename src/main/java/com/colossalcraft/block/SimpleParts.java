package com.colossalcraft.block;

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

    /** Joint kinds. Strength = share of a 1x1 bearing's load it carries (assemblies multiply by n^n). */
    public enum JointShape {
        FULL("bearing", "Bearing", 1.0), SLAB("slab_joint", "Slab Joint", 0.5), PANEL("panel_joint", "Panel Joint", 0.5), PIN("pin_joint", "Pin Joint", 0.25),
        /** Ball joint: keeps the part below it level (turns any way, within its limit). */
        BALL("ball_joint", "Ball Joint", 1.0),
        /** Powered ball joint: a ball joint with its own motor; tilts the foot to the ground and helps balance. */
        POWERED_BALL("powered_ball_joint", "Powered Ball Joint", 1.0);
        public final String suffix, title; public final double strength;
        JointShape(String s, String t, double k) { suffix = s; title = t; strength = k; }
        public boolean ball() { return this == BALL || this == POWERED_BALL; }
    }

    /** Grid assembly state: the place in the assembly is {@link Cells}; LONE = "keep separate" (placed while sneaking). */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty LONE = net.minecraft.world.level.block.state.properties.BooleanProperty.create("lone");

    /** Blocks that join their neighbours into square grids (bearings, motors) or boxes (ball joints). */
    public interface GridBlock {
        /** The two axes the grid spreads along. */
        Direction.Axis[] plane(BlockState s);
        /** Do these two join into one assembly? */
        default boolean joins(BlockState a, BlockState b) {
            if (a.getBlock() != b.getBlock() || a.getValue(LONE) || b.getValue(LONE)) return false;
            if (a.hasProperty(BlockStateProperties.AXIS) && a.getValue(BlockStateProperties.AXIS) != b.getValue(BlockStateProperties.AXIS)) return false;
            return true;
        }
        /** Also stacks upward into n x h x n boxes (ball joints). */
        default boolean stacks() { return false; }
    }
    static Direction.Axis[] across(Direction.Axis a) {
        return switch (a) { case X -> new Direction.Axis[]{Direction.Axis.Z, Direction.Axis.Y}; case Y -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}; default -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Y}; };
    }

    /** A joint block. AXIS = hinge axis. Joint blocks split the Colossus into Segments. */
    public static class JointBlock extends RotatedPillarBlock {
        public final JointShape shape;
        private final java.util.EnumMap<Direction.Axis, VoxelShape> shapes = new java.util.EnumMap<>(Direction.Axis.class);
        public JointBlock(JointShape shape, Properties p) {
            super(p); this.shape = shape;
            for (Direction.Axis a : Direction.Axis.values()) shapes.put(a, outline(shape, a));
        }
        public static VoxelShape outline(JointShape shape, Direction.Axis a) {
            return switch (shape) {
                case SLAB -> Block.box(0, 0, 0, 16, 8, 16);
                case PANEL -> a == Direction.Axis.X ? Block.box(4, 0, 0, 12, 16, 16) : a == Direction.Axis.Y ? Block.box(0, 4, 0, 16, 12, 16) : Block.box(0, 0, 4, 16, 16, 12);
                case PIN -> a == Direction.Axis.X ? Block.box(0, 5, 5, 16, 11, 11) : a == Direction.Axis.Y ? Block.box(5, 0, 5, 11, 16, 11) : Block.box(5, 5, 0, 11, 11, 16);
                case BALL, POWERED_BALL -> Block.box(2, 0, 2, 14, 16, 14);
                default -> Shapes.block();
            };
        }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return shapes.get(s.getValue(AXIS)); }
        /** Joint strength for one block of this kind on its own (1x1). */
        public double strength() { return shape.strength; }
    }

    /** Bearing: the main hinge joint. Bearings side by side (across their axis) join into 2x2, 3x3 and 4x4 assemblies. */
    public static class BearingBlock extends JointBlock implements GridBlock {
        public BearingBlock(Properties p) { super(JointShape.FULL, p); registerDefaultState(defaultBlockState().setValue(Cells.CELL, 0).setValue(LONE, false)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { super.createBlockStateDefinition(b); b.add(Cells.CELL, LONE); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { BlockState s = super.getStateForPlacement(c); return s == null ? null : s.setValue(LONE, c.isSecondaryUseActive()); }
        public Direction.Axis[] plane(BlockState s) { return across(s.getValue(AXIS)); }
        @Override protected void onPlace(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState old, boolean m) { super.onPlace(s, l, p, old, m); Grid.placed(l, p, old.getBlock() != this); }
        @Override protected void onRemove(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState now, boolean m) { boolean gone = now.getBlock() != this; Grid.removed(l, p, s, gone); super.onRemove(s, l, p, now, m); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return RotatedPillarBlock.rotatePillar(s, r); }
    }

    /** Ball joint (plain or powered). Ball joints next to each other join into one big ball joint: an n x n square, up to n layers high.
     *  A flat 2x1x2 turns +-45 degrees, a 2x2x2 cube +-90 (range = 90 * layers / side). Sneak to keep one separate. */
    public static class BallJointBlock extends JointBlock implements GridBlock {
        private static final VoxelShape SMALL = Block.box(2, 0, 2, 14, 16, 14);
        public BallJointBlock(JointShape sh, Properties p) { super(sh, p); registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y).setValue(Cells.BOX, 0).setValue(LONE, false)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { super.createBlockStateDefinition(b); b.add(Cells.BOX, LONE); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(LONE, c.isSecondaryUseActive()); }
        public Direction.Axis[] plane(BlockState s) { return new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}; }
        @Override public boolean stacks() { return true; }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return s.getValue(Cells.BOX) == 0 ? SMALL : Shapes.block(); }
        @Override protected void onPlace(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState old, boolean m) { super.onPlace(s, l, p, old, m); Grid.placed(l, p, old.getBlock() != this); }
        @Override protected void onRemove(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState now, boolean m) { boolean gone = now.getBlock() != this; Grid.removed(l, p, s, gone); super.onRemove(s, l, p, now, m); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return s; }
        /** Range of motion cap either way, in degrees, for an assembly of this size and height. */
        public static int romCap(int size, int height) { return (int) Math.round(90.0 * Math.min(height, size) / size); }
    }

    /** Servo motor. AXIS = its shaft. Motors side by side (across their axis) join into 2x2, 3x3, 4x4 motors: one shaft, n^n the torque. */
    public static class MotorBlock extends RotatedPillarBlock implements GridBlock {
        public MotorBlock(Properties p) { super(p); registerDefaultState(defaultBlockState().setValue(Cells.CELL, 0).setValue(LONE, false)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { super.createBlockStateDefinition(b); b.add(Cells.CELL, LONE); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { BlockState s = super.getStateForPlacement(c); return s == null ? null : s.setValue(LONE, c.isSecondaryUseActive()); }
        public Direction.Axis[] plane(BlockState s) { return across(s.getValue(AXIS)); }
        @Override protected void onPlace(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState old, boolean m) { super.onPlace(s, l, p, old, m); Grid.placed(l, p, old.getBlock() != this); }
        @Override protected void onRemove(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState now, boolean m) { boolean gone = now.getBlock() != this; Grid.removed(l, p, s, gone); super.onRemove(s, l, p, now, m); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return RotatedPillarBlock.rotatePillar(s, r); }
    }

    /** Gearbox: placed between Servo Motors and a joint. Multiplies torque by ratio (times efficiency) and divides speed by ratio. Chains multiply. */
    public static class GearboxBlock extends DirectionalBlock {
        public final double ratio, efficiency; public final String label;
        /** FACING = the output side (the arrow points there). Place it by clicking the joint (or next gearbox) it should drive. */
        public GearboxBlock(double ratio, double efficiency, String label, Properties p) {
            super(p); this.ratio = ratio; this.efficiency = efficiency; this.label = label;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }
        @Override protected com.mojang.serialization.MapCodec<? extends DirectionalBlock> codec() { return null; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getClickedFace().getOpposite()); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
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
        public SlabJointBlock(Properties p) { super(JointShape.SLAB, p); registerDefaultState(defaultBlockState().setValue(TOP, false)); }
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
        public PanelJointBlock(Properties p) { super(JointShape.PANEL, p); registerDefaultState(defaultBlockState().setValue(SIDE, Direction.NORTH)); }
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
    /** Gyro: a reaction wheel. Gyros next to each other join into one big gyro (an n x n square, up to n layers high, like ball joints):
     *  n^n the capacity, times layers / side. Optional for walkers; a helper for ships, jets and hovering craft. */
    public static class GyroBlock extends Block implements GridBlock {
        public final double capacity;
        public GyroBlock(double cap, Properties p) { super(p); capacity = cap; registerDefaultState(defaultBlockState().setValue(Cells.BOX, 0).setValue(LONE, false)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(Cells.BOX, LONE); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(LONE, c.isSecondaryUseActive()); }
        public Direction.Axis[] plane(BlockState s) { return new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}; }
        @Override public boolean stacks() { return true; }
        @Override protected void onPlace(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState old, boolean m) { super.onPlace(s, l, p, old, m); Grid.placed(l, p, old.getBlock() != this); }
        @Override protected void onRemove(BlockState s, net.minecraft.world.level.Level l, BlockPos p, BlockState now, boolean m) { boolean gone = now.getBlock() != this; Grid.removed(l, p, s, gone); super.onRemove(s, l, p, now, m); }
        /** Capacity of the whole assembly this block starts (counted once, at its first block). */
        public double assemblyCapacity(BlockState s) { int n = Cells.size(s); return capacity * com.colossalcraft.tier.Parts.scale(n) * Cells.height(s) / n; }
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

    /** Locomotion Controller: configures legs and kinematics (IK solver, gait, keys). Right-click for its screen. */
    public static class LocomotionControllerBlock extends HorizontalDirectionalBlock {
        public LocomotionControllerBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
        @Override protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() { return null; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite()); }
        @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState s, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) com.colossalcraft.network.Net.openLocoParked(sp, pos);
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /** Jump Drive: a capacitor. Charges slowly from the Colossus' power, lets it all out at once to launch it. */
    public static class JumpDriveBlock extends Block {
        public static final double CAPACITY = 600_000; public static final double CHARGE_RATE = 400; // CJ, CJ per tick
        public JumpDriveBlock(Properties p) { super(p); }
    }
}
