package colossus;

import colossus.assembly.BodyPlanner;
import colossus.assembly.JointKind;
import colossus.assembly.graph.CoreTier;
import colossus.assembly.graph.StructureScanner;
import colossus.kinetics.ComponentScaling;
import colossus.physics.api.*;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import colossus.physics.xpbd.XpbdBackend;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Scan → plan → simulate, the same path the game layer takes, without Minecraft. */
public class AssembledColossusTest {

    /** A four-legged table: 3x3 deck at y=5 with the core on top, hinge hips at y=4 and hinge knees at y=2. */
    static FoundationTest.World table(String hip, String knee) {
        FoundationTest.World w = new FoundationTest.World();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) w.block(x, 5, z);
        w.core(2, 6, 2);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            w.joint(c[0], 4, c[1], hip, 0);
            w.block(c[0], 3, c[1]);
            w.joint(c[0], 2, c[1], knee, 0);
            w.block(c[0], 1, c[1]).block(c[0], 0, c[1]);
        }
        return w;
    }

    record Sim(XpbdBackend backend, List<BodyHandle> bodies, List<ConstraintHandle> joints, List<BodyPlanner.JointInput> jointInputs, int root, double rootMass) {}

    static Sim build(FoundationTest.World w, boolean holdPose) {
        int cx = 2, cy = 6, cz = 2;
        StructureScanner.Result r = StructureScanner.scan(w, cx, cy, cz, CoreTier.STEEL.budget(0, 1_000_000), 100_000);
        assertTrue(r.errors().toString(), r.ok());
        XpbdBackend b = new XpbdBackend();
        b.setTerrain(TerrainQuery.flat(0, 0.8));
        List<BodyPlanner.SegmentInput> segs = new ArrayList<>();
        for (StructureScanner.SegmentPlan sp : r.segments()) {
            List<int[]> rel = new ArrayList<>();
            for (long[] c : sp.cells()) rel.add(new int[]{(int) c[0] - cx, (int) c[1] - cy, (int) c[2] - cz});
            Vec3 com = sp.mass().centerOfMass();
            segs.add(new BodyPlanner.SegmentInput(com, sp.mass().mass(), sp.mass().inertia(), rel, new Pose(new Vec3(cx, cy, cz).add(com), Quat.IDENTITY)));
        }
        List<BodyHandle> bodies = new ArrayList<>();
        for (BodyPlanner.SegmentInput s : segs) bodies.add(b.createBody(BodyPlanner.body(s, 1)));
        List<ConstraintHandle> joints = new ArrayList<>();
        List<BodyPlanner.JointInput> inputs = new ArrayList<>();
        ComponentScaling scaling = new ComponentScaling();
        for (StructureScanner.JointPlan jp : r.joints()) {
            BodyPlanner.JointInput ji = new BodyPlanner.JointInput(JointKind.byId(jp.type()), jp.parent(), jp.child(),
                    jp.anchor().sub(new Vec3(cx, cy, cz)), jp.axis(), jp.footprint());
            ConstraintHandle h = b.addConstraint(BodyPlanner.constraint(ji, segs, bodies, 9.81));
            if (holdPose) b.setMotorTarget(h, 0, BodyPlanner.holdLimit(ji, scaling));
            joints.add(h);
            inputs.add(ji);
        }
        return new Sim(b, bodies, joints, inputs, r.root().id(), r.root().mass().mass());
    }

    @Test public void heldJointsKeepATableStanding() {
        Sim s = build(table("thrust_bearing", "thrust_bearing"), true);
        double y0 = s.backend.bodyState(s.bodies.get(s.root)).pose().position().y();
        for (int i = 0; i < 180; i++) s.backend.step(1 / 60.0);
        Vec3 p = s.backend.bodyState(s.bodies.get(s.root)).pose().position();
        assertTrue("finite " + p, p.isFinite());
        assertEquals("deck height", y0, p.y(), 0.3);
        // the four hips together carry the deck's weight
        double hipSum = 0;
        for (int i = 0; i < s.joints.size(); i++) {
            if (s.jointInputs.get(i).parent() == s.root) hipSum += s.backend.constraintState(s.joints.get(i)).force();
        }
        assertEquals(s.rootMass * 9.81, hipSum, s.rootMass * 9.81 * 0.15);
        // 30 t on four 1x1 bearings is over their 50 kN rating: the strain meter must say so (bigger joints needed)
        ComponentScaling scaling = new ComponentScaling();
        double hipStrain = BodyPlanner.strain(s.jointInputs.get(0), s.backend.constraintState(s.joints.get(0)), scaling);
        assertTrue("strain " + hipStrain, hipStrain > 1 && hipStrain < 2.5);
    }

    @Test public void limpJointsLetItCollapse() {
        Sim s = build(table("thrust_bearing", "thrust_bearing"), false);
        double y0 = s.backend.bodyState(s.bodies.get(s.root)).pose().position().y();
        // nudge it so the unpowered hinges fold instead of balancing perfectly
        s.backend.applyForce(s.bodies.get(s.root), new Vec3(0, 0, 200_000), s.backend.bodyState(s.bodies.get(s.root)).pose().position());
        for (int i = 0; i < 240; i++) s.backend.step(1 / 60.0);
        double y = s.backend.bodyState(s.bodies.get(s.root)).pose().position().y();
        assertTrue("unpowered legs fold: " + y0 + " -> " + y, y < y0 - 1);
    }

    @Test public void weldedTableIsRigid() {
        Sim s = build(table("fixed_weld", "fixed_weld"), false);
        double y0 = s.backend.bodyState(s.bodies.get(s.root)).pose().position().y();
        for (int i = 0; i < 180; i++) s.backend.step(1 / 60.0);
        assertEquals(y0, s.backend.bodyState(s.bodies.get(s.root)).pose().position().y(), 0.1);
    }
}
