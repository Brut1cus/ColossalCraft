package colossus.net;

import colossus.core.Colossus;
import colossus.physics.math.Pose;
import colossus.physics.math.Vec3;
import colossus.shipyard.ColossusRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Server → client, 20 Hz: every Segment's pose (centre of mass position + smallest-three quaternion).
 * Clients interpolate between the last two at partial tick (renderer: Phase 2).
 */
public record PoseSync(UUID colossus, double[] positions, int[] rotations) implements CustomPacketPayload {
    public static final Type<PoseSync> TYPE = new Type<>(Colossus.id("pose_sync"));

    public static final StreamCodec<ByteBuf, PoseSync> CODEC = StreamCodec.of(PoseSync::write, PoseSync::read);

    public static PoseSync of(ColossusRecord r) {
        int n = r.segments.size();
        double[] p = new double[n * 3];
        int[] q = new int[n];
        for (int i = 0; i < n; i++) {
            Pose pose = r.segments.get(i).pose;
            p[i * 3] = pose.position().x();
            p[i * 3 + 1] = pose.position().y();
            p[i * 3 + 2] = pose.position().z();
            q[i] = PoseCodec.packQuat(pose.rotation());
        }
        return new PoseSync(r.id, p, q);
    }

    public Pose pose(int segment) {
        return new Pose(new Vec3(positions[segment * 3], positions[segment * 3 + 1], positions[segment * 3 + 2]), PoseCodec.unpackQuat(rotations[segment]));
    }

    public int segments() { return rotations.length; }

    static void write(ByteBuf buf, PoseSync p) {
        buf.writeLong(p.colossus.getMostSignificantBits());
        buf.writeLong(p.colossus.getLeastSignificantBits());
        buf.writeShort(p.rotations.length);
        for (int i = 0; i < p.rotations.length; i++) {
            buf.writeDouble(p.positions[i * 3]);
            buf.writeDouble(p.positions[i * 3 + 1]);
            buf.writeDouble(p.positions[i * 3 + 2]);
            buf.writeInt(p.rotations[i]);
        }
    }

    static PoseSync read(ByteBuf buf) {
        UUID id = new UUID(buf.readLong(), buf.readLong());
        int n = buf.readUnsignedShort();
        double[] pos = new double[n * 3];
        int[] rot = new int[n];
        for (int i = 0; i < n; i++) {
            pos[i * 3] = buf.readDouble();
            pos[i * 3 + 1] = buf.readDouble();
            pos[i * 3 + 2] = buf.readDouble();
            rot[i] = buf.readInt();
        }
        return new PoseSync(id, pos, rot);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
