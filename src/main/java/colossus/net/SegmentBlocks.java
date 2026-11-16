package colossus.net;

import colossus.core.Colossus;
import colossus.physics.math.Vec3;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Server → client: the blocks of one Segment (or one part of a large Segment), so the client can build its mesh.
 * Ship-space plots sit far outside any player's view distance, so their chunks never reach the client; this payload
 * carries what the renderer needs instead. Sent on assembly, on login and on dimension change, never while moving.
 *
 * @param segmentCount Segments in the Colossus
 * @param comRel       this Segment's centre of mass in the core frame
 * @param part         index of this part, {@code 0 ≤ part < parts}; part 0 resets the Segment on the client
 * @param cells        core-frame cells packed with {@code BlockPos.asLong()}
 * @param states       block state ids ({@code Block.getId}), index-aligned with {@code cells}
 */
public record SegmentBlocks(UUID colossus, int segment, int segmentCount, Vec3 comRel, int part, int parts,
                            long[] cells, int[] states) implements CustomPacketPayload {
    public static final Type<SegmentBlocks> TYPE = new Type<>(Colossus.id("segment_blocks"));
    public static final StreamCodec<ByteBuf, SegmentBlocks> CODEC = StreamCodec.of(SegmentBlocks::write, SegmentBlocks::read);

    /** Cells per payload; at most ~14 bytes each, so a part stays well under the 1 MiB custom payload limit. */
    public static final int MAX_CELLS_PER_PART = 32_768;

    public SegmentBlocks {
        if (cells.length != states.length) throw new IllegalArgumentException("cells and states differ in length");
    }

    static void write(ByteBuf buf, SegmentBlocks p) {
        buf.writeLong(p.colossus.getMostSignificantBits());
        buf.writeLong(p.colossus.getLeastSignificantBits());
        VarInt.write(buf, p.segment);
        VarInt.write(buf, p.segmentCount);
        buf.writeDouble(p.comRel.x());
        buf.writeDouble(p.comRel.y());
        buf.writeDouble(p.comRel.z());
        VarInt.write(buf, p.part);
        VarInt.write(buf, p.parts);
        VarInt.write(buf, p.cells.length);
        for (int i = 0; i < p.cells.length; i++) {
            VarLong.write(buf, p.cells[i]);
            VarInt.write(buf, p.states[i]);
        }
    }

    static SegmentBlocks read(ByteBuf buf) {
        UUID id = new UUID(buf.readLong(), buf.readLong());
        int segment = VarInt.read(buf), count = VarInt.read(buf);
        Vec3 com = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        int part = VarInt.read(buf), parts = VarInt.read(buf);
        int n = VarInt.read(buf);
        if (n < 0 || n > MAX_CELLS_PER_PART) throw new IllegalArgumentException("bad cell count " + n);
        long[] cells = new long[n];
        int[] states = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = VarLong.read(buf);
            states[i] = VarInt.read(buf);
        }
        return new SegmentBlocks(id, segment, count, com, part, parts, cells, states);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
