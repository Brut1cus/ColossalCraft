package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusInteract;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Using or mining a block on a Colossus arrives as a normal packet for its dock place: the Colossus handles it. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ShipServerPacketMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER), cancellable = true)
    private void colossalcraft$useOnShip(ServerboundUseItemOnPacket p, CallbackInfo ci) {
        if (ColossusInteract.packetUse(player, p)) ci.cancel();
    }
    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER), cancellable = true)
    private void colossalcraft$mineShip(ServerboundPlayerActionPacket p, CallbackInfo ci) {
        if (ColossusInteract.packetMine(player, p)) ci.cancel();
    }
}
