package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusDock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block change in the dock goes back to the Colossus it belongs to. */
@Mixin(Level.class)
public abstract class DockLevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void colossalcraft$dockChange(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && ColossusDock.isDock((Level) (Object) this)) ColossusDock.changed(pos);
    }
}
