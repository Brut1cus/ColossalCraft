package colossus.net;

import colossus.core.Colossus;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Server → client: a Colossus was disassembled; drop its poses and meshes. */
public record ColossusRemoved(UUID colossus) implements CustomPacketPayload {
    public static final Type<ColossusRemoved> TYPE = new Type<>(Colossus.id("colossus_removed"));
    public static final StreamCodec<ByteBuf, ColossusRemoved> CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeLong(p.colossus.getMostSignificantBits()); buf.writeLong(p.colossus.getLeastSignificantBits()); },
            buf -> new ColossusRemoved(new UUID(buf.readLong(), buf.readLong())));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
