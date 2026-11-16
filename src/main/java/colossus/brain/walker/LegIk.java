package colossus.brain.walker;

import colossus.brain.ik.IkChain;
import colossus.brain.ik.IkSolver;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

/**
 * Leg IK. The common ball hip + hinge knee leg gets an exact solve; every other shape uses the blueprint's default
 * solver ({@link IkSolver#defaultFor}). Pure Java.
 * <p>
 * Ball + hinge: the knee angle is fixed by the hip–foot distance (law of cosines in the plane normal to the knee axis);
 * the hip rotation then swings the leg onto the target line and twists it so the knee axis stays as close as it can to
 * where it was at assembly (perpendicular to the leg). Of the two knee solutions the one nearer the previous angle wins,
 * and the chain's pole breaks a tie (a straight leg can bend either way).
 */
public final class LegIk {
    private LegIk() {}

    public static IkSolver.Result solve(IkChain chain, IkChain.Pose warm, Vec3 target) { return solve(chain, warm, target, Quat.IDENTITY); }

    /**
     * @param legTurn turn of the leg about the vertical relative to the base frame (the foot's heading): the knee axis
     *                follows it, so a planted foot that keeps its heading while the body turns needs no ankle twist
     */
    public static IkSolver.Result solve(IkChain chain, IkChain.Pose warm, Vec3 target, Quat legTurn) {
        if (chain.size() == 2 && chain.links.get(0).kind() == IkChain.Kind.BALL && chain.links.get(1).kind() == IkChain.Kind.HINGE) {
            return ballHinge(chain, warm, target, legTurn);
        }
        return IkSolver.defaultFor(chain).solve(chain, warm, target);
    }

    static IkSolver.Result ballHinge(IkChain chain, IkChain.Pose warm, Vec3 target) { return ballHinge(chain, warm, target, Quat.IDENTITY); }

    static IkSolver.Result ballHinge(IkChain chain, IkChain.Pose warm, Vec3 target, Quat legTurn) {
        IkChain.Link hip = chain.links.get(0), knee = chain.links.get(1);
        Vec3 b0 = hip.bone(), b1 = knee.bone(), k = knee.axis();
        Vec3 b1par = k.mul(b1.dot(k)), b1perp = b1.sub(b1par);
        Vec3 a = b0.add(b1par), aPerp = a.rejectFrom(k);
        double minD = Math.abs(aPerp.length() - b1perp.length()), maxD = aPerp.length() + b1perp.length();
        double d0 = target.length();
        double fullMin = Math.sqrt(Math.max(0, a.lengthSq() - aPerp.lengthSq() + minD * minD));
        double fullMax = Math.sqrt(a.lengthSq() - aPerp.lengthSq() + maxD * maxD);
        double d = Math.max(fullMin + 1e-4, Math.min(fullMax - 1e-4, d0));
        boolean outOfReach = d0 > fullMax + IkSolver.TOLERANCE;

        // |a + R(θ)·b1perp|² = d²  →  aPerp·R(θ)b1perp = C
        double c = (d * d - a.lengthSq() - b1perp.lengthSq()) / 2;
        double denom = aPerp.length() * b1perp.length();
        double cosPhi = denom < 1e-12 ? 1 : Math.max(-1, Math.min(1, c / denom));
        double phi0 = signedAngle(aPerp, b1perp, k);
        double acos = Math.acos(cosPhi);
        double[] candidates = {wrap(acos - phi0), wrap(-acos - phi0)};

        IkChain.Pose best = null;
        double bestScore = Double.MAX_VALUE;
        for (double theta : candidates) {
            IkChain.Pose p = new IkChain.Pose(2);
            p.value[1] = knee.min() <= knee.max() ? Math.max(knee.min(), Math.min(knee.max(), theta)) : theta;
            Vec3 v = b0.add(Quat.axisAngle(k, p.value[1]).rotate(b1)); // leg vector before the hip turns
            p.ball[0] = aim(v, k, legTurn.rotate(k), target.lengthSq() < 1e-12 ? v : target);
            chain.clamp(p);
            double score = Math.abs(p.value[1] - warm.value[1]) + Math.abs(theta - p.value[1]) * 10;
            if (chain.pole != null) {
                Vec3 kneePos = p.ball[0].rotate(b0);
                score -= 1e-3 * kneePos.normalize().dot(chain.pole.normalize()); // tie-break only
            }
            if (score < bestScore) { bestScore = score; best = p; }
        }
        Vec3 eff = chain.forward(best).effector();
        double err = eff.distance(target);
        return new IkSolver.Result(best, eff, err, err <= IkSolver.TOLERANCE, outOfReach);
    }

    /**
     * Hip rotation that turns leg vector v onto the target direction and brings the knee axis k as close as it can to
     * {@code kHint} (the rest knee axis turned with the foot), keeping it perpendicular to the leg.
     */
    static Quat aim(Vec3 v, Vec3 k, Vec3 kHint, Vec3 target) {
        Vec3 dir = target.normalize();
        Vec3 kWant = kHint.rejectFrom(dir);
        if (kWant.lengthSq() < 1e-9) kWant = dir.anyPerpendicular();
        kWant = kWant.normalize();
        Quat swing = Quat.fromTo(v.normalize(), dir);
        double twist = signedAngle(swing.rotate(k), kWant, dir);
        return Quat.axisAngle(dir, twist).mul(swing).normalize();
    }

    static double signedAngle(Vec3 u, Vec3 v, Vec3 n) {
        Vec3 uu = u.rejectFrom(n), vv = v.rejectFrom(n);
        if (uu.lengthSq() < 1e-18 || vv.lengthSq() < 1e-18) return 0;
        uu = uu.normalize(); vv = vv.normalize();
        return Math.atan2(uu.cross(vv).dot(n), uu.dot(vv));
    }

    public static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
