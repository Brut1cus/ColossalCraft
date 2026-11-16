package colossus.shipyard;

import colossus.physics.math.Mat3;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent description of one assembled Colossus: its Segments (with the ship-space plot holding each one's blocks)
 * and its joints. Physics handles are runtime-only and rebuilt from this after a restart.
 * <p>
 * Frames: every cell is stored relative to the Hull Core's block position at assembly ("core frame"). A Segment's
 * body frame is the core frame shifted to the Segment's centre of mass ({@code comRel}) and moved by {@code pose}.
 */
public final class ColossusRecord {
    public final UUID id;
    public UUID owner;
    public String coreTier;
    /** Hull Core world position at assembly. */
    public BlockPos origin;
    public final List<SegmentRecord> segments = new ArrayList<>();
    public final List<JointRecord> joints = new ArrayList<>();

    public ColossusRecord(UUID id) { this.id = id; }

    public SegmentRecord root() {
        for (SegmentRecord s : segments) if (s.root) return s;
        return segments.isEmpty() ? null : segments.get(0);
    }

    public int blockCount() { return segments.stream().mapToInt(s -> s.cells.length).sum(); }

    public static final class SegmentRecord {
        public int plotIndex;
        /** Ship-space position that core-frame (0, 0, 0) maps to for this Segment's plot. */
        public BlockPos plotAnchor;
        /** Cells in the core frame, packed with {@link BlockPos#asLong()}. */
        public long[] cells = new long[0];
        public Vec3 comRel = Vec3.ZERO;
        public double mass;
        public Mat3 inertia = Mat3.ZERO;
        public Pose pose = Pose.IDENTITY;
        public boolean root;

        public BlockPos plotPos(long cell) { return plotAnchor.offset(BlockPos.of(cell)); }

        /** Centre of a cell in the body frame (relative to the centre of mass). */
        public Vec3 local(BlockPos rel) { return new Vec3(rel.getX() + 0.5, rel.getY() + 0.5, rel.getZ() + 0.5).sub(comRel); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Plot", plotIndex);
            t.putLong("Anchor", plotAnchor.asLong());
            t.putLongArray("Cells", cells);
            putVec(t, "Com", comRel);
            t.putDouble("Mass", mass);
            double[] i = {inertia.get(0, 0), inertia.get(1, 1), inertia.get(2, 2), inertia.get(0, 1), inertia.get(0, 2), inertia.get(1, 2)};
            ListTag il = new ListTag();
            for (double d : i) il.add(net.minecraft.nbt.DoubleTag.valueOf(d));
            t.put("Inertia", il);
            putVec(t, "Pos", pose.position());
            Quat q = pose.rotation();
            t.putDouble("Qw", q.w()); t.putDouble("Qx", q.x()); t.putDouble("Qy", q.y()); t.putDouble("Qz", q.z());
            t.putBoolean("Root", root);
            return t;
        }

        static SegmentRecord load(CompoundTag t) {
            SegmentRecord s = new SegmentRecord();
            s.plotIndex = t.getInt("Plot");
            s.plotAnchor = BlockPos.of(t.getLong("Anchor"));
            s.cells = t.getLongArray("Cells");
            s.comRel = getVec(t, "Com");
            s.mass = t.getDouble("Mass");
            ListTag il = t.getList("Inertia", Tag.TAG_DOUBLE);
            if (il.size() == 6) {
                double xx = il.getDouble(0), yy = il.getDouble(1), zz = il.getDouble(2), xy = il.getDouble(3), xz = il.getDouble(4), yz = il.getDouble(5);
                s.inertia = new Mat3(xx, xy, xz, xy, yy, yz, xz, yz, zz);
            }
            s.pose = new Pose(getVec(t, "Pos"), new Quat(t.getDouble("Qw"), t.getDouble("Qx"), t.getDouble("Qy"), t.getDouble("Qz")).normalize());
            s.root = t.getBoolean("Root");
            return s;
        }
    }

    public static final class JointRecord {
        public String kind;
        public int parent, child;
        /** Joint centre in the core frame. */
        public Vec3 anchorRel = Vec3.ZERO;
        public int axis;
        public int footprint = 1;
        public boolean broken;

        public Vec3 axisVector() { return axis == 0 ? Vec3.X : axis == 1 ? Vec3.Y : Vec3.Z; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Kind", kind);
            t.putInt("Parent", parent);
            t.putInt("Child", child);
            putVec(t, "Anchor", anchorRel);
            t.putInt("Axis", axis);
            t.putInt("Footprint", footprint);
            t.putBoolean("Broken", broken);
            return t;
        }

        static JointRecord load(CompoundTag t) {
            JointRecord j = new JointRecord();
            j.kind = t.getString("Kind");
            j.parent = t.getInt("Parent");
            j.child = t.getInt("Child");
            j.anchorRel = getVec(t, "Anchor");
            j.axis = t.getInt("Axis");
            j.footprint = Math.max(1, t.getInt("Footprint"));
            j.broken = t.getBoolean("Broken");
            return j;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        if (owner != null) t.putUUID("Owner", owner);
        t.putString("Tier", coreTier);
        t.putLong("Origin", origin.asLong());
        ListTag segs = new ListTag();
        for (SegmentRecord s : segments) segs.add(s.save());
        t.put("Segments", segs);
        ListTag js = new ListTag();
        for (JointRecord j : joints) js.add(j.save());
        t.put("Joints", js);
        return t;
    }

    public static ColossusRecord load(CompoundTag t) {
        ColossusRecord c = new ColossusRecord(t.getUUID("Id"));
        c.owner = t.hasUUID("Owner") ? t.getUUID("Owner") : null;
        c.coreTier = t.getString("Tier");
        c.origin = BlockPos.of(t.getLong("Origin"));
        for (Tag s : t.getList("Segments", Tag.TAG_COMPOUND)) c.segments.add(SegmentRecord.load((CompoundTag) s));
        for (Tag j : t.getList("Joints", Tag.TAG_COMPOUND)) c.joints.add(JointRecord.load((CompoundTag) j));
        return c;
    }

    static void putVec(CompoundTag t, String k, Vec3 v) {
        t.putDouble(k + "X", v.x()); t.putDouble(k + "Y", v.y()); t.putDouble(k + "Z", v.z());
    }

    static Vec3 getVec(CompoundTag t, String k) { return new Vec3(t.getDouble(k + "X"), t.getDouble(k + "Y"), t.getDouble(k + "Z")); }
}
