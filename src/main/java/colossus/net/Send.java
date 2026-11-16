package colossus.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server → client sends that skip connections without our channel (a client without the mod, a player still logging
 * in, a GameTest mock player). NeoForge throws if a payload goes to a connection that never negotiated it, and from a
 * level tick that would take the server down.
 */
public final class Send {
    private Send() {}

    public static void toPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) player.connection.send(payload);
    }

    public static void toLevel(ServerLevel level, CustomPacketPayload payload) {
        for (ServerPlayer p : level.players()) toPlayer(p, payload);
    }
}
