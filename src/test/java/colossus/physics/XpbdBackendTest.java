package colossus.physics;

import colossus.physics.api.*;
import colossus.physics.forces.Forces;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import colossus.physics.xpbd.XpbdBackend;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class XpbdBackendTest {
    static final double DT = 1.0 / 60;

    static BodyHandle cube(PhysicsBackend b, double mass, Vec3 pos) {
        Box box = new Box(new Vec3(-0.5, -0.5, -0.5), new Vec3(0.5, 0.5, 0.5));
        return b.createBody(BodyDesc.dynamic(MassProperties.solidBox(mass, new Vec3(1, 1, 1)), new Pose(pos, Quat.IDENTITY), List.of(box)));
    }

    @Test public void freeFallMatchesKinematics() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle h = cube(b, 1000, new Vec3(0, 100, 0));
        for (int i = 0; i < 60; i++) b.step(DT);
        double y = b.bodyState(h).pose().position().y();
        assertEquals(100 - 0.5 * 9.81, y, 0.15);
        assertEquals(-9.81, b.bodyState(h).linearVelocity().y(), 0.05);
    }

    @Test public void cubeRestsOnTerrain() {
        XpbdBackend b = new XpbdBackend();
        b.setTerrain(TerrainQuery.flat(64, 0.7));
        BodyHandle h = cube(b, 1600, new Vec3(0.3, 67, 0.2));
        for (int i = 0; i < 240; i++) b.step(DT);
        BodyState s = b.bodyState(h);
        assertEquals(64.5, s.pose().position().y(), 0.03);
        assertTrue("should settle: " + s.linearVelocity(), s.linearVelocity().length() < 0.05);
    }

    @Test public void frictionStopsSlidingOnStoneButNotOnIce() {
        double[] mus = {0.7, 0.02};
        double[] travelled = new double[2];
        for (int k = 0; k < 2; k++) {
            XpbdBackend b = new XpbdBackend();
            double mu = mus[k];
            b.setTerrain(TerrainQuery.flat(0, mu));
            Box box = new Box(new Vec3(-0.5, -0.5, -0.5), new Vec3(0.5, 0.5, 0.5));
            BodyHandle h = b.createBody(BodyDesc.dynamic(MassProperties.solidBox(1000, new Vec3(1, 1, 1)), new Pose(new Vec3(0, 0.5, 0), Quat.IDENTITY), List.of(box)).withFriction(mu));
            for (int i = 0; i < 30; i++) b.step(DT);
            // shove: 4 m/s along X via a short force burst
            for (int i = 0; i < 6; i++) { b.applyForce(h, new Vec3(1000 * 4 / (6 * DT), 0, 0), b.bodyState(h).pose().position()); b.step(DT); }
            for (int i = 0; i < 120; i++) b.step(DT);
            travelled[k] = b.bodyState(h).pose().position().x();
        }
        assertTrue("stone stops quickly: " + travelled[0], travelled[0] < 3);
        assertTrue("ice slides far: " + travelled[1], travelled[1] > 6);
    }

    @Test public void hingePendulumKeepsLengthAndAxis() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle anchor = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle bob = cube(b, 500, new Vec3(3, 0, 0));
        b.addConstraint(ConstraintDesc.of(JointType.HINGE, anchor, bob, Vec3.ZERO, new Vec3(-3, 0, 0), Vec3.Z, Vec3.Z));
        double minY = 0;
        for (int i = 0; i < 120; i++) {
            b.step(DT);
            Vec3 p = b.bodyState(bob).pose().position();
            assertEquals("pendulum length", 3, p.length(), 0.02);
            assertEquals("stays in the XY plane", 0, p.z(), 0.01);
            minY = Math.min(minY, p.y());
        }
        assertTrue("it swung down: " + minY, minY < -2.9);
    }

    @Test public void hingeLimitHolds() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle anchor = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle bob = cube(b, 500, new Vec3(3, 0, 0));
        // limit the swing to ±30°: the bob must stop well above the bottom
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.HINGE, anchor, bob, Vec3.ZERO, new Vec3(-3, 0, 0), Vec3.Z, Vec3.Z)
                .withLimits(Math.toRadians(-30), Math.toRadians(30)));
        for (int i = 0; i < 180; i++) b.step(DT);
        double angle = b.constraintState(c).position();
        assertTrue("angle " + Math.toDegrees(angle), angle > Math.toRadians(-31.5) && angle < Math.toRadians(31.5));
        assertTrue(b.bodyState(bob).pose().position().y() > -1.6);
    }

    @Test public void motorDrivesHingeToTarget() {
        XpbdBackend b = new XpbdBackend();
        b.setGravity(Vec3.ZERO);
        BodyHandle base = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle arm = cube(b, 200, new Vec3(2, 0, 0));
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.HINGE, base, arm, Vec3.ZERO, new Vec3(-2, 0, 0), Vec3.Y, Vec3.Y));
        b.setMotorTarget(c, Math.toRadians(60), 20_000);
        for (int i = 0; i < 240; i++) b.step(DT);
        assertEquals(60, Math.toDegrees(b.constraintState(c).position()), 2);
    }

    @Test public void weakMotorCannotLiftHeavyArm() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle base = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle arm = cube(b, 2000, new Vec3(2, 0, 0));
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.HINGE, base, arm, Vec3.ZERO, new Vec3(-2, 0, 0), Vec3.Z, Vec3.Z));
        // needs 2000 kg * 9.81 * 2 m ≈ 39 kN·m to hold level; give it 5 kN·m
        b.setMotorTarget(c, 0, 5_000);
        for (int i = 0; i < 240; i++) b.step(DT);
        assertTrue("arm sags", b.bodyState(arm).pose().position().y() < -1);
    }

    // ---- orientation servos (ball, universal)

    static double angleFrom(Quat q, Quat target) { return target.mul(q.conjugate()).angle(); }

    @Test public void ballServoHoldsACantileverAgainstGravity() {
        double[] sag = new double[2];
        for (int k = 0; k < 2; k++) {
            XpbdBackend b = new XpbdBackend();
            BodyHandle base = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
            BodyHandle arm = cube(b, 500, new Vec3(2, 0, 0));
            ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.BALL, base, arm, Vec3.ZERO, new Vec3(-2, 0, 0), Vec3.X, Vec3.X));
            // needs 500 kg · 9.81 · 2 m ≈ 9.8 kN·m
            if (k == 1) b.setMotorOrientation(c, Quat.IDENTITY, 40_000);
            for (int i = 0; i < 240; i++) b.step(DT);
            sag[k] = -b.bodyState(arm).pose().position().y();
        }
        assertTrue("a free ball joint swings down: " + sag[0], sag[0] > 1.5);
        assertTrue("a held one stays level: " + sag[1], sag[1] < 0.3);
    }

    @Test public void ballServoDrivesToATarget() {
        XpbdBackend b = new XpbdBackend();
        b.setGravity(Vec3.ZERO);
        BodyHandle base = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle arm = cube(b, 300, new Vec3(0, -2, 0));
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.BALL, base, arm, Vec3.ZERO, new Vec3(0, 2, 0), new Vec3(0, -1, 0), new Vec3(0, -1, 0)));
        Quat target = Quat.axisAngle(new Vec3(1, 0, 1).normalize(), Math.toRadians(35)).mul(Quat.axisAngle(Vec3.Y, Math.toRadians(20)));
        b.setMotorOrientation(c, target, 30_000);
        for (int i = 0; i < 300; i++) b.step(DT);
        double off = Math.toDegrees(angleFrom(b.bodyState(arm).pose().rotation(), target));
        assertTrue("reached the swing+twist target, off by " + off + "°", off < 2);
        Vec3 tip = b.bodyState(arm).pose().position();
        assertEquals("stays on the joint", 2, tip.length(), 0.02);
    }

    @Test public void weakBallServoSags() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle base = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle arm = cube(b, 2000, new Vec3(2, 0, 0));
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.BALL, base, arm, Vec3.ZERO, new Vec3(-2, 0, 0), Vec3.X, Vec3.X));
        b.setMotorOrientation(c, Quat.IDENTITY, 5_000); // needs ~39 kN·m
        for (int i = 0; i < 240; i++) b.step(DT);
        assertTrue("torque clamp respected", b.bodyState(arm).pose().position().y() < -1);
    }

    @Test public void ballJointConeLimit() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle anchor = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle leg = cube(b, 300, new Vec3(2, 0, 0));
        b.addConstraint(ConstraintDesc.of(JointType.BALL, anchor, leg, Vec3.ZERO, new Vec3(-2, 0, 0), Vec3.X, Vec3.X)
                .withCone(Math.toRadians(20), Math.PI));
        for (int i = 0; i < 240; i++) b.step(DT);
        Vec3 dir = b.bodyState(leg).pose().position().normalize();
        double angle = Math.toDegrees(Math.acos(dir.dot(Vec3.X)));
        assertTrue("cone angle " + angle, angle < 22);
    }

    @Test public void sliderAndSpringSuspensionSettles() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle chassis = b.createBody(BodyDesc.fixed(new Pose(new Vec3(0, 10, 0), Quat.IDENTITY), List.of()));
        BodyHandle wheel = cube(b, 100, new Vec3(0, 9, 0));
        double k = 20_000, rest = -1;
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.SPRING_DAMPER, chassis, wheel, Vec3.ZERO, Vec3.ZERO, Vec3.Y, Vec3.Y)
                .withSpring(k, 2_000, rest).withLimits(-2, 0));
        for (int i = 0; i < 300; i++) b.step(DT);
        // static deflection = m g / k
        assertEquals(rest - 100 * 9.81 / k, b.constraintState(c).position(), 0.01);
        assertEquals(0, b.bodyState(wheel).pose().position().x(), 1e-6);
    }

    @Test public void fixedJointCarriesLoadAndReportsForce() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle anchor = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle load = cube(b, 1000, new Vec3(0, -1, 0));
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.FIXED, anchor, load, Vec3.ZERO, new Vec3(0, 1, 0), Vec3.Y, Vec3.Y));
        for (int i = 0; i < 60; i++) b.step(DT);
        assertEquals(-1, b.bodyState(load).pose().position().y(), 0.01);
        assertEquals(1000 * 9.81, b.constraintState(c).force(), 1000 * 9.81 * 0.1);
    }

    @Test public void overloadedJointBreaks() {
        XpbdBackend b = new XpbdBackend();
        BodyHandle anchor = b.createBody(BodyDesc.fixed(Pose.IDENTITY, List.of()));
        BodyHandle load = cube(b, 1000, new Vec3(0, -1, 0));
        ConstraintHandle c = b.addConstraint(ConstraintDesc.of(JointType.FIXED, anchor, load, Vec3.ZERO, new Vec3(0, 1, 0), Vec3.Y, Vec3.Y)
                .withBreakForce(5_000));
        for (int i = 0; i < 60; i++) b.step(DT);
        assertTrue(b.constraintState(c).broken());
        assertTrue(b.bodyState(load).pose().position().y() < -2);
    }

    @Test public void restingBodiesFallAsleep() {
        XpbdBackend b = new XpbdBackend();
        b.setTerrain(TerrainQuery.flat(0, 0.8));
        BodyHandle h = cube(b, 500, new Vec3(0, 0.6, 0));
        for (int i = 0; i < 300; i++) b.step(DT);
        assertTrue(b.bodyState(h).sleeping());
        b.applyForce(h, new Vec3(0, 50_000, 0), b.bodyState(h).pose().position());
        b.step(DT);
        assertFalse(b.bodyState(h).sleeping());
    }

    @Test public void threadedWorldPublishesSnapshots() throws Exception {
        try (PhysicsWorld w = new PhysicsWorld(new XpbdBackend(), 60, true, "colossus-physics-test")) {
            Box box = new Box(new Vec3(-0.5, -0.5, -0.5), new Vec3(0.5, 0.5, 0.5));
            BodyHandle h = w.createBody(BodyDesc.dynamic(MassProperties.solidBox(10, new Vec3(1, 1, 1)), new Pose(new Vec3(0, 50, 0), Quat.IDENTITY), List.of(box)))
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(300);
            PhysicsWorld.Snapshot s = w.snapshot();
            assertTrue(s.step() > 5);
            assertTrue(s.body(h).pose().position().y() < 50);
        }
    }

    @Test public void forceModels() {
        assertEquals(1.225, Forces.airDensity(63, 600), 1e-9);
        assertTrue(Forces.airDensity(663, 600) < 0.5);
        // drag opposes motion
        assertTrue(Forces.drag(new Vec3(10, 0, 0), 1.225, 1, 4).x() < 0);
        // stall: lift coefficient drops past the stall angle
        double st = Math.toRadians(15);
        assertTrue(Forces.liftCoefficient(Math.toRadians(25), st) < Forces.liftCoefficient(st, st));
        // reverse spin reverses thrust
        assertTrue(Forces.propellerThrust(0.1, 1.2, -600, 4) < 0);
        // diminishing returns beyond ~4 blades
        double c2 = Forces.thrustCoefficient(2, 0.3), c4 = Forces.thrustCoefficient(4, 0.3), c8 = Forces.thrustCoefficient(8, 0.3);
        assertTrue(c4 - c2 > c8 - c4 - 1e-12 && c8 > c4);
        // a sealed 10 m³ hull displaces 10 t of water
        assertEquals(98_100, Forces.buoyancy(1000, 10, new Vec3(0, -9.81, 0)).y(), 1e-6);
    }
}
