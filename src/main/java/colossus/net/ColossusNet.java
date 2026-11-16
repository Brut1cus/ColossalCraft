package colossus.net;

import colossus.client.ClientColossi;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration (NeoForge {@code CustomPacketPayload} + {@code StreamCodec}).
 * Pilot Seat input goes client → server in {@link colossus.pilot.PilotInput}.
 */
public final class ColossusNet {
    public static final String VERSION = "3";

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar(VERSION);
        r.playToClient(PoseSync.TYPE, PoseSync.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> ClientColossi.accept(payload)));
        r.playToClient(SegmentBlocks.TYPE, SegmentBlocks.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> ClientColossi.accept(payload)));
        r.playToServer(colossus.pilot.PilotInput.TYPE, colossus.pilot.PilotInput.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) colossus.pilot.PilotInput.apply(sp, payload);
        }));
        r.playToClient(ColossusRemoved.TYPE, ColossusRemoved.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> ClientColossi.remove(payload.colossus())));
    }

    private ColossusNet() {}
}
