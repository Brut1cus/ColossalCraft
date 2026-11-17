package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusDock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Colossus dock chunk arrives: store it (no lighting or terrain rendering: the Colossus draws its own blocks). */
@Mixin(ClientPacketListener.class)
public abstract class ShipClientPacketMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V", shift = At.Shift.AFTER), cancellable = true)
    private void colossalcraft$shipChunk(ClientboundLevelChunkWithLightPacket p, CallbackInfo ci) {
        if (!ColossusDock.isShipChunk(p.getX(), p.getZ())) return;
        var lv = Minecraft.getInstance().level;
        if (lv != null) { var d = p.getChunkData(); lv.getChunkSource().replaceWithPacketData(p.getX(), p.getZ(), d.getReadBuffer(), d.getHeightmaps(), d.getBlockEntitiesTagsConsumer(p.getX(), p.getZ())); }
        ci.cancel();
    }
}
