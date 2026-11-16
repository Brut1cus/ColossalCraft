package colossus.brain;

import colossus.brain.balance.GyroController;
import colossus.brain.balance.GyroStabilizer;
import colossus.physics.PhysicsWorld;
import colossus.physics.api.*;
import colossus.physics.math.Mat3;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import colossus.physics.xpbd.XpbdBackend;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GyroTest {
    static final double DT = 1.0 / 60;
    static final GyroStabilizer.Params P = GyroStabilizer.Params.DEFAULT;

    static GyroStabilizer.Rotor spunUp(int footprint) {
        GyroStabilizer.Rotor r = new GyroStabilizer.Rotor(footprint, P);
        r.setThrottle(1);
        for (int i = 0; i < 60 * 60 * 5; i++) r.spin(DT);
        return r;
    }

    @Test public void moreSpinAndMoreMassMeanMoreTorque() {
        GyroStabilizer.Rotor one = new GyroStabilizer.Rotor(1, P), two = new GyroStabilizer.Rotor(2, P), three = new GyroStabilizer.Rotor(3, P);
        // I = ½ m r², m ∝ N², r ∝ N  →  I ∝ N⁴
        assertEquals(0.5 * 2000 * 0.25, one.inertia, 1e-9);
        assertEquals(16, two.inertia / one.inertia, 1e-9);
        assertEquals(81, three.inertia / one.inertia, 1e-9);

        GyroStabilizer full = new GyroStabilizer(List.of(spunUp(1)), Mat3.IDENTITY, P);
        GyroStabilizer.Rotor half = new GyroStabilizer.Rotor(1, P);
        half.setThrottle(0.5);
        for (int i = 0; i < 60 * 60; i++) half.spin(DT);
        GyroStabilizer halfSpeed = new GyroStabilizer(List.of(half), Mat3.IDENTITY, P);
        GyroStabilizer big = new GyroStabilizer(List.of(spunUp(2)), Mat3.IDENTITY, P);
        assertEquals(3000, full.rotors().get(0).rpm(), 1);
        assertEquals("capacity = I·ω·gimbalRate", one.inertia * 3000 * 2 * Math.PI / 60, full.torqueCapacity(), 1);
        assertEquals("half the spin, half the torque", 0.5, halfSpeed.torqueCapacity() / full.torqueCapacity(), 1e-3);
        assertEquals("a merged 2x2 beats four 1x1s fourfold", 16, big.torqueCapacity() / full.torqueCapacity(), 1e-3);
    }

    @Test public void biggerRotorsSpinUpSlower() {
        GyroStabilizer.Rotor one = new GyroStabilizer.Rotor(1, P), two = new GyroStabilizer.Rotor(2, P);
        assertEquals(4, two.timeConstant() / one.timeConstant(), 1e-9);
        one.setThrottle(1);
        int steps = (int) Math.round(one.timeConstant() / DT);
        for (int i = 0; i < steps; i++) one.spin(DT);
        assertEquals("63% after one time constant", 1 - Math.exp(-1), one.omega() / one.maxOmega, 0.01);
        one.setThrottle(0);
        for (int i = 0; i < steps * 10; i++) one.spin(DT);
        assertTrue("spins down unpowered", one.rpm() < 1);
    }

    @Test public void unpoweredGyroDoesNothing() {
        GyroStabilizer g = new GyroStabilizer(List.of(new GyroStabilizer.Rotor(3, P)), Mat3.IDENTITY, P);
        BodyState tilted = new BodyState(new Pose(Vec3.ZERO, Quat.axisAngle(Vec3.X, 0.5)), Vec3.ZERO, new Vec3(1, 0, 0), false);
        assertEquals(Vec3.ZERO, g.update(tilted, DT));
    }

    @Test public void tiltErrorPointsBackToUpright() {
        assertEquals(0, GyroStabilizer.tiltError(Quat.IDENTITY).length(), 1e-12);
        Vec3 e = GyroStabilizer.tiltError(Quat.axisAngle(Vec3.X, 0.3));
        assertEquals(-0.3, e.x(), 1e-9);
        assertEquals(0, e.y(), 1e-9);
        // yaw alone is not an error
        assertEquals(0, GyroStabilizer.tiltError(Quat.axisAngle(Vec3.Y, 1.2)).length(), 1e-9);
        assertEquals(Math.PI, GyroStabilizer.tiltError(Quat.axisAngle(Vec3.Z, Math.PI)).length(), 1e-6);
    }

    static BodyHandle box(PhysicsBackend b, double mass, Vec3 size, Pose pose) {
        Vec3 h = size.mul(0.5);
        return b.createBody(BodyDesc.dynamic(MassProperties.solidBox(mass, size), pose, List.of(new Box(h.neg(), h))));
    }

    @Test public void angularMomentumIsConserved() {
        // zero gravity, spinning tumbling box: the gyro stops it, and what the hull loses the gimbal stores
        XpbdBackend b = new XpbdBackend();
        b.setGravity(Vec3.ZERO);
        Vec3 size = new Vec3(2, 3, 2);
        MassProperties mp = MassProperties.solidBox(20_000, size);
        BodyHandle h = box(b, 20_000, size, Pose.IDENTITY);
        // spin it about X with a short torque burst
        for (int i = 0; i < 6; i++) { b.applyTorque(h, new Vec3(400_000, 0, 0)); b.step(DT); }
        Vec3 l0 = mp.inertia().transform(b.bodyState(h).angularVelocity());
        GyroStabilizer g = new GyroStabilizer(List.of(spunUp(2)), mp.inertia(), P);
        PhysicsWorld w = new PhysicsWorld(b, 60, false, "test");
        w.addController(new GyroController(Map.of(h, g)));
        w.stepNow(5);
        double angle0 = GyroStabilizer.tiltError(b.bodyState(h).pose().rotation()).length();
        assertTrue("it was tumbling", angle0 > 0.01);
        w.stepNow(60 * 6);
        BodyState s = b.bodyState(h);
        assertTrue("tumble stopped: " + s.angularVelocity(), s.angularVelocity().length() < 0.02);
        Vec3 hull = s.pose().rotation().rotate(mp.inertia().transform(s.pose().rotation().inverseRotate(s.angularVelocity())));
        Vec3 total = hull.add(g.storedMomentum());
        // the gimbal also re-centres into the hull; with nothing to push against, total stays the hull's start momentum
        assertEquals("total angular momentum kept", l0.length(), total.length(), l0.length() * 0.03);
    }

    @Test public void rightsATiltedBodyInZeroG() {
        XpbdBackend b = new XpbdBackend();
        b.setGravity(Vec3.ZERO);
        Vec3 size = new Vec3(2, 4, 2);
        MassProperties mp = MassProperties.solidBox(30_000, size);
        BodyHandle h = box(b, 30_000, size, new Pose(Vec3.ZERO, Quat.axisAngle(new Vec3(1, 0, 1).normalize(), 0.4)));
        GyroStabilizer g = new GyroStabilizer(List.of(spunUp(3)), mp.inertia(), P);
        PhysicsWorld w = new PhysicsWorld(b, 60, false, "test");
        w.addController(new GyroController(Map.of(h, g)));
        w.stepNow(60 * 5);
        double tilt = GyroStabilizer.tiltError(b.bodyState(h).pose().rotation()).length();
        assertTrue("back upright: " + Math.toDegrees(tilt) + "°", tilt < Math.toRadians(1));
    }

    @Test public void saturatesUnderASustainedTorque() {
        // a steady push bigger than the momentum budget can absorb forever: holds at first, then gives up
        GyroStabilizer g = new GyroStabilizer(List.of(spunUp(1)), Mat3.diagonal(1e5, 1e5, 1e5), P);
        double cap = g.torqueCapacity();
        BodyState leaning = new BodyState(new Pose(Vec3.ZERO, Quat.axisAngle(Vec3.Z, 0.2)), Vec3.ZERO, Vec3.ZERO, false);
        Vec3 first = g.update(leaning, DT);
        assertEquals("pushes at full capacity", cap, first.length(), cap * 1e-6);
        double seconds = 0;
        while (g.saturation() < 0.999 && seconds < 60) { g.update(leaning, DT); seconds += DT; }
        // dh/dt = cap − d·g·h with cap = g·H  →  h reaches H at t = −ln(1 − d) / (d·g) ≈ 1.05 s
        double d = P.desaturation(), gr = P.gimbalRate();
        assertEquals(-Math.log(1 - d) / (d * gr), seconds, 0.05);
        for (int i = 0; i < 60; i++) g.update(leaning, DT);
        Vec3 after = g.update(leaning, DT);
        assertTrue("saturated: only the re-centring push is left, " + after.length(), after.length() < cap * 1e-6 + 1e-9);
    }

    @Test public void unitsMergeOnlyFilledSquares() {
        List<int[]> cells = List.of(
                new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{0, 0, 1}, new int[]{1, 0, 1},   // 2x2 slab
                new int[]{5, 0, 0},                                                             // lone block
                new int[]{8, 0, 0}, new int[]{9, 0, 0}, new int[]{10, 0, 0},                    // 1x3 line: three 1x1
                new int[]{0, 3, 0}, new int[]{0, 4, 0}, new int[]{0, 5, 0},
                new int[]{0, 3, 1}, new int[]{0, 4, 1}, new int[]{0, 5, 1},
                new int[]{0, 3, 2}, new int[]{0, 4, 2}, new int[]{0, 5, 2});                    // vertical 3x3 slab
        List<GyroStabilizer.Unit> units = GyroStabilizer.units(cells);
        assertEquals(6, units.size());
        assertEquals(1, units.stream().filter(u -> u.footprint() == 2 && u.cells().length == 4).count());
        assertEquals(1, units.stream().filter(u -> u.footprint() == 3 && u.cells().length == 9).count());
        assertEquals(4, units.stream().filter(u -> u.footprint() == 1).count());
    }

    @Test public void combinedInertiaUsesParallelAxis() {
        Mat3 i = GyroStabilizer.combinedInertia(List.of(Mat3.ZERO, Mat3.ZERO), List.of(2.0, 2.0),
                List.of(new Vec3(1, 0, 0), new Vec3(-1, 0, 0)), Vec3.ZERO);
        assertEquals(0, i.get(0, 0), 1e-12);
        assertEquals(4, i.get(1, 1), 1e-12);
        assertEquals(4, i.get(2, 2), 1e-12);
    }

    /**
     * Regression: a light torso on a free ball joint, while the gyro believes the machine is 60× its inertia. Gains
     * sized for the whole machine overshoot every physics step and spin it wildly; capped gains hold it upright.
     */
    @Test public void looselyCoupledSegmentStaysStable() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle base = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        Vec3 size = new Vec3(3, 2, 1);
        double mass = 6 * 7800;
        MassProperties mp = MassProperties.solidBox(mass, size);
        Quat tilt = Quat.axisAngle(Vec3.X, Math.toRadians(5));
        BodyHandle torso = b.createBody(BodyDesc.dynamic(mp, new Pose(tilt.rotate(new Vec3(0, 1, 0)), tilt), List.of(new Box(size.mul(-0.5), size.mul(0.5)))));
        b.addConstraint(ConstraintDesc.of(JointType.BALL, base, torso, Vec3.ZERO, new Vec3(0, -1, 0), Vec3.Y, Vec3.Y).withCone(Math.PI, Math.PI));
        GyroStabilizer g = new GyroStabilizer(List.of(spunUp(3)), mp.inertia(), mp.inertia().scale(60), P);
        assertEquals("stiffness uses the whole machine", 60, g.stiffnessRatio(DT), 1e-9);
        assertTrue("damping capped: " + g.dampingRatio(DT), g.dampingRatio(DT) < 10);
        PhysicsWorld w = new PhysicsWorld(b, 60, false, "test");
        w.addController(new GyroController(Map.of(torso, g)));
        double worst = 0;
        for (int i = 0; i < 60 * 6; i++) {
            w.stepNow(1);
            worst = Math.max(worst, b.bodyState(torso).angularVelocity().length());
        }
        double tiltDeg = Math.toDegrees(GyroStabilizer.tiltError(b.bodyState(torso).pose().rotation()).length());
        assertTrue("upright: " + tiltDeg + "°", tiltDeg < 1);
        assertTrue("no thrashing: peak " + worst + " rad/s", worst < 0.5);
    }

    /** A tall tower tipped 8° on flat ground: it falls over on its own, and a spun-up gyro stands it back up. */
    @Test public void gyroKeepsATippedTowerStanding() {
        double[] finalTilt = new double[2];
        for (int k = 0; k < 2; k++) {
            XpbdBackend b = new XpbdBackend();
            b.setTerrain(TerrainQuery.flat(0, 0.8));
            Vec3 size = new Vec3(1, 8, 1);
            double mass = 8 * 7800;
            MassProperties mp = MassProperties.solidBox(mass, size);
            Quat tilt = Quat.axisAngle(Vec3.Z, Math.toRadians(8));
            Vec3 base = new Vec3(0, 0, 0);
            Pose pose = new Pose(base.add(tilt.rotate(new Vec3(0, 4, 0))).add(new Vec3(0, 0.02, 0)), tilt);
            BodyHandle h = b.createBody(BodyDesc.dynamic(mp, pose, List.of(new Box(size.mul(-0.5), size.mul(0.5)))).withFriction(0.8));
            PhysicsWorld w = new PhysicsWorld(b, 60, false, "test");
            if (k == 1) {
                // effective inertia about the CoM; the pivot is the foot, so add m·d² like the game does for a whole machine
                Mat3 about = GyroStabilizer.combinedInertia(List.of(mp.inertia()), List.of(mass), List.of(new Vec3(0, 4, 0)), Vec3.ZERO);
                w.addController(new GyroController(Map.of(h, new GyroStabilizer(List.of(spunUp(3)), mp.inertia(), about, P))));
            }
            w.stepNow(60 * 6);
            finalTilt[k] = Math.toDegrees(GyroStabilizer.tiltError(b.bodyState(h).pose().rotation()).length());
        }
        assertTrue("without a gyro it falls: " + finalTilt[0] + "°", finalTilt[0] > 45);
        assertTrue("with a gyro it stands: " + finalTilt[1] + "°", finalTilt[1] < 2);
    }
}
