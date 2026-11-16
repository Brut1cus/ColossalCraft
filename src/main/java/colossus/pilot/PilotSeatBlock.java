package colossus.pilot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Pilot Seat: sit on it to drive the Colossus it is part of (W/S forward and back, A/D turn, sneak to get up). It works
 * before assembly too: assemble while seated and you keep your seat.
 */
public class PilotSeatBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

    public PilotSeatBlock(Properties props) { super(props); }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isSecondaryUseActive()) return InteractionResult.PASS;
        if (level instanceof ServerLevel sl) {
            SeatEntity seat = sl.getEntitiesOfClass(SeatEntity.class, new AABB(pos)).stream().findFirst().orElse(null);
            if (seat == null) {
                seat = SeatEntity.onBlock(sl, pos);
                sl.addFreshEntity(seat);
            }
            if (!seat.isVehicle()) player.startRiding(seat);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
