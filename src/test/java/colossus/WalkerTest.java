package colossus;

import colossus.assembly.BodyPlanner;
import colossus.brain.ik.IkChain;
import colossus.brain.ik.IkSolver;
import colossus.brain.walker.LegIk;
import colossus.brain.walker.Limb;
import colossus.brain.walker.LimbDiscovery;
import colossus.brain.walker.WalkerBrain;
import colossus.brain.walker.WalkerBrainAccess;
import colossus.AssembledColossusTest.Sim;
import colossus.kinetics.ComponentScaling;
import colossus.physics.PhysicsWorld;
import colossus.physics.api.BodyState;
import colossus.physics.api.TerrainQuery;
import colossus.physics.math.Vec3;
import colossus.physics.math.Quat;
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

    /**
     * A quadruped built the way a player should build one: a 6x6 deck (4.3 t) on 2x2 legs with 2x2 ball hips, hinge
     * knees and universal ankles that keep the 2x2 feet flat. Merged 2x2 joints have 8x the torque of 1x1.
     */
    static Sim quadruped() { return quadruped(p -> p[1] >= 7 ? 120 : 25); }

    /** The same quadruped with a per-block mass function (x, y, z) → kg: deck y = 7, core y = 8, hips 6, knees 3, ankles 1. */
    static Sim quadruped(java.util.function.ToDoubleFunction<int[]> mass) {
        FoundationTest.World w = new FoundationTest.World();
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) w.block(x, 7, z);
        w.core(2, 8, 2);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                int x = c[0] + dx, z = c[1] + dz;
                w.joint(x, 6, z, "ball_bearing", 1);
                w.block(x, 5, z).block(x, 4, z);
                w.joint(x, 3, z, "thrust_bearing", 0);
                w.block(x, 2, z);
                w.joint(x, 1, z, "universal_joint", 1);
                w.block(x, 0, z);
            }
        }
        w.massAt = mass;
        return AssembledColossusTest.build(w, false, 2, 8, 2);
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

    static WalkerBrain brain(Sim s) { return brain(s, 1, WalkerBrain.Params.DEFAULT); }

    /** The brain wired the way the game will wire it: every driven joint with its bodies, anchor, axis and rating. */
    static WalkerBrain brain(Sim s, double torqueScale, WalkerBrain.Params params) {
        ComponentScaling scaling = new ComponentScaling();
        List<WalkerBrain.Leg> legs = new ArrayList<>();
        for (Limb l : LimbDiscovery.discover(s.segments(), s.jointInputs(), s.root(), null, null)) {
            WalkerBrain.Drive[] drives = new WalkerBrain.Drive[l.joints().length];
            for (int k = 0; k < drives.length; k++) {
                BodyPlanner.JointInput ji = s.jointInputs().get(l.joints()[k]);
                drives[k] = new WalkerBrain.Drive(s.joints().get(l.joints()[k]), ji.kind().type, torqueScale * BodyPlanner.holdLimit(ji, scaling),
                        s.bodies().get(ji.parent()), s.bodies().get(ji.child()), ji.anchorRel().sub(s.segments().get(ji.parent()).comRel()), ji.axisVector());
            }
            WalkerBrain.Drive ankle = null;
            if (l.hasAnkle()) {
                BodyPlanner.JointInput ji = s.jointInputs().get(l.ankle());
                ankle = new WalkerBrain.Drive(s.joints().get(l.ankle()), ji.kind().type, torqueScale * BodyPlanner.holdLimit(ji, scaling),
                        s.bodies().get(ji.parent()), s.bodies().get(ji.child()), ji.anchorRel().sub(s.segments().get(ji.parent()).comRel()), ji.axisVector());
            }
            legs.add(new WalkerBrain.Leg(l, drives, ankle, s.bodies().get(l.footSegment()), s.segments().get(l.footSegment()).comRel()));
        }
        double mass = 0;
        for (BodyPlanner.SegmentInput seg : s.segments()) mass += seg.mass();
        return new WalkerBrain(s.bodies().get(s.root()), s.segments().get(s.root()).comRel(), mass, legs, TerrainQuery.flat(0, 0.8), params);
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
        // the crawl's first step from standing is short (the body has only just started moving): a real step, not a shuffle
        assertTrue("stepped forward: " + stride + " m", stride > 0.4);
        assertTrue("foot really there: planted " + stepper.planted() + " vs " + footWorld(s, stepper.limb),
                footWorld(s, stepper.limb).distance(stepper.planted()) < 0.3);
        assertTrue("upright during the step: worst " + worstTilt + "°", worstTilt < 5);
    }

    static double tiltDeg(Sim s) {
        return Math.toDegrees(colossus.brain.balance.GyroStabilizer.tiltError(s.backend().bodyState(s.bodies().get(s.root())).pose().rotation()).length());
    }

    static Vec3 rootPos(Sim s) { return s.backend().bodyState(s.bodies().get(s.root())).pose().position(); }

    /** The quadruped with ankles walks forward steadily and stays upright. */
    @Test public void quadrupedWalksForwardUpright() {
        Sim s = quadruped();
        WalkerBrain b = brain(s);
        PhysicsWorld w = new PhysicsWorld(s.backend(), 60, false, "test");
        w.addController(b);
        w.stepNow(60);
        Vec3 p0 = rootPos(s);
        b.command(0.5, 0, 0);
        double worst = 0;
        for (int i = 0; i < 60 * 40; i++) { w.stepNow(1); worst = Math.max(worst, tiltDeg(s)); }
        Vec3 p = rootPos(s);
        assertTrue("walked forward: " + (p.z() - p0.z()) + " m in 40 s", p.z() - p0.z() > 15);
        assertTrue("kept its line: " + (p.x() - p0.x()), Math.abs(p.x() - p0.x()) < 2);
        assertTrue("upright: worst tilt " + worst + "°", worst < 10);
    }

    /** A yaw command turns the machine on the spot. */
    @Test public void quadrupedTurnsOnTheSpot() {
        Sim s = quadruped();
        WalkerBrain b = brain(s);
        PhysicsWorld w = new PhysicsWorld(s.backend(), 60, false, "test");
        w.addController(b);
        w.stepNow(60);
        Vec3 p0 = rootPos(s);
        double prev = -s.backend().bodyState(s.bodies().get(s.root())).pose().rotation().yaw(), turned = 0;
        b.command(0, 0, 0.15);
        double worst = 0;
        for (int i = 0; i < 60 * 30; i++) {
            w.stepNow(1);
            worst = Math.max(worst, tiltDeg(s));
            double h = -s.backend().bodyState(s.bodies().get(s.root())).pose().rotation().yaw();
            turned += Math.toDegrees(LegIk.wrap(h - prev)); // unwrapped: it may turn past 180°
            prev = h;
        }
        Vec3 p = rootPos(s);
        assertTrue("turned left: " + turned + "°", turned > 60);
        assertTrue("on the spot: drifted " + Math.hypot(p.x() - p0.x(), p.z() - p0.z()) + " m", Math.hypot(p.x() - p0.x(), p.z() - p0.z()) < 2);
        assertTrue("upright: worst tilt " + worst + "°", worst < 10);
    }

    /** When the command stops, the machine settles with every foot down and stays put. */
    @Test public void quadrupedStopsWhenTheCommandStops() {
        Sim s = quadruped();
        WalkerBrain b = brain(s);
        PhysicsWorld w = new PhysicsWorld(s.backend(), 60, false, "test");
        w.addController(b);
        w.stepNow(60);
        b.command(0.5, 0, 0);
        w.stepNow(60 * 8);
        b.command(0, 0, 0);
        w.stepNow(60 * 3);
        Vec3 a = rootPos(s);
        w.stepNow(60 * 5);
        Vec3 c = rootPos(s);
        assertTrue("stays put: moved " + c.sub(a), Math.hypot(c.x() - a.x(), c.z() - a.z()) < 0.3);
        for (WalkerBrain.Leg l : b.legs()) assertTrue("all feet down", l.inStance());
        assertTrue("upright: " + tiltDeg(s) + "°", tiltDeg(s) < 5);
    }

    /** Discovery spots ankles: the IK chain stops at the universal joint, which then keeps the foot level. */
    @Test public void discoversAnkles() {
        Sim s = quadruped();
        List<Limb> limbs = LimbDiscovery.discover(s.segments(), s.jointInputs(), s.root(), null, null);
        assertEquals(4, limbs.size());
        for (Limb l : limbs) {
            assertTrue(l.hasAnkle());
            assertEquals("hip and knee in the chain", 2, l.chain().size());
            assertEquals("foot 1.5 m below the ankle", -1.5, l.footBelowAnkle().y(), 1e-9);
        }
    }

    /** Ankle targets carry no twist about the ankle's through axis (a universal joint cannot twist). */
    @Test public void swingOnlyRemovesTwist() {
        Quat swing = Quat.axisAngle(Vec3.X, 0.4);
        Quat twist = Quat.axisAngle(Vec3.Y, 0.7);
        Quat q = swing.mul(twist);
        Quat s = WalkerBrainAccess.swingOnly(q, Vec3.Y);
        assertEquals("twist gone", 0, s.mul(swing.conjugate()).angle(), 1e-9);
        assertEquals("pure swing kept", 0, WalkerBrainAccess.swingOnly(swing, Vec3.Y).mul(swing.conjugate()).angle(), 1e-9);
    }
}
