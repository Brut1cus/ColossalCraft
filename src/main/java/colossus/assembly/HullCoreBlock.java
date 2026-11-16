package colossus.assembly;

import colossus.assembly.graph.CoreTier;
import colossus.core.ColossusConfig;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

/**
 * The Hull Core. Right-click: scan and report (the Assembly GUI replaces this in Phase 2). Sneak + right-click: assemble.
 */
public class HullCoreBlock extends BaseEntityBlock {
    public final CoreTier tier;

    /** Codec only used by data tooling; the tier is fixed per registered block. */
    private static final MapCodec<HullCoreBlock> CODEC = simpleCodec(p -> new HullCoreBlock(p, CoreTier.IRON));

    public HullCoreBlock(Properties props, CoreTier tier) {
        super(props);
        this.tier = tier;
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new HullCoreBlockEntity(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            if (player.isShiftKeyDown()) {
                if (!ColossusConfig.ENABLE_ASSEMBLY.get()) {
                    sp.sendSystemMessage(Component.translatable("colossus.assembly.disabled").withStyle(ChatFormatting.RED));
                } else {
                    AssemblyService.assemble(sl, pos, sp);
                }
            } else {
                AssemblyService.report(sl, pos, sp);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
