package colossus;

import colossus.assembly.graph.CoreTier;
import colossus.assembly.graph.StructureScanner;
import colossus.assembly.graph.VoxelSource;
import colossus.core.data.BlockPhysicsTable;
import colossus.kinetics.ComponentScaling;
import colossus.kinetics.KineticNetwork;
import colossus.net.PoseCodec;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import colossus.shipyard.PlotAllocator;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class FoundationTest {

    // ------------------------------------------------------------------ scanner

    /** Map-backed world for scanner tests. Ground (EXCLUDED) at y < 0. */
    static final class World implements VoxelSource {
        final Map<String, Kind> kinds = new HashMap<>();
        final Map<String, JointInfo> joints = new HashMap<>();
        final Set<String> lattice = new HashSet<>();
        static String k(int x, int y, int z) { return x + "," + y + "," + z; }
        World block(int x, int y, int z) { kinds.put(k(x, y, z), Kind.BLOCK); return this; }
        World core(int x, int y, int z) { kinds.put(k(x, y, z), Kind.CORE); return this; }
        World joint(int x, int y, int z, String type, int axis) { kinds.put(k(x, y, z), Kind.JOINT); joints.put(k(x, y, z), new JointInfo(type, axis)); return this; }
        @Override public Kind kind(int x, int y, int z) { return y < 0 ? Kind.EXCLUDED : kinds.getOrDefault(k(x, y, z), Kind.EMPTY); }
        @Override public JointInfo joint(int x, int y, int z) { return joints.get(k(x, y, z)); }
        @Override public double mass(int x, int y, int z) { return 1000; }
        @Override public boolean isLattice(int x, int y, int z) { return lattice.contains(k(x, y, z)); }
    }

    static final CoreTier.Budget BIG = CoreTier.STEEL.budget(0, 1_000_000);

    /**
     * Biped: torso (3 wide at y=6..7 with the core), two hips (ball joints) at y=5, thighs y=3..4,
     * knees (hinges) y=2, shins y=0..1 standing on the ground.
     */
    static World biped() {
        World w = new World();
        for (int x = 0; x < 5; x++) { w.block(x, 6, 0); w.block(x, 7, 0); }
        w.core(2, 8, 0);
        for (int x : new int[]{0, 4}) {
            w.joint(x, 5, 0, "ball", 1);
            w.block(x, 4, 0).block(x, 3, 0);
            w.joint(x, 2, 0, "hinge", 0);
            w.block(x, 1, 0).block(x, 0, 0);
        }
        return w;
    }

    @Test public void scannerSplitsAtJoints() {
        StructureScanner.Result r = StructureScanner.scan(biped(), 2, 8, 0, BIG, 10_000);
        assertTrue(r.errors().toString(), r.ok());
        assertEquals(5, r.segments().size());              // torso, 2 thighs, 2 shins
        assertEquals(4, r.joints().size());
        assertEquals(23, r.blockCount());
        StructureScanner.SegmentPlan root = r.root();
        assertEquals(11 + 2, root.cells().size());          // torso + core + 2 hip housings
        // joints point away from the core: hips have the torso as parent
        for (StructureScanner.JointPlan j : r.joints()) {
            if (j.type().equals("ball")) assertEquals(root.id(), j.parent());
            else assertNotEquals(root.id(), j.parent());
        }
        assertEquals(23_000, r.totalMass(), 1e-6);
        assertTrue(r.warnings().isEmpty());
    }

    @Test public void touchingJointBlocksMergeIntoOneBigJoint() {
        World w = new World();
        w.core(0, 5, 0);
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) { w.block(x, 4, z); w.joint(x, 3, z, "hinge", 1); w.block(x, 2, z); }
        StructureScanner.Result r = StructureScanner.scan(w, 0, 5, 0, BIG, 10_000);
        assertEquals(1, r.joints().size());
        assertEquals(2, r.joints().get(0).footprint());
        assertEquals(new Vec3(1, 3.5, 1), r.joints().get(0).anchor());
        assertTrue(r.warnings().get(0).contains("Nothing rests on the ground"));
    }

    @Test public void budgetAndWorldEatingAreCaught() {
        World w = biped();
        StructureScanner.Result small = StructureScanner.scan(w, 2, 8, 0, CoreTier.WOODEN.budget(0, 1_000_000), 10_000);
        assertTrue(small.ok()); // 23 blocks, 5 parts, 4 joints fit a Wooden Core (512 / 8 / 8)
        StructureScanner.Result tiny = StructureScanner.scan(w, 2, 8, 0, new CoreTier.Budget(10, 2, 2), 10_000);
        assertEquals(3, tiny.errors().size());
        StructureScanner.Result runaway = StructureScanner.scan(w, 2, 8, 0, BIG, 12);
        assertFalse(runaway.ok());
        assertTrue(runaway.errors().get(0).contains("more than 12"));
    }

    @Test public void latticeRaisesNetheriteBudget() {
        CoreTier.Budget b = CoreTier.NETHERITE.budget(4, 1_000_000);
        assertEquals(131_072 + 4 * 16_384, b.blocks());
        assertEquals(512 + 128, b.segments());
        assertEquals(1_000_000, CoreTier.NETHERITE.budget(100, 1_000_000).blocks());
        assertEquals(512, CoreTier.WOODEN.budget(10, 1_000_000).blocks());
    }

    @Test public void greedyMeshMergesSolidRegions() {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 5; z++) cells.add(new int[]{x, y, z});
        cells.add(new int[]{10, 0, 0});
        List<colossus.physics.api.Box> boxes = colossus.physics.shape.GreedyMesher.mesh(cells, Vec3.ZERO);
        assertEquals(2, boxes.size());
        double vol = boxes.stream().mapToDouble(colossus.physics.api.Box::volume).sum();
        assertEquals(61, vol, 1e-9);
    }

    // ------------------------------------------------------------------ kinetics

    @Test public void sizeScalingIsSuperLinear() {
        ComponentScaling s = new ComponentScaling();
        double t1 = s.torqueCapacity(ComponentScaling.Component.BEARING, 1);
        assertEquals(27 * t1, s.torqueCapacity(ComponentScaling.Component.BEARING, 3), 1e-6);
        assertEquals(3, ComponentScaling.footprintOf(9));
        assertEquals(1, ComponentScaling.footprintOf(6)); // not a filled square: no merge
    }

    @Test public void networkSagsUnderLoadAndGearsTradeSpeedForTorque() {
        // 60 rpm motor, 10 kN·m stall, 4:1 reduction to a joint needing 8 kN·m
        KineticNetwork n = new KineticNetwork()
                .add(new KineticNetwork.Source(0, 60, 10_000))
                .link(0, 1, 0.25)
                .add(KineticNetwork.Consumer.constant(1, 8_000))
                .add(new KineticNetwork.Part("shaft", 1, 50_000));
        KineticNetwork.State s = n.solve();
        assertFalse(s.jammed());
        // referred demand at the motor = 2 kN·m → 20 % sag → 48 rpm at the motor, 12 rpm at the joint
        assertEquals(48, s.groups().get(0).rpm(), 0.1);
        assertEquals(12, s.groups().get(1).rpm(), 0.1);
        assertTrue(s.overloaded().isEmpty());
        // without the gearbox the same load stalls... nearly: 8 kN·m of 10 → 12 rpm
        KineticNetwork direct = new KineticNetwork().add(new KineticNetwork.Source(0, 60, 10_000)).add(KineticNetwork.Consumer.constant(0, 8_000));
        assertEquals(12, direct.solve().referenceRpm(), 0.1);
        // overload a weak part
        KineticNetwork weak = new KineticNetwork().add(new KineticNetwork.Source(0, 60, 10_000)).add(KineticNetwork.Consumer.constant(0, 8_000))
                .add(new KineticNetwork.Part("thin shaft", 0, 5_000));
        assertEquals(1, weak.solve().overloaded().size());
    }

    @Test public void conflictingGearsJam() {
        KineticNetwork n = new KineticNetwork().add(new KineticNetwork.Source(0, 60, 1000)).link(0, 1, 2).link(1, 2, 2).link(0, 2, 3);
        assertTrue(n.solve().jammed());
        KineticNetwork opposed = new KineticNetwork().add(new KineticNetwork.Source(0, 60, 1000)).add(new KineticNetwork.Source(1, 60, 1000)).link(0, 1, -1);
        assertTrue(opposed.solve().jammed());
    }

    // ------------------------------------------------------------------ shipyard, net, data

    @Test public void plotsAreFarAwayAndReused() {
        PlotAllocator a = new PlotAllocator();
        PlotAllocator.Plot p0 = a.allocate(), p1 = a.allocate();
        assertEquals(20_000_000, p0.minX());
        assertEquals(20_000_000 + 1024, p1.minX());
        assertEquals(p1, a.at(p1.minX() + 500, p1.minZ() + 10));
        assertNull(a.at(0, 0));
        a.free(0);
        assertEquals(0, a.allocate().index());
        assertEquals(2, PlotAllocator.plotsForHeight(500, 384));
        PlotAllocator b = new PlotAllocator();
        b.load(a.save());
        assertTrue(b.isUsed(1));
        // every plot stays inside the world border
        PlotAllocator.Plot last = a.plot(a.capacity() - 1);
        assertTrue(last.minX() + last.size() <= PlotAllocator.WORLD_LIMIT);
    }

    @Test public void smallestThreeRoundTrips() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            Quat q = new Quat(r.nextGaussian(), r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            Quat d = PoseCodec.unpackQuat(PoseCodec.packQuat(q));
            double angle = q.conjugate().mul(d).angle();
            assertTrue("error " + Math.toDegrees(angle), angle < Math.toRadians(0.2));
        }
    }

    @Test public void blockPhysicsResolutionOrder() {
        BlockPhysicsTable t = new BlockPhysicsTable();
        t.putId("minecraft:iron_block", new BlockPhysicsTable.Props(7800, 0.4, 0.2));
        t.putTag("minecraft:logs", new BlockPhysicsTable.Props(600, 0.5, 0.2));
        t.putSound("wool", new BlockPhysicsTable.Props(50, 0.6, 0.1));
        assertEquals(7800, t.mass(key("minecraft:iron_block", List.of("minecraft:logs"), "metal", 1)), 0);
        assertEquals(600, t.mass(key("minecraft:oak_log", List.of("minecraft:logs"), "wood", 1)), 0);
        assertEquals(25, t.mass(key("minecraft:white_carpet_slab", List.of(), "wool", 0.5)), 0);
        assertEquals(1000, t.mass(key("othermod:thing", List.of(), null, 1)), 0);
    }

    static BlockPhysicsTable.BlockKey key(String id, List<String> tags, String sound, double vol) {
        return new BlockPhysicsTable.BlockKey() {
            public String id() { return id; }
            public Collection<String> tags() { return tags; }
            public String soundType() { return sound; }
            public double shapeVolume() { return vol; }
        };
    }
}
