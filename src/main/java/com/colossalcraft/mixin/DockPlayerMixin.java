package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusDock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chests, furnaces and other screens of blocks on a Colossus stay open while the player is near that block on the Colossus. */
@Mixin(Player.class)
public abstract class DockPlayerMixin {
    @Inject(method = "canInteractWithBlock(Lnet/minecraft/core/BlockPos;D)Z", at = @At("HEAD"), cancellable = true)
    private void colossalcraft$dockReach(BlockPos pos, double extra, CallbackInfoReturnable<Boolean> cir) {
        if (ColossusDock.reachable((Player) (Object) this, pos, extra)) cir.setReturnValue(true);
    }
}
