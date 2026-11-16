package colossus.client;

import colossus.net.PoseSync;
import colossus.net.SegmentBlocks;
import colossus.physics.math.Vec3;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side state per Colossus: the last two pose packets (for interpolation) and each Segment's blocks (for meshing).
 * Contains no client-only classes, so it is safe to reference from common payload registration and from GameTests.
 * The renderer ({@code colossus.client.render}) reads it every frame and rebuilds a mesh when a Segment's
 * {@link SegmentData#version()} changes.
 */
public final class ClientColossi {
    public record Poses(PoseSync previous, PoseSync latest, long receivedAtNanos) {}

    /**
     * One Segment's blocks. Mutated only on the client main thread (payload handlers use {@code enqueueWork}).
     * {@code cells}/{@code states} hold {@code filled} entries; the Segment is drawable once every part arrived.
     */
    public static final class SegmentData {
        private static final AtomicInteger VERSIONS = new AtomicInteger();

        Vec3 comRel = Vec3.ZERO;
        long[] cells = new long[0];
        int[] states = new int[0];
        int filled, partsSeen, parts = 1;
        int version;

        public Vec3 comRel() { return comRel; }
        public int size() { return filled; }
        public long cell(int i) { return cells[i]; }
        public int state(int i) { return states[i]; }
        public boolean complete() { return partsSeen >= parts; }
        /** Changes whenever the contents change; unique across all Segments. */
        public int version() { return version; }

        void accept(SegmentBlocks p) {
            if (p.part() == 0) { filled = 0; partsSeen = 0; }
            comRel = p.comRel();
            parts = Math.max(1, p.parts());
            int need = filled + p.cells().length;
            if (cells.length < need) {
                cells = Arrays.copyOf(cells, Math.max(need, cells.length * 2));
                states = Arrays.copyOf(states, cells.length);
            }
            System.arraycopy(p.cells(), 0, cells, filled, p.cells().length);
            System.arraycopy(p.states(), 0, states, filled, p.states().length);
            filled = need;
            partsSeen++;
            version = VERSIONS.incrementAndGet();
        }
    }

    /** Block contents of one Colossus, index-aligned with its pose packets. */
    public static final class Structure {
        final SegmentData[] segments;

        Structure(int count) {
            segments = new SegmentData[count];
            for (int i = 0; i < count; i++) segments[i] = new SegmentData();
        }

        public int segments() { return segments.length; }
        public SegmentData segment(int i) { return segments[i]; }
    }

    private static final Map<UUID, Poses> POSES = new ConcurrentHashMap<>();
    private static final Map<UUID, Structure> STRUCTURES = new ConcurrentHashMap<>();

    public static void accept(PoseSync p) {
        POSES.compute(p.colossus(), (id, old) -> new Poses(old == null ? p : old.latest(), p, System.nanoTime()));
    }

    public static void accept(SegmentBlocks p) {
        if (p.segment() < 0 || p.segment() >= p.segmentCount()) return;
        Structure s = STRUCTURES.get(p.colossus());
        if (s == null || s.segments() != p.segmentCount()) {
            s = new Structure(p.segmentCount());
            STRUCTURES.put(p.colossus(), s);
        }
        s.segments[p.segment()].accept(p);
    }

    public static void remove(UUID id) {
        POSES.remove(id);
        STRUCTURES.remove(id);
    }

    public static Poses get(UUID id) { return POSES.get(id); }

    public static Structure structure(UUID id) { return STRUCTURES.get(id); }

    public static Map<UUID, Poses> all() { return POSES; }

    public static Map<UUID, Structure> structures() { return STRUCTURES; }

    /** On logout, respawn and dimension change: the server resends what the new level holds. */
    public static void clear() {
        POSES.clear();
        STRUCTURES.clear();
    }

    private ClientColossi() {}
}
