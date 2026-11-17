package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusDock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sounds and effects in the dock are heard and seen at the Colossus instead. */
@Mixin(ServerLevel.class)
public abstract class DockServerLevelMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
    private void colossalcraft$dockSound(Player except, double x, double y, double z, Holder<SoundEvent> snd, SoundSource src, float vol, float pitch, long seed, CallbackInfo ci) {
        if (ColossusDock.isDock((ServerLevel) (Object) this) && ColossusDock.relaySound(x, y, z, snd, src, vol, pitch, seed)) ci.cancel();
    }
    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
    private void colossalcraft$dockEvent(Player except, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (ColossusDock.isDock((ServerLevel) (Object) this) && ColossusDock.relayLevelEvent(type, pos, data)) ci.cancel();
    }
}
