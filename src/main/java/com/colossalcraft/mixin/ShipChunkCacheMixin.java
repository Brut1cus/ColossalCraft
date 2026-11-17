package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusDock;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The client keeps the dock chunks of nearby Colossi (their real blocks, far away from everything) in a little map of its own,
 * outside the normal view window. So the client world really has those blocks: F3, block-info mods, mining and using them
 * all work through the normal game code.
 */
@Mixin(ClientChunkCache.class)
public abstract class ShipChunkCacheMixin {
    @Shadow @Final ClientLevel level;
    @Unique private final Map<Long, LevelChunk> colossalcraft$ship = new HashMap<>();

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void colossalcraft$getShip(int x, int z, ChunkStatus st, boolean require, CallbackInfoReturnable<LevelChunk> cir) {
        if (!ColossusDock.isShipChunk(x, z)) return;
        LevelChunk c = colossalcraft$ship.get(ChunkPos.asLong(x, z));
        if (c != null) cir.setReturnValue(c);
    }
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
    private void colossalcraft$loadShip(int x, int z, FriendlyByteBuf buf, CompoundTag heightmaps, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> bes, CallbackInfoReturnable<LevelChunk> cir) {
        if (!ColossusDock.isShipChunk(x, z)) return;
        long k = ChunkPos.asLong(x, z);
        LevelChunk c = colossalcraft$ship.get(k);
        if (c == null) { c = new LevelChunk(level, new ChunkPos(x, z)); colossalcraft$ship.put(k, c); }
        c.replaceWithPacketData(buf, heightmaps, bes);
        c.setLoaded(true);
        cir.setReturnValue(c);
    }
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void colossalcraft$dropShip(ChunkPos pos, CallbackInfo ci) {
        if (!ColossusDock.isShipChunk(pos.x, pos.z)) return;
        LevelChunk c = colossalcraft$ship.remove(pos.toLong());
        if (c != null) c.setLoaded(false);
        ci.cancel();
    }
}
