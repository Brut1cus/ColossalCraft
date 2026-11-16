package colossus.assembly.graph;

import colossus.kinetics.ComponentScaling;
import colossus.physics.api.MassProperties;
import colossus.physics.math.Vec3;

import java.util.*;

/**
 * Build-to-life steps 4 and 5 (Scan, Validate) as pure logic:
 * flood-fill the build from the Hull Core, merge touching joint blocks into multiblock joints, split the build into
 * Segments at joint blocks, orient the Segment graph away from the core, and check budgets.
 */
public final class StructureScanner {
    private StructureScanner() {}

    /** One merged joint between a parent and a child Segment. */
    public record JointPlan(int id, String type, int axis, List<long[]> cells, int footprint, int parent, int child, Vec3 anchor, boolean loopClosure) {}

    /**
     * One rigid Segment. {@code mass.centerOfMass()} is relative to the Hull Core's block position (the scan origin),
     * which keeps the inertia sums precise even at ship-space coordinates in the tens of millions.
     */
    public record SegmentPlan(int id, List<long[]> cells, MassProperties mass, boolean root) {}

    public record Result(List<SegmentPlan> segments, List<JointPlan> joints, int blockCount, int latticeBlocks,
                         List<String> errors, List<String> warnings) {
        public boolean ok() { return errors.isEmpty(); }
        public SegmentPlan root() { return segments.stream().filter(SegmentPlan::root).findFirst().orElse(null); }
        public double totalMass() { return segments.stream().mapToDouble(s -> s.mass().mass()).sum(); }
    }

    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF); }
    static int kx(long k) { return (int) (k >> 38) << 6 >> 6; }
    static int ky(long k) { return (int) ((k >> 26) & 0xFFF) << 20 >> 20; }
    static int kz(long k) { return (int) (k & 0x3FFFFFF) << 6 >> 6; }

    /**
     * @param maxBlocks hard stop for the flood fill (budget + 1 so "over budget" can be reported without eating the world)
     */
    public static Result scan(VoxelSource src, int cx, int cy, int cz, CoreTier.Budget budget, int maxBlocks) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        // 1. flood fill
        LinkedHashMap<Long, VoxelSource.Kind> cells = new LinkedHashMap<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        long start = key(cx, cy, cz);
        cells.put(start, VoxelSource.Kind.CORE);
        q.add(start);
        int lattice = 0;
        boolean overflow = false;
        while (!q.isEmpty()) {
            long k = q.poll();
            int x = kx(k), y = ky(k), z = kz(k);
            for (int[] d : N6) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                long nk = key(nx, ny, nz);
                if (cells.containsKey(nk)) continue;
                VoxelSource.Kind kind = src.kind(nx, ny, nz);
                if (kind == VoxelSource.Kind.EMPTY || kind == VoxelSource.Kind.EXCLUDED) continue;
                if (kind == VoxelSource.Kind.CORE) {
                    errors.add("Another Hull Core at " + nx + " " + ny + " " + nz + ": nested Colossi are not supported yet");
                    continue;
                }
                if (cells.size() >= maxBlocks) { overflow = true; break; }
                cells.put(nk, kind);
                if (src.isLattice(nx, ny, nz)) lattice++;
                q.add(nk);
            }
            if (overflow) break;
        }
        if (overflow) {
            errors.add("The build has more than " + maxBlocks + " connected blocks. Is it touching the ground or another build?");
            return new Result(List.of(), List.of(), cells.size(), lattice, errors, warnings);
        }

        // 2. glued joint cells behave as plain blocks
        for (Map.Entry<Long, VoxelSource.Kind> e : cells.entrySet()) {
            if (e.getValue() != VoxelSource.Kind.JOINT) continue;
            long k = e.getKey();
            for (int[] d : N6) {
                long nk = key(kx(k) + d[0], ky(k) + d[1], kz(k) + d[2]);
                if (cells.get(nk) == VoxelSource.Kind.BLOCK && src.glued(kx(k), ky(k), kz(k), kx(nk), ky(nk), kz(nk))) {
                    e.setValue(VoxelSource.Kind.BLOCK);
                    break;
                }
            }
        }

        // 3. merge joints: connected joint cells with the same type and axis
        Map<Long, Integer> jointOf = new HashMap<>();
        List<List<Long>> jointGroups = new ArrayList<>();
        List<VoxelSource.JointInfo> jointInfo = new ArrayList<>();
        for (Map.Entry<Long, VoxelSource.Kind> e : cells.entrySet()) {
            if (e.getValue() != VoxelSource.Kind.JOINT || jointOf.containsKey(e.getKey())) continue;
            long k0 = e.getKey();
            VoxelSource.JointInfo info = src.joint(kx(k0), ky(k0), kz(k0));
            int gid = jointGroups.size();
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> jq = new ArrayDeque<>(List.of(k0));
            jointOf.put(k0, gid);
            while (!jq.isEmpty()) {
                long k = jq.poll();
                group.add(k);
                for (int[] d : N6) {
                    long nk = key(kx(k) + d[0], ky(k) + d[1], kz(k) + d[2]);
                    if (cells.get(nk) != VoxelSource.Kind.JOINT || jointOf.containsKey(nk)) continue;
                    if (!info.equals(src.joint(kx(nk), ky(nk), kz(nk)))) continue;
                    jointOf.put(nk, gid);
                    jq.add(nk);
                }
            }
            jointGroups.add(group);
            jointInfo.add(info);
        }

        // 4. segments: components of non-joint cells
        Map<Long, Integer> segOf = new HashMap<>();
        List<List<Long>> segCells = new ArrayList<>();
        for (Map.Entry<Long, VoxelSource.Kind> e : cells.entrySet()) {
            if (e.getValue() == VoxelSource.Kind.JOINT || segOf.containsKey(e.getKey())) continue;
            int sid = segCells.size();
            List<Long> seg = new ArrayList<>();
            ArrayDeque<Long> sq = new ArrayDeque<>(List.of(e.getKey()));
            segOf.put(e.getKey(), sid);
            while (!sq.isEmpty()) {
                long k = sq.poll();
                seg.add(k);
                for (int[] d : N6) {
                    long nk = key(kx(k) + d[0], ky(k) + d[1], kz(k) + d[2]);
                    VoxelSource.Kind nkind = cells.get(nk);
                    if (nkind == null || nkind == VoxelSource.Kind.JOINT || segOf.containsKey(nk)) continue;
                    segOf.put(nk, sid);
                    sq.add(nk);
                }
            }
            segCells.add(seg);
        }
        int rootSeg = segOf.get(start);

        // 5. segment graph oriented away from the root (BFS); joint housings belong to the parent
        List<Set<Integer>> jointNeighbours = new ArrayList<>();
        for (List<Long> g : jointGroups) {
            Set<Integer> ns = new LinkedHashSet<>();
            for (long k : g) for (int[] d : N6) {
                Integer s = segOf.get(key(kx(k) + d[0], ky(k) + d[1], kz(k) + d[2]));
                if (s != null) ns.add(s);
            }
            jointNeighbours.add(ns);
        }
        boolean[] visited = new boolean[segCells.size()];
        boolean[] jointUsed = new boolean[jointGroups.size()];
        int[] housingOwner = new int[jointGroups.size()];
        Arrays.fill(housingOwner, -1);
        List<int[]> edges = new ArrayList<>(); // {joint, parent, child, loop}
        ArrayDeque<Integer> bq = new ArrayDeque<>(List.of(rootSeg));
        visited[rootSeg] = true;
        while (!bq.isEmpty()) {
            int s = bq.poll();
            for (int j = 0; j < jointGroups.size(); j++) {
                if (jointUsed[j] || !jointNeighbours.get(j).contains(s)) continue;
                jointUsed[j] = true;
                housingOwner[j] = s;
                for (int other : jointNeighbours.get(j)) {
                    if (other == s) continue;
                    if (visited[other]) { edges.add(new int[]{j, s, other, 1}); continue; }
                    visited[other] = true;
                    edges.add(new int[]{j, s, other, 0});
                    bq.add(other);
                }
            }
        }
        for (int j = 0; j < jointGroups.size(); j++) {
            int nb = jointNeighbours.get(j).size();
            long k = jointGroups.get(j).get(0);
            if (nb < 2) warnings.add("Joint at " + kx(k) + " " + ky(k) + " " + kz(k) + " connects only one part; it acts as a plain block");
            if (housingOwner[j] < 0) housingOwner[j] = jointNeighbours.get(j).isEmpty() ? rootSeg : jointNeighbours.get(j).iterator().next();
        }
        for (int[] e : edges) if (e[3] == 1) warnings.add("Closed loop through joint " + e[0] + ": it is kept as an extra constraint");

        // 6. mass properties, relative to the core block
        List<SegmentPlan> segments = new ArrayList<>();
        for (int s = 0; s < segCells.size(); s++) {
            MassProperties.Builder mb = new MassProperties.Builder();
            List<long[]> list = new ArrayList<>();
            for (long k : segCells.get(s)) addCell(src, mb, list, k, cx, cy, cz);
            for (int j = 0; j < jointGroups.size(); j++) if (housingOwner[j] == s) for (long k : jointGroups.get(j)) addCell(src, mb, list, k, cx, cy, cz);
            segments.add(new SegmentPlan(s, list, mb.build(), s == rootSeg));
        }
        List<JointPlan> joints = new ArrayList<>();
        for (int[] e : edges) {
            List<Long> g = jointGroups.get(e[0]);
            double sx = 0, sy = 0, sz = 0;
            List<long[]> jc = new ArrayList<>();
            for (long k : g) { sx += kx(k) + 0.5; sy += ky(k) + 0.5; sz += kz(k) + 0.5; jc.add(new long[]{kx(k), ky(k), kz(k)}); }
            Vec3 anchor = new Vec3(sx / g.size(), sy / g.size(), sz / g.size());
            VoxelSource.JointInfo info = jointInfo.get(e[0]);
            joints.add(new JointPlan(joints.size(), info.type(), info.axis(), jc, ComponentScaling.footprintOf(g.size()), e[1], e[2], anchor, e[3] == 1));
        }

        // 7. validation
        if (cells.size() > budget.blocks()) errors.add("Too many blocks: " + cells.size() + " / " + budget.blocks() + " for this Hull Core");
        if (segments.size() > budget.segments()) errors.add("Too many parts: " + segments.size() + " / " + budget.segments());
        if (joints.size() > budget.joints()) errors.add("Too many joints: " + joints.size() + " / " + budget.joints());
        boolean supported = false;
        for (long k : cells.keySet()) {
            if (src.isSupportingGround(kx(k), ky(k) - 1, kz(k)) && !cells.containsKey(key(kx(k), ky(k) - 1, kz(k)))) { supported = true; break; }
        }
        if (!supported) warnings.add("Nothing rests on the ground: it will fall unless it has lift or buoyancy");
        return new Result(segments, joints, cells.size(), lattice, errors, warnings);
    }

    private static void addCell(VoxelSource src, MassProperties.Builder mb, List<long[]> list, long k, int ox, int oy, int oz) {
        int x = kx(k), y = ky(k), z = kz(k);
        mb.addBlock(src.mass(x, y, z), x - ox, y - oy, z - oz);
        list.add(new long[]{x, y, z});
    }
}
