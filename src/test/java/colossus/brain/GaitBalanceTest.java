package colossus.brain;

import colossus.brain.balance.BalanceController;
import colossus.brain.balance.SupportPolygon;
import colossus.brain.foot.FootPlanner;
import colossus.brain.foot.SwingTrajectory;
import colossus.brain.gait.GaitPattern;
import colossus.brain.gait.GaitPlanner;
import colossus.physics.math.Vec3;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class GaitBalanceTest {

    @Test public void walkGaitKeepsThreeFeetDown() {
        GaitPattern walk = GaitPattern.quadWalk();
        for (double t = 0; t < 1; t += 0.01) {
            int down = 0;
            for (int leg = 0; leg < 4; leg++) if (walk.inStance(leg, t)) down++;
            assertTrue("at t=" + t + " only " + down + " feet down", down >= 3);
        }
    }

    /** The crawl lifts one leg at a time in the lateral sequence, with all four feet down between swings. */
    @Test public void crawlHasFourLeggedPhasesBetweenSwings() {
        GaitPattern crawl = GaitPattern.quadCrawl(0.9);
        int fourDown = 0;
        java.util.List<Integer> order = new java.util.ArrayList<>();
        boolean[] was = {true, true, true, true};
        for (double t = 0; t < 1; t += 0.001) {
            int down = 0;
            for (int leg = 0; leg < 4; leg++) {
                boolean in = crawl.inStance(leg, t);
                if (in) down++;
                if (was[leg] && !in) order.add(leg);
                was[leg] = in;
            }
            assertTrue("at t=" + t + " only " + down + " feet down", down >= 3);
            if (down == 4) fourDown++;
        }
        assertTrue("four-legged phases", fourDown > 400);
        assertEquals("LH, LF, RH, RF", java.util.List.of(2, 0, 3, 1), order);
    }

    @Test public void trotMovesDiagonalPairsTogether() {
        GaitPattern trot = GaitPattern.quadTrot();
        for (double t = 0; t < 1; t += 0.05) {
            assertEquals(trot.inStance(0, t), trot.inStance(3, t)); // LF with RH
            assertEquals(trot.inStance(1, t), trot.inStance(2, t)); // RF with LH
        }
    }

    @Test public void waveGaitLiftsOneLegAtATime() {
        GaitPattern wave = GaitPattern.wave(8);
        for (double t = 0; t < 1; t += 0.01) {
            int up = 0;
            for (int leg = 0; leg < 8; leg++) if (!wave.inStance(leg, t)) up++;
            assertTrue(up <= 1);
        }
    }

    @Test public void speedPicksGaitWithHysteresis() {
        GaitPlanner p = new GaitPlanner(GaitPattern.defaultsFor(4), 1.5);
        p.advance(0.05, 0.5, l -> true, l -> true);
        assertEquals("crawl", p.gait().name());
        p.advance(0.05, 2.5, l -> true, l -> true);
        assertEquals("trot", p.gait().name());
        p.advance(0.05, 1.1, l -> true, l -> true); // inside the band: stays trotting
        assertEquals("trot", p.gait().name());
        p.advance(0.05, 0.3, l -> true, l -> true);
        assertEquals("crawl", p.gait().name());
    }

    @Test public void staticGaitNeverLiftsAnUnsafeLeg() {
        GaitPlanner p = new GaitPlanner(new GaitPattern[]{GaitPattern.quadWalk()}, 1.5);
        int safetyHolds = 0;
        for (int i = 0; i < 400; i++) {
            boolean[] stance = p.advance(0.05, 0.6, leg -> leg != 2, leg -> true); // leg 2 is never safe to lift
            assertTrue("leg 2 lifted", stance[2]);
            if (p.isHeld(2)) safetyHolds++;
        }
        assertTrue(safetyHolds > 0);
    }

    @Test public void standingStillKeepsAllFeetDown() {
        GaitPlanner p = new GaitPlanner(GaitPattern.defaultsFor(6), 1);
        boolean[] s = p.advance(0.05, 0, l -> true, l -> true);
        for (boolean b : s) assertTrue(b);
    }

    @Test public void supportPolygonMargin() {
        SupportPolygon sq = new SupportPolygon(List.of(new Vec3(0, 0, 0), new Vec3(2, 0, 0), new Vec3(2, 0, 2), new Vec3(0, 0, 2), new Vec3(1, 0, 1)));
        assertEquals(4, sq.vertexCount());
        assertEquals(1, sq.margin(new Vec3(1, 5, 1)), 1e-9);
        assertEquals(-1, sq.margin(new Vec3(3, 0, 1)), 1e-9);
        assertTrue(sq.contains(new Vec3(0.1, 0, 1.9)));
        assertEquals(new Vec3(2, 0, 1), sq.closestPoint(new Vec3(3, 0, 1)));
    }

    @Test public void capturePointLeadsTheBody() {
        Vec3 cp = BalanceController.capturePoint(new Vec3(0, 3, 0), new Vec3(2, 0, 0), 0);
        assertEquals(2 * Math.sqrt(3 / 9.81), cp.x(), 1e-9);
    }

    @Test public void wrenchIsSharedAndCarriesTheWeight() {
        List<Vec3> feet = List.of(new Vec3(-1, 0, -1), new Vec3(1, 0, -1), new Vec3(-1, 0, 1), new Vec3(1, 0, 1));
        BalanceController.Wrench w = new BalanceController.Wrench(new Vec3(0, 10_000, 0), Vec3.ZERO);
        Vec3[] f = BalanceController.distribute(w, feet, new Vec3(0, 2, 0), 0.8);
        double sum = 0;
        for (Vec3 v : f) { assertEquals(2500, v.y(), 1); sum += v.y(); }
        assertEquals(10_000, sum, 1);
        // forces stay inside the friction cone
        BalanceController.Wrench push = new BalanceController.Wrench(new Vec3(50_000, 10_000, 0), Vec3.ZERO);
        for (Vec3 v : BalanceController.distribute(push, feet, new Vec3(0, 2, 0), 0.5)) {
            assertTrue(Math.hypot(v.x(), v.z()) <= 0.5 * v.y() + 1e-6);
            assertTrue(v.y() >= 0);
        }
    }

    @Test public void jacobianTransposeGivesKneeTorque() {
        // vertical 4 m leg, knee 2 m below hip, axis Z; pushing the ground with 1000 N backward (−X) at the foot
        double[] tau = BalanceController.jacobianTranspose(new Vec3[]{new Vec3(0, 4, 0), new Vec3(0, 2, 0)},
                new Vec3[]{Vec3.Z, Vec3.Z}, Vec3.ZERO, new Vec3(-1000, 0, 0));
        assertEquals(-4000, tau[0], 1e-9);
        assertEquals(-2000, tau[1], 1e-9);
    }

    @Test public void raibertPlacesFootAheadWhenTooFast() {
        FootPlanner fp = new FootPlanner(FootPlanner.Settings.defaults(4));
        Vec3 home = new Vec3(1, 0, 0), body = new Vec3(0, 3, 0);
        Vec3 slow = fp.idealFoothold(home, new Vec3(0, 0, 1), new Vec3(0, 0, 1), 0, body, 0.3, 0.6);
        Vec3 fast = fp.idealFoothold(home, new Vec3(0, 0, 2), new Vec3(0, 0, 1), 0, body, 0.3, 0.6);
        assertTrue(fast.z() > slow.z() + 0.6);
    }

    @Test public void footSearchAvoidsLavaAndCliffs() {
        FootPlanner fp = new FootPlanner(FootPlanner.Settings.defaults(5));
        // flat ground at y=0, but a lava pool where x in [−0.6, 0.6]
        FootPlanner.GroundProbe probe = (from, max) -> Optional.of(new FootPlanner.GroundHit(
                new Vec3(from.x(), 0, from.z()), Vec3.Y, Math.abs(from.x()) < 0.6, false, false));
        Optional<FootPlanner.GroundHit> h = fp.choose(new Vec3(0, 0, 0), new Vec3(0, 3, 0), probe);
        assertTrue(h.isPresent());
        assertTrue(Math.abs(h.get().point().x()) >= 0.6);
        assertTrue(h.get().point().distance(Vec3.ZERO) < 1.1);
        // no ground at all within reach
        assertTrue(fp.choose(Vec3.ZERO, new Vec3(0, 3, 0), (from, max) -> Optional.empty()).isEmpty());
    }

    @Test public void swingClearsObstacle() {
        SwingTrajectory s = new SwingTrajectory(new Vec3(0, 0, 0), new Vec3(0, 0, 2), 0.5, 1.2, 0.3);
        double max = 0;
        for (double t = 0; t <= 1; t += 0.01) max = Math.max(max, s.at(t).y());
        assertEquals(1.5, max, 1e-3);
        assertEquals(new Vec3(0, 0, 2), s.at(1));
    }
}
