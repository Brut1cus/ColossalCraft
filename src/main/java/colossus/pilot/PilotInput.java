package colossus.pilot;

import colossus.core.Colossus;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → server, 20 Hz while riding a Pilot Seat: the rider's movement keys. {@code forward} and {@code turn} are in
 * −1..1 (W = +1 forward, A = +1 turn left). The server checks the sender really rides that seat.
 */
public record PilotInput(int seat, float forward, float turn) implements CustomPacketPayload {
    public static final Type<PilotInput> TYPE = new Type<>(Colossus.id("pilot_input"));
    public static final StreamCodec<ByteBuf, PilotInput> CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeInt(p.seat); buf.writeFloat(p.forward); buf.writeFloat(p.turn); },
            buf -> new PilotInput(buf.readInt(), buf.readFloat(), buf.readFloat()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Server side: apply the input if the player rides that seat. Returns true if it was applied. */
    public static boolean apply(ServerPlayer player, PilotInput p) {
        if (!(player.serverLevel().getEntity(p.seat) instanceof SeatEntity seat) || !seat.hasPassenger(player)) return false;
        if (!Float.isFinite(p.forward) || !Float.isFinite(p.turn)) return false;
        seat.steer(p.forward, p.turn);
        return true;
    }
}
