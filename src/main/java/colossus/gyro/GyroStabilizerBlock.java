package colossus.gyro;

import colossus.core.ColossusRegistries;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Gyro Stabilizer. Redstone power spins its rotor up, in the world as well as on board, so a machine can be
 * assembled with its gyros already at speed. An N×N square merges into one rotor (see {@code GyroStabilizer}).
 */
public class GyroStabilizerBlock extends BaseEntityBlock {
    private static final MapCodec<GyroStabilizerBlock> CODEC = simpleCodec(GyroStabilizerBlock::new);

    public GyroStabilizerBlock(Properties props) { super(props); }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new GyroStabilizerBlockEntity(pos, state); }

    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ColossusRegistries.GYRO_BE.get(), GyroStabilizerBlockEntity::serverTick);
    }
}
