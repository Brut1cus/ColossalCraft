package colossus.net;

import colossus.client.ClientColossi;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration (NeoForge {@code CustomPacketPayload} + {@code StreamCodec}).
 * Phase 2 adds the per-tick control-state payload (axes quantised, buttons as a bitset) from seats to the server.
 */
public final class ColossusNet {
    public static final String VERSION = "2";

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar(VERSION);
        r.playToClient(PoseSync.TYPE, PoseSync.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> ClientColossi.accept(payload)));
        r.playToClient(SegmentBlocks.TYPE, SegmentBlocks.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> ClientColossi.accept(payload)));
        r.playToClient(ColossusRemoved.TYPE, ColossusRemoved.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> ClientColossi.remove(payload.colossus())));
    }

    private ColossusNet() {}
}
