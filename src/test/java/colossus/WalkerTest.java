package colossus;

import colossus.assembly.BodyPlanner;
import colossus.brain.ik.IkChain;
import colossus.brain.ik.IkSolver;
import colossus.brain.walker.LegIk;
import colossus.brain.walker.Limb;
import colossus.brain.walker.LimbDiscovery;
import colossus.brain.walker.WalkerBrain;
import colossus.AssembledColossusTest.Sim;
import colossus.kinetics.ComponentScaling;
import colossus.physics.PhysicsWorld;
import colossus.physics.api.BodyState;
import colossus.physics.api.TerrainQuery;
import colossus.physics.math.Vec3;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class WalkerTest {

    static IkChain ballHingeLeg() {
        // hip ball (cone axis down), thigh 2 m down, knee hinge about X, shin 2 m down
        return IkChain.of(IkChain.Link.ball(new Vec3(0, -1, 0), new Vec3(0, -2, 0), Math.toRadians(60)),
                IkChain.Link.hinge(Vec3.X, new Vec3(0, -2, 0), Math.toRadians(-120), Math.toRadians(120)));
    }

    @Test public void ballHingeIkHitsReachableTargets() {
        IkChain leg = ballHingeLeg();
        Random rnd = new Random(7);
        IkChain.Pose warm = new IkChain.Pose(2);
        for (int i = 0; i < 200; i++) {
            // inside what the limits allow: knee ±120° keeps the foot at least 2 m away, the hip cone is 60°
            Vec3 t = new Vec3(rnd.nextDouble() * 1.4 - 0.7, -2.6 - rnd.nextDouble() * 1.2, rnd.nextDouble() * 1.4 - 0.7);
            IkSolver.Result r = LegIk.solve(leg, warm, t);
            assertTrue("target " + t + " missed by " + r.error(), r.error() < 0.01);
            warm = r.pose();
        }
    }

    @Test public void straightLegBendsTowardThePole() {
        IkChain leg = ballHingeLeg();
        leg.pole = new Vec3(0, 0, 1);
        IkSolver.Result r = LegIk.solve(leg, new IkChain.Pose(2), new Vec3(0, -3.2, 0));
        assertTrue(r.error() < 0.01);
        Vec3 knee = r.pose().ball[0].rotate(new Vec3(0, -2, 0));
        assertTrue("knee forward: " + knee, knee.z() > 0.5);
    }

    @Test public void outOfReachTargetIsFlagged() {
        IkSolver.Result r = LegIk.solve(ballHingeLeg(), new IkChain.Pose(2), new Vec3(0, -6, 0));
        assertTrue(r.outOfReach());
        assertEquals("stretched toward it", -4, r.effector().y(), 0.01);
    }


    /**
     * The test table built like a walker should be: a 3 t deck (120 kg blocks, core included) on light legs (40 kg
     * blocks, about 0.24 t each). Legs as heavy as the body kick it around as they swing, and a heavier body would ask
     * more of the 1x1 hips than their 20 kN·m rating.
     */
    static Sim lightTable() {
        FoundationTest.World w = AssembledColossusTest.table("ball_bearing", "thrust_bearing");
        w.massAt = p -> p[1] >= 5 ? 120 : 40;
        return AssembledColossusTest.build(w, false);
    }

    @Test public void discoversFourBallHingeLegs() {
        var s = AssembledColossusTest.build(AssembledColossusTest.table("ball_bearing", "thrust_bearing"), true);
        List<Limb> limbs = LimbDiscovery.discover(s.segments(), s.jointInputs(), s.root(), null, null);
        assertEquals(4, limbs.size());
        for (Limb l : limbs) {
            assertEquals(2, l.chain().size());
            assertEquals(IkChain.Kind.BALL, l.chain().links.get(0).kind());
            assertEquals(IkChain.Kind.HINGE, l.chain().links.get(1).kind());
            assertEquals("hip to foot", 4.5, l.restLength(), 1e-9);
        }
    }

    static WalkerBrain brain(Sim s) {
        ComponentScaling scaling = new ComponentScaling();
        List<WalkerBrain.Leg> legs = new ArrayList<>();
        for (Limb l : LimbDiscovery.discover(s.segments(), s.jointInputs(), s.root(), null, null)) {
            WalkerBrain.Drive[] drives = new WalkerBrain.Drive[l.joints().length];
            for (int k = 0; k < drives.length; k++) {
                BodyPlanner.JointInput ji = s.jointInputs().get(l.joints()[k]);
                drives[k] = new WalkerBrain.Drive(s.joints().get(l.joints()[k]), ji.kind().type, BodyPlanner.holdLimit(ji, scaling));
            }
            legs.add(new WalkerBrain.Leg(l, drives, s.bodies().get(l.footSegment()), s.segments().get(l.footSegment()).comRel()));
        }
        return new WalkerBrain(s.bodies().get(s.root()), s.segments().get(s.root()).comRel(), legs, TerrainQuery.flat(0, 0.8), WalkerBrain.Params.DEFAULT);
    }

    @Test public void standsStillWithoutACommand() {
        var s = lightTable();
        WalkerBrain b = brain(s);
        PhysicsWorld w = new PhysicsWorld(s.backend(), 60, false, "test");
        w.addController(b);
        Vec3 p0 = s.backend().bodyState(s.bodies().get(s.root())).pose().position();
        w.stepNow(60 * 5);
        BodyState st = s.backend().bodyState(s.bodies().get(s.root()));
        assertTrue("stays put: " + st.pose().position().sub(p0), new Vec3(st.pose().position().x() - p0.x(), 0, st.pose().position().z() - p0.z()).length() < 0.3);
        assertTrue("upright: " + Math.toDegrees(st.pose().rotation().angle()), Math.toDegrees(st.pose().rotation().angle()) < 5);
        assertTrue("crouched a little, not collapsed: " + (p0.y() - st.pose().position().y()), p0.y() - st.pose().position().y() < 1.2);
    }

    /** Where a leg's foot point really is, from the physics bodies. */
    static Vec3 footWorld(Sim s, Limb l) {
        var fs = s.segments().get(l.footSegment());
        return s.backend().bodyState(s.bodies().get(l.footSegment())).pose().toWorld(l.footRest().sub(fs.comRel()));
    }

    /** The servos put each foot exactly where the IK says (frames and signs agree end to end). */
    @Test public void servosPutFeetWhereTheIkSays() {
        Sim s = lightTable();
        s.backend().setGravity(Vec3.ZERO);
        s.backend().setTerrain(TerrainQuery.NONE);
        Vec3 com = s.segments().get(s.root()).comRel();
        List<Limb> limbs = LimbDiscovery.discover(s.segments(), s.jointInputs(), s.root(), null, null);
        List<Vec3> want = new ArrayList<>();
        for (Limb l : limbs) {
            l.chain().pole = new Vec3(0, 0, 1);
            Vec3 target = l.footRest().sub(com).add(new Vec3(0.3, 0.6, 0.5)).sub(l.hip().sub(com)); // raised, forward, out
            IkSolver.Result r = LegIk.solve(l.chain(), new IkChain.Pose(2), target);
            assertTrue(r.reached());
            for (int k = 0; k < 2; k++) {
                var h = s.joints().get(l.joints()[k]);
                if (s.jointInputs().get(l.joints()[k]).kind().type.orientationMotor()) s.backend().setMotorOrientation(h, r.pose().ball[k], 2e5);
                else s.backend().setMotorTarget(h, r.pose().value[k], 2e5);
            }
            want.add(target);
        }
        for (int i = 0; i < 600; i++) s.backend().step(1 / 60.0);
        var root = s.backend().bodyState(s.bodies().get(s.root())).pose();
        for (int i = 0; i < limbs.size(); i++) {
            Vec3 got = root.toLocal(footWorld(s, limbs.get(i))).sub(limbs.get(i).hip().sub(com));
            assertTrue("leg " + i + ": wanted " + want.get(i) + ", foot at " + got, got.distance(want.get(i)) < 0.02);
        }
    }

    /** On a walk command the first leg swings, lands near its foothold, and the body stays upright meanwhile. */
    @Test public void firstStepLandsNearItsFoothold() {
        Sim s = lightTable();
        WalkerBrain b = brain(s);
        PhysicsWorld w = new PhysicsWorld(s.backend(), 60, false, "test");
        w.addController(b);
        w.stepNow(60);
        b.command(0.4, 0, 0);
        WalkerBrain.Leg stepper = null;
        Vec3 liftedFrom = null;
        double worstTilt = 0;
        for (int i = 0; i < 60 * 4 && (stepper == null || !stepper.inStance()); i++) {
            w.stepNow(1);
            worstTilt = Math.max(worstTilt, Math.toDegrees(s.backend().bodyState(s.bodies().get(s.root())).pose().rotation().angle()));
            if (stepper == null) for (WalkerBrain.Leg l : b.legs()) if (!l.inStance()) { stepper = l; liftedFrom = l.planted(); }
        }
        assertNotNull("no leg lifted", stepper);
        assertTrue("the step finished", stepper.inStance());
        double stride = stepper.planted().z() - liftedFrom.z();
        assertTrue("stepped forward: " + stride + " m", stride > 0.8);
        assertTrue("foot really there: planted " + stepper.planted() + " vs " + footWorld(s, stepper.limb),
                footWorld(s, stepper.limb).distance(stepper.planted()) < 0.3);
        assertTrue("upright during the step: worst " + worstTilt + "°", worstTilt < 5);
    }
}
