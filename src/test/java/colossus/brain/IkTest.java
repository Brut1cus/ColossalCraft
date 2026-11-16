package colossus.brain;

import colossus.brain.ik.*;
import colossus.physics.math.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

public class IkTest {
    /** Thigh + shin hanging down, both hinges on the Z axis (a plantigrade leg in the X/Y plane). */
    static IkChain leg() {
        IkChain c = IkChain.of(
                IkChain.Link.hinge(Vec3.Z, new Vec3(0, -2, 0), Math.toRadians(-90), Math.toRadians(90)),
                IkChain.Link.hinge(Vec3.Z, new Vec3(0, -2, 0), Math.toRadians(-150), 0));
        c.pole = new Vec3(1, 0, 0); // knee bends forward (+X)
        return c;
    }

    static void reaches(IkSolver s, IkChain c, Vec3 target) {
        IkSolver.Result r = s.solve(c, new IkChain.Pose(c.size()), target);
        assertTrue(s.getClass().getSimpleName() + " error " + r.error() + " to " + target, r.reached());
        assertFalse(r.outOfReach());
        // joint limits respected
        for (int i = 0; i < c.size(); i++) {
            IkChain.Link l = c.links.get(i);
            if (l.kind() == IkChain.Kind.HINGE) assertTrue(r.pose().value[i] >= l.min() - 1e-9 && r.pose().value[i] <= l.max() + 1e-9);
        }
    }

    @Test public void analyticTwoBoneIsExact() {
        IkChain c = leg();
        Vec3 t = new Vec3(0.8, -3.2, 0);
        IkSolver.Result r = new Analytic2Bone().solve(c, new IkChain.Pose(2), t);
        assertTrue("error " + r.error(), r.error() < 1e-6);
        // knee bends forward: knee x > straight line between hip and foot
        Vec3 knee = c.forward(r.pose()).positions()[1];
        assertTrue("knee forward " + knee, knee.x() > t.x() / 2);
    }

    @Test public void allSolversReachAPlanarTarget() {
        Vec3 t = new Vec3(1.0, -3.0, 0);
        reaches(new Analytic2Bone(), leg(), t);
        reaches(new Fabrik(), leg(), t);
        reaches(new DampedLeastSquares(), leg(), t);
    }

    @Test public void ccdSolvesCraneArm() {
        IkChain crane = IkChain.of(
                IkChain.Link.hinge(Vec3.Y, new Vec3(0, 3, 0), -Math.PI, Math.PI),      // slewing base
                IkChain.Link.hinge(Vec3.Z, new Vec3(3, 0, 0), -1.4, 1.4),              // boom
                IkChain.Link.hinge(Vec3.Z, new Vec3(2, 0, 0), -2.5, 2.5));             // jib
        IkSolver.Result r = new Ccd().solve(crane, new IkChain.Pose(3), new Vec3(1, 4, -3));
        assertTrue("error " + r.error(), r.reached());
    }

    @Test public void outOfReachIsFlagged() {
        IkSolver.Result r = new Analytic2Bone().solve(leg(), new IkChain.Pose(2), new Vec3(0, -6, 0));
        assertTrue(r.outOfReach());
        assertFalse(r.reached());
        assertEquals(2.0, r.error(), 0.05);
    }

    @Test public void kneeLimitIsRespected() {
        // knee may only bend one way: a target that needs the other way can't be fully reached by bending backwards
        IkChain c = leg();
        IkSolver.Result r = new Ccd().solve(c, new IkChain.Pose(2), new Vec3(-1.0, -1.0, 0));
        assertTrue(r.pose().value[1] <= 1e-9);
    }

    @Test public void fabrikSolvesLongTail() {
        IkChain.Link[] links = new IkChain.Link[8];
        for (int i = 0; i < 8; i++) links[i] = IkChain.Link.ball(new Vec3(0, -1, 0), new Vec3(0, -1, 0), Math.toRadians(40));
        IkChain tail = IkChain.of(links);
        Vec3 t = new Vec3(3, -5, 2);
        IkSolver.Result r = new Fabrik().solve(tail, new IkChain.Pose(8), t);
        assertTrue("error " + r.error(), r.error() < 0.05);
        assertTrue(IkSolver.defaultFor(tail) instanceof Fabrik);
    }

    @Test public void dlsHandlesSlider() {
        // telescoping leg: ball hip + slider shin
        IkChain c = IkChain.of(
                IkChain.Link.ball(new Vec3(0, -1, 0), new Vec3(0, -1.5, 0), Math.toRadians(60)),
                IkChain.Link.slider(new Vec3(0, -1, 0), new Vec3(0, -0.5, 0), 0, 1.5));
        Vec3 t = new Vec3(1.0, -3.0, 0.5);
        IkSolver.Result r = new DampedLeastSquares().solve(c, new IkChain.Pose(2), t);
        assertTrue("error " + r.error(), r.reached());
        assertTrue(IkSolver.defaultFor(c) instanceof DampedLeastSquares);
    }

    @Test public void digitigradeKeepsHockRatio() {
        IkChain c = IkChain.of(
                IkChain.Link.hinge(Vec3.Z, new Vec3(0, -2, 0), Math.toRadians(-80), Math.toRadians(80)),
                IkChain.Link.hinge(Vec3.Z, new Vec3(0, -2, 0), Math.toRadians(-150), Math.toRadians(150)),
                IkChain.Link.hinge(Vec3.Z, new Vec3(0, -1.2, 0), Math.toRadians(-150), Math.toRadians(150)));
        c.pole = new Vec3(-1, 0, 0); // reverse knee
        double ratio = -0.8;
        IkSolver.Result r = new Analytic3BoneDigitigrade(ratio).solve(c, new IkChain.Pose(3), new Vec3(0.3, -4.2, 0));
        assertTrue("error " + r.error(), r.error() < 0.05);
        assertEquals(ratio * r.pose().value[1], r.pose().value[2], 1e-9);
    }

    @Test public void warmStartIsStable() {
        IkChain c = leg();
        IkSolver s = new Fabrik();
        IkSolver.Result a = s.solve(c, new IkChain.Pose(2), new Vec3(0.5, -3.3, 0));
        IkSolver.Result b = s.solve(c, a.pose(), new Vec3(0.52, -3.3, 0));
        // a tiny target move must not flip the knee
        assertEquals(a.pose().value[1], b.pose().value[1], 0.05);
    }
}
