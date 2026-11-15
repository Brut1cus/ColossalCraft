package com.colossalcraft.colossus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Physics walker: the Hull is a rigid body that only moves because planted feet push on the ground.
 *
 * Each tick:
 *  1. Contact: planted feet are locked in world space. A foot plants on the highest ground under any of its sole blocks.
 *  2. Gait: legs step in groups (from the Locomotion Controller's gait type). A group lifts only when the weight can be carried by the others.
 *  3. Balance: works out the force the Hull needs (track the pilot's speed, keep the weight over the support feet, hold standing height).
 *  4. Ground reaction: sideways push per foot <= min(grip * load, hip torque / hip height); a stiff leg holds only while tan(angle) <= grip.
 *     Knees hold the Hull up with at most their torque: weak knees let it sink.
 *  5. The Hull integrates those forces (+ gravity, thrust). Legs are struts with reach limits.
 *  6. IK (Analytic 2-link, FABRIK or CCD) bends every leg to reach its foot (or ankle, if a ball joint levels the foot).
 */
public class WalkerController {
    public enum State { STANCE, SWING, AIR }

    public State[] state = new State[0];
    public Vec3[] foot = new Vec3[0];        // world contact point of each foot (centre of the soles, on the ground)
    private Vec3[] swingFrom = new Vec3[0], swingTo = new Vec3[0];
    private double[] swingT = new double[0];
    public boolean[] slipping = new boolean[0];
    private int nextGroup = 0, waitingGroup = -1;
    private Vec3 track;
    public boolean unbalanced;
    private int outsideTicks;
    public String status = "";
    private int legsHash;
    /** Standing height as a share of leg reach (raise/lower keys). 1 = as built. */
    public double height = 1;
    /** Ticks after a Jump Drive launch during which feet may not re-plant. */
    private int launchTicks;

    private static final double KP = 8, KD = 5, KPV = 30, KDV = 10;

    public boolean usable(StructureModel m) { return !m.legChains.isEmpty(); }

    public void release(StructureModel m) {
        if (m.ikAngles != null) Arrays.fill(m.ikAngles, Double.NaN);
        legsHash = 0;
    }

    /** Jump Drive: every foot lets go and the legs snap straight. */
    public void launch(ColossusEntity c) {
        ensure(c);
        for (int i = 0; i < state.length; i++) state[i] = State.AIR;
        launchTicks = 6;
        if (c.model.ikAngles != null) for (StructureModel.LegChain L : c.model.legChains) for (int j : L.joints()) c.model.ikAngles[j] = 0;
        track = null;
    }

    private void ensure(ColossusEntity c) {
        StructureModel m = c.model;
        int h = System.identityHashCode(m);
        if (h == legsHash && state.length == m.legChains.size()) return;
        legsHash = h;
        int n = m.legChains.size();
        state = new State[n]; foot = new Vec3[n]; swingFrom = new Vec3[n]; swingTo = new Vec3[n]; swingT = new double[n]; slipping = new boolean[n];
        for (int i = 0; i < n; i++) { state[i] = State.AIR; foot[i] = footFromPose(c, i); }
        track = null; nextGroup = 0; waitingGroup = -1;
    }

    private static Vec3 tf(Matrix4d m, Vec3 p) { Vector3d v = m.transformPosition(new Vector3d(p.x, p.y, p.z)); return new Vec3(v.x, v.y, v.z); }
    private Vec3 footFromPose(ColossusEntity c, int i) { StructureModel.LegChain L = c.model.legChains.get(i); return tf(c.segWorld(L.footSeg()), L.foot()); }
    private Vec3 hip(ColossusEntity c, int i) { StructureModel.LegChain L = c.model.legChains.get(i); return tf(c.segWorld(L.bodySeg()), L.hip()); }
    /** Level offset (world) from a foot's contact point to a rest-pose local point of that foot (soles, ankle): only turned by yaw. */
    private static Vec3 levelOffset(ColossusEntity c, Vec3 localFrom, Vec3 localTo) {
        double cs = Math.cos(c.yaw), sn = Math.sin(c.yaw);
        Vec3 d = localTo.subtract(localFrom);
        return new Vec3(d.x * cs - d.z * sn, d.y, d.x * sn + d.z * cs);
    }

    /** The ground surface under (x, z): searching down from a block above, the top of the first solid block. NaN if none within 3 below. */
    public static double groundY(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.0); by >= (int) Math.floor(y) - 3; by--) {
            mp.set(bx, by, bz);
            BlockState s = level.getBlockState(mp);
            VoxelShape sh = s.getCollisionShape(level, mp);
            if (!sh.isEmpty()) return by + sh.max(net.minecraft.core.Direction.Axis.Y);
        }
        return Double.NaN;
    }
    /** Highest ground under any sole block of a foot whose contact centre would be at p. */
    private double soleGround(ColossusEntity c, int i, Vec3 p) {
        StructureModel.LegChain L = c.model.legChains.get(i);
        double best = Double.NaN;
        for (BlockPos s : L.soles()) {
            Vec3 off = levelOffset(c, L.foot(), new Vec3(s.getX() + .5, L.foot().y, s.getZ() + .5));
            double gy = groundY(c.level(), p.x + off.x, p.y, p.z + off.z);
            if (!Double.isNaN(gy) && (Double.isNaN(best) || gy > best)) best = gy;
        }
        return best;
    }
    private static double groundMu(Level level, Vec3 p) {
        BlockState s = level.getBlockState(BlockPos.containing(p.x, p.y - 0.05, p.z));
        return Math.max(0.02, Math.min(1.2, 2.0 * (1 - s.getBlock().getFriction())));
    }
    private static double margin(StructureModel m) { return m.coreGyro <= 0 ? 0 : 0.25 + 1.5 * Math.min(1, m.coreGyro / m.mass); }

    // ------------------------------------------------------------------ main step

    /**
     * @param ext    outside forces on the Hull besides gravity (thrust, buoyancy, lift), N
     * @param ratio  how much of the wanted power is available (0..1)
     * @param fwd    pilot forward input (-1..1)
     * @param sprint run (faster gait)
     * @param raise  +1 raise, -1 lower, 0 hold
     */
    public void step(ColossusEntity c, Vec3 ext, double ratio, double fwd, boolean sprint, int raise) {
        ensure(c);
        StructureModel m = c.model;
        Level level = c.level();
        double dt = 0.05, M = m.mass, g = StructureModel.G;
        int n = m.legChains.size();
        int groups = 1; for (StructureModel.LegChain L : m.legChains) groups = Math.max(groups, L.group() + 1);
        boolean biped = groups == 2 && n == 2;
        double yw = c.yaw, cs = Math.cos(yw), sn = Math.sin(yw);
        Vec3 fl = Vec3.atLowerCornerOf(m.forward.getNormal());
        Vec3 fW = new Vec3(fl.x * cs - fl.z * sn, 0, fl.x * sn + fl.z * cs), rW = new Vec3(-fW.z, 0, fW.x);
        double gait = m.gaitSpeed * (sprint ? 1.6 : 1);
        double cycle = 1 / Math.max(0.05, gait), swingDur = cycle / Math.max(2, groups);
        height = Math.max(0.6, Math.min(1.15, height + raise * 0.4 * dt));
        double reachH = Double.MAX_VALUE;
        for (StructureModel.LegChain L : m.legChains) reachH = Math.min(reachH, L.length() * Math.sin(Math.min(L.limits()[0], Math.toRadians(60))));
        double stepMax = 0.6 * reachH;
        double vMax = stepMax / cycle * m.gaitScale();
        Vec3 vCmd = fW.scale(Math.max(-1, Math.min(1, fwd)) * vMax);

        if (launchTicks > 0) launchTicks--;
        Vec3[] H = new Vec3[n];
        for (int i = 0; i < n; i++) H[i] = hip(c, i);

        // ---- 1. feet: touchdown, swing progress
        for (int i = 0; i < n; i++) {
            if (state[i] == State.AIR) {
                Vec3 f = footFromPose(c, i);
                double gy = soleGround(c, i, f);
                boolean mayLand = launchTicks <= 0 && c.vel.y <= 0.1;
                if (mayLand && !Double.isNaN(gy) && f.y - gy < 0.15) { state[i] = State.STANCE; foot[i] = new Vec3(f.x, gy, f.z); }
                else foot[i] = f;
            } else if (state[i] == State.SWING) {
                swingT[i] += dt / swingDur;
                double t = Math.min(1, swingT[i]);
                double lift = 0.2 * m.legChains.get(i).length();
                Vec3 p = swingFrom[i].add(swingTo[i].subtract(swingFrom[i]).scale(t)).add(0, Math.sin(Math.PI * t) * lift, 0);
                foot[i] = p;
                if (t >= 1) {
                    double gy = soleGround(c, i, p.add(0, 0.5, 0));
                    if (!Double.isNaN(gy)) { foot[i] = new Vec3(p.x, gy, p.z); state[i] = State.STANCE; }
                    else { swingTo[i] = swingTo[i].subtract(0, 0.3, 0); swingFrom[i] = foot[i]; swingT[i] = 0.7; }
                }
            }
        }

        // ---- 2. gait
        boolean anySwing = false; for (State s : state) anySwing |= s == State.SWING;
        boolean wantStep = Math.abs(fwd) > 0.01;
        for (int i = 0; i < n && !wantStep; i++) if (state[i] == State.STANCE) {
            Vec3 off = foot[i].subtract(H[i]); off = new Vec3(off.x, 0, off.z);
            if (off.length() > 0.85 * reachH) wantStep = true;
        }
        waitingGroup = -1;
        double liftR = 0.5 + margin(m) + (biped ? 0.75 : 0);
        if (!anySwing && wantStep && groups > 1) {
            int G = nextGroup % groups;
            List<Vec3> others = new ArrayList<>(); boolean othersDown = true;
            for (int i = 0; i < n; i++) if (m.legChains.get(i).group() != G) { if (state[i] == State.STANCE) others.add(foot[i]); else othersDown = false; }
            if (othersDown && !others.isEmpty()) {
                if (insideSupport(others, c.position(), liftR)) {
                    for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == G && state[i] == State.STANCE) {
                        Vec3 hipProj = new Vec3(H[i].x, foot[i].y, H[i].z).add(levelOffset(c, m.legChains.get(i).hip(), m.legChains.get(i).foot()).multiply(1, 0, 1));
                        Vec3 vh = new Vec3(c.vel.x, 0, c.vel.z);
                        Vec3 dir = vCmd.lengthSqr() > 1e-6 ? vCmd.normalize() : fW;
                        double stepLen = Math.min(stepMax, Math.max(vCmd.length(), vh.length()) * cycle);
                        Vec3 to = hipProj.add(dir.scale(stepLen * 0.5)).add(vh.scale(swingDur * 0.5)); // Raibert-style placement
                        Vec3 off = to.subtract(hipProj); if (off.length() > stepMax) to = hipProj.add(off.normalize().scale(stepMax));
                        swingFrom[i] = foot[i]; swingTo[i] = to; swingT[i] = 0; state[i] = State.SWING;
                    }
                    nextGroup = G + 1;
                } else waitingGroup = G;
            }
        }

        // ---- 3. balance controller
        List<Integer> planted = new ArrayList<>();
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) planted.add(i);
        Vec3 com = c.position();
        if (planted.isEmpty()) {
            // nothing on the ground: fall with every block colliding (legs where they really are), legs relax to their built pose
            c.vel = c.vel.add(ext.scale(dt / M)).add(0, -g * dt, 0);
            c.moveFull(c.vel.scale(dt));
            c.onGroundC = false;
            status = "airborne";
            if (m.ikAngles != null) {
                System.arraycopy(m.ikAngles, 0, m.ikPrev, 0, m.ikAngles.length);
                for (StructureModel.LegChain L : m.legChains) for (int j : L.joints()) m.ikAngles[j] = Double.isNaN(m.ikAngles[j]) ? 0 : m.ikAngles[j] * 0.85;
            }
            unbalanced = false; outsideTicks = 0;
            return;
        }
        if (track == null || track.subtract(com).multiply(1, 0, 1).length() > 1.5) track = new Vec3(com.x, 0, com.z);
        track = track.add(vCmd.scale(dt));
        boolean waiting = waitingGroup >= 0 && !(biped && insideSupport(others(planted, waitingGroup, m), com, liftR + 1));
        List<Vec3> support = new ArrayList<>();
        if (waitingGroup >= 0) support.addAll(others(planted, waitingGroup, m));
        else for (int i : planted) support.add(foot[i]);
        Vec3 desired = track;
        if (!support.isEmpty()) {
            Vec3 sc = Vec3.ZERO; for (Vec3 p : support) sc = sc.add(p); sc = sc.scale(1.0 / support.size());
            if (waiting) { desired = new Vec3(sc.x, 0, sc.z); track = new Vec3(com.x, 0, com.z); }
            else {
                // shift sideways only as far as needed to be within balance range of the supporting feet (the gyro covers the rest)
                double lat = sc.subtract(desired).dot(rW);
                double need = biped ? Math.signum(lat) * Math.max(0, Math.abs(lat) - (liftR - 0.25)) : lat;
                desired = desired.add(rW.scale(need));
            }
        }
        Vec3 vh = new Vec3(c.vel.x, 0, c.vel.z);
        Vec3 vDes = vCmd;
        for (int i : planted) { // brake before a planted leg runs out of reach
            Vec3 o = H[i].subtract(foot[i]); o = new Vec3(o.x, 0, o.z);
            double ol = o.length(); if (ol < 1e-6) continue;
            Vec3 od = o.scale(1 / ol);
            double room = Math.max(0, 0.95 * reachH - ol), vMaxAway = Math.sqrt(2 * 3.0 * room), away = vDes.dot(od);
            if (away > vMaxAway) vDes = vDes.subtract(od.scale(away - vMaxAway));
            double tAway = track.subtract(new Vec3(foot[i].x, 0, foot[i].z)).dot(od);
            if (tAway > 0.95 * reachH) track = track.subtract(od.scale(tAway - 0.95 * reachH));
        }
        if (waiting) vDes = Vec3.ZERO;
        else desired = new Vec3(track.x, 0, track.z).add(rW.scale(desired.subtract(track).dot(rW)));
        Vec3 aDes = new Vec3(desired.x - com.x, 0, desired.z - com.z).scale(KP).add(vDes.subtract(vh).scale(KD));

        // ---- 4. ground reaction limits: grip and hip torque sideways; knee torque upwards
        double load = M * g / planted.size();
        double capSum = 0, fricSum = 0, kneeCap = 0, lenNow = 0, lenWant = 0; boolean allRigid = true;
        for (int i : planted) {
            StructureModel.LegChain L = m.legChains.get(i);
            double mu = Math.min(L.mu(), groundMu(level, foot[i]));
            double h = Math.max(0.5, H[i].y - foot[i].y);
            double fric = mu * load, torque = L.hipTorque() * ratio / h;
            fricSum += fric; capSum += Math.min(fric, torque);
            if (L.joints().size() > 1) {
                allRigid = false;
                kneeCap += L.kneeTorque() * ratio / Math.max(0.3, 0.35 * L.length());
                lenNow += H[i].subtract(effectorWorld(c, i)).length();
                lenWant += Math.min(0.98 * maxReach(m, L), height * L.hip().distanceTo(L.effector()));
            }
        }
        Vec3 Fdem = aDes.scale(M);
        Vec3 Fh = Fdem.length() > capSum ? (Fdem.length() < 1e-9 ? Vec3.ZERO : Fdem.normalize().scale(capSum)) : Fdem;
        boolean slip = Fdem.length() > fricSum + 1e-6 && fricSum <= capSum + 1e-6;
        for (int i : planted) slipping[i] = slip;
        double Fy = 0;
        if (!allRigid) { // knees hold the Hull at standing height, up to what their torque allows
            double vy = c.vel.y;
            double ay = KPV * (lenWant - lenNow) / Math.max(1, planted.size()) - KDV * vy;
            Fy = Math.max(0, Math.min(kneeCap, M * (g + ay)));
        }

        // ---- 5. integrate the Hull
        Vec3 F = Fh.add(ext.x, ext.y + Fy, ext.z);
        c.vel = c.vel.add(F.scale(dt / M)).add(0, -g * dt, 0);
        Vec3 before = c.position();
        c.moveBody(c.vel.scale(dt));
        if (slip) {
            Vec3 moved = c.position().subtract(before).multiply(1, 0, 1).scale(1 - fricSum / Math.max(1e-6, Fdem.length()));
            for (int i : planted) foot[i] = foot[i].add(moved);
        }
        // Legs are struts: never longer than they reach; stiff (knee-less) legs keep their length; knees can't fold past their limit.
        for (int it = 0; it < 3; it++) {
            Vec3 corr = Vec3.ZERO; int k = 0;
            for (int i : planted) {
                StructureModel.LegChain L = m.legChains.get(i);
                Vec3 hp = hip(c, i), e = effectorWorld(c, i), d = hp.subtract(e);
                double len = d.length();
                double maxL = L.joints().size() > 1 ? maxReach(m, L) : L.hip().distanceTo(L.effector());
                double minL = L.joints().size() > 1 ? 0.5 * maxL : 0.97 * maxL;
                double target = len > maxL ? maxL : len < minL ? minL : len;
                if (target == len || len < 1e-6) continue;
                Vec3 ci = d.scale((target - len) / len);
                double tan = Math.sqrt(d.x * d.x + d.z * d.z) / Math.max(1e-6, Math.abs(d.y));
                double muS = 1.25 * Math.min(L.mu(), groundMu(level, foot[i]));
                if (tan > muS) { foot[i] = foot[i].add(-ci.x, 0, -ci.z); slipping[i] = true; ci = new Vec3(0, ci.y, 0); }
                corr = corr.add(ci); k++;
            }
            if (k == 0) break;
            corr = corr.scale(1.0 / k);
            c.setPos(c.getX() + corr.x, c.getY() + corr.y, c.getZ() + corr.z);
            if (corr.y > 0 && c.vel.y < 0) c.vel = new Vec3(c.vel.x, 0, c.vel.z);
        }
        c.onGroundC = true;

        // ---- 6. IK and balance check
        solveAllIK(c);
        List<Vec3> feetDown = new ArrayList<>(); for (int i : planted) feetDown.add(foot[i]);
        boolean outside = !insideSupport(feetDown, c.position(), 0.5 + margin(m) + (biped ? 0.75 : 0));
        outsideTicks = outside ? outsideTicks + 1 : 0;
        unbalanced = outsideTicks > 20;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : " ").append(state[i] == State.STANCE ? (slipping[i] ? "SLIP" : "DOWN") : state[i] == State.SWING ? "STEP" : "AIR");
        status = sb + (waiting ? "  shifting weight" : "");
    }

    private List<Vec3> others(List<Integer> planted, int group, StructureModel m) {
        List<Vec3> o = new ArrayList<>(); for (int i : planted) if (m.legChains.get(i).group() != group) o.add(foot[i]); return o;
    }
    /** Where the IK effector (ankle or foot) must be for foot i's contact point. */
    private Vec3 effectorWorld(ColossusEntity c, int i) {
        StructureModel.LegChain L = c.model.legChains.get(i);
        return foot[i].add(levelOffset(c, L.foot(), L.effector()));
    }
    private static double maxReach(StructureModel m, StructureModel.LegChain L) {
        double sum = 0; Vec3 prev = L.hip();
        for (int k = 1; k < L.joints().size(); k++) { Vec3 p = m.joints.get(L.joints().get(k)).pivot; sum += p.distanceTo(prev); prev = p; }
        return sum + prev.distanceTo(L.effector());
    }

    // ------------------------------------------------------------------ IK

    private void solveAllIK(ColossusEntity c) {
        StructureModel m = c.model;
        System.arraycopy(m.ikAngles, 0, m.ikPrev, 0, m.ikAngles.length);
        boolean digi = m.gaitMode == StructureModel.GAIT_DIGI;
        for (int i = 0; i < m.legChains.size(); i++) {
            StructureModel.LegChain L = m.legChains.get(i);
            Matrix4d inv = new Matrix4d(c.segWorld(L.bodySeg())).invert();
            Vec3 tw = effectorWorld(c, i);
            Vector3d t = inv.transformPosition(new Vector3d(tw.x, tw.y, tw.z));
            double[] ang = new double[L.joints().size()];
            for (int k = 0; k < ang.length; k++) { double a = m.ikAngles[L.joints().get(k)]; ang[k] = Double.isNaN(a) ? 0 : a; }
            solve(m, L, new Vec3(t.x, t.y, t.z), ang, digi);
            for (int k = 0; k < ang.length; k++) m.ikAngles[L.joints().get(k)] = ang[k];
        }
    }

    /** Pick the solver from the Locomotion Controller: Analytic 2-link and FABRIK work in the leg's plane; anything else uses CCD. */
    public static void solve(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang, boolean kneeBack) {
        boolean planar = true;
        for (int j : L.joints()) planar &= m.joints.get(j).axis == m.joints.get(L.joints().get(0)).axis;
        if (planar && m.ikMode == 0 && ang.length == 2) { analytic2(m, L, target, ang, kneeBack); return; }
        if (planar && (m.ikMode == 1 || m.ikMode == 0) && ang.length >= 1) { fabrik(m, L, target, ang, kneeBack); return; }
        solveIK(m, L, target, ang);
    }

    /** 2D frame in the leg's plane: b1 x b2 = axis, so a positive angle is a right-hand turn about the joint axis. */
    private static Vec3[] plane(StructureModel m, StructureModel.LegChain L) {
        Vec3 n = StructureModel.axisVec(m.joints.get(L.joints().get(0)).axis);
        Vec3 b2 = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 b1 = b2.cross(n).normalize();
        b2 = n.cross(b1).normalize();
        return new Vec3[]{b1, b2, n};
    }
    private static double[] to2(Vec3 p, Vec3 o, Vec3[] B) { Vec3 d = p.subtract(o); return new double[]{d.dot(B[0]), d.dot(B[1])}; }

    /** Points of the chain in its rest pose: pivots then the effector. */
    private static Vec3[] restPoints(StructureModel m, StructureModel.LegChain L) {
        int k = L.joints().size();
        Vec3[] p = new Vec3[k + 1];
        for (int i = 0; i < k; i++) p[i] = m.joints.get(L.joints().get(i)).pivot;
        p[k] = L.effector();
        return p;
    }

    /** Joint angles from absolute segment angles (each joint also turns everything below it). */
    private static void anglesFromSegments(StructureModel.LegChain L, double[] restAbs, double[] abs, double[] ang) {
        double sum = 0;
        for (int i = 0; i < ang.length; i++) {
            double a = wrap(abs[i] - restAbs[i] - sum);
            a = Math.max(-L.limits()[i], Math.min(L.limits()[i], a));
            ang[i] = a; sum += a;
        }
    }
    private static double wrap(double a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }

    private static boolean kneeForwardSign(StructureModel m, Vec3[] B, boolean kneeBack, double[] hip, double[] tgt, double[] knee) {
        Vec3 f = Vec3.atLowerCornerOf(m.forward.getNormal());
        double fx = f.dot(B[0]), fy = f.dot(B[1]);
        double mx = (hip[0] + tgt[0]) / 2, my = (hip[1] + tgt[1]) / 2;
        double side = (knee[0] - mx) * fx + (knee[1] - my) * fy; // >0: knee points forward
        return kneeBack ? side < 0 : side > 0;
    }

    /** Analytic two-link IK (hip + knee) with the knee bending forward (standard) or backward (digitigrade). */
    public static void analytic2(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang, boolean kneeBack) {
        Vec3[] B = plane(m, L);
        Vec3[] rp = restPoints(m, L);
        double[] p0 = to2(rp[0], rp[0], B), p1 = to2(rp[1], rp[0], B), p2 = to2(rp[2], rp[0], B), t = to2(target, rp[0], B);
        double l0 = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]), l1 = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
        double[] restAbs = {Math.atan2(p1[1] - p0[1], p1[0] - p0[0]), Math.atan2(p2[1] - p1[1], p2[0] - p1[0])};
        double d = Math.max(Math.abs(l0 - l1) + 1e-3, Math.min(l0 + l1 - 1e-3, Math.hypot(t[0], t[1])));
        double base = Math.atan2(t[1], t[0]);
        double cosA = Math.max(-1, Math.min(1, (l0 * l0 + d * d - l1 * l1) / (2 * l0 * d)));
        double A = Math.acos(cosA);
        double[] best = null;
        for (int s = -1; s <= 1; s += 2) {
            double a0 = base + s * A;
            double[] knee = {l0 * Math.cos(a0), l0 * Math.sin(a0)};
            double tx = d * Math.cos(base), ty = d * Math.sin(base);
            double a1 = Math.atan2(ty - knee[1], tx - knee[0]);
            if (best == null || kneeForwardSign(m, B, kneeBack, p0, new double[]{tx, ty}, knee)) best = new double[]{a0, a1};
        }
        anglesFromSegments(L, restAbs, best, ang);
    }

    /** FABRIK in the leg's plane, starting from the current pose, with a pole that pushes the knee forward (or back). */
    public static void fabrik(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang, boolean kneeBack) {
        Vec3[] B = plane(m, L);
        Vec3[] rp = restPoints(m, L);
        int k = ang.length;
        double[][] rest = new double[k + 1][]; for (int i = 0; i <= k; i++) rest[i] = to2(rp[i], rp[0], B);
        double[] len = new double[k], restAbs = new double[k];
        for (int i = 0; i < k; i++) { len[i] = Math.hypot(rest[i + 1][0] - rest[i][0], rest[i + 1][1] - rest[i][1]); restAbs[i] = Math.atan2(rest[i + 1][1] - rest[i][1], rest[i + 1][0] - rest[i][0]); }
        double[][] p = new double[k + 1][2];
        double acc = 0; p[0] = new double[]{0, 0};
        for (int i = 0; i < k; i++) { acc += ang[i]; double a = restAbs[i] + acc; p[i + 1] = new double[]{p[i][0] + len[i] * Math.cos(a), p[i][1] + len[i] * Math.sin(a)}; }
        double[] t = to2(target, rp[0], B);
        Vec3 f = Vec3.atLowerCornerOf(m.forward.getNormal());
        double px = f.dot(B[0]) * (kneeBack ? -1 : 1), py = f.dot(B[1]) * (kneeBack ? -1 : 1);
        for (int i = 1; i < k; i++) { p[i][0] += px * 0.15 * len[i - 1]; p[i][1] += py * 0.15 * len[i - 1]; } // pole bias
        double total = 0; for (double l : len) total += l;
        if (Math.hypot(t[0], t[1]) > total) { double s = total / Math.hypot(t[0], t[1]); t = new double[]{t[0] * s * 0.999, t[1] * s * 0.999}; }
        for (int iter = 0; iter < 12; iter++) {
            p[k] = t.clone();
            for (int i = k - 1; i >= 0; i--) { double dx = p[i][0] - p[i + 1][0], dy = p[i][1] - p[i + 1][1], dd = Math.max(1e-9, Math.hypot(dx, dy)); p[i] = new double[]{p[i + 1][0] + dx / dd * len[i], p[i + 1][1] + dy / dd * len[i]}; }
            p[0] = new double[]{0, 0};
            for (int i = 0; i < k; i++) { double dx = p[i + 1][0] - p[i][0], dy = p[i + 1][1] - p[i][1], dd = Math.max(1e-9, Math.hypot(dx, dy)); p[i + 1] = new double[]{p[i][0] + dx / dd * len[i], p[i][1] + dy / dd * len[i]}; }
        }
        double[] abs = new double[k];
        for (int i = 0; i < k; i++) abs[i] = Math.atan2(p[i + 1][1] - p[i][1], p[i + 1][0] - p[i][0]);
        anglesFromSegments(L, restAbs, abs, ang);
    }

    private static Matrix4d jt(StructureModel.Joint j, double a) {
        Vec3 ax = StructureModel.axisVec(j.axis);
        return new Matrix4d().translate(j.pivot.x, j.pivot.y, j.pivot.z).rotate(a, ax.x, ax.y, ax.z).translate(-j.pivot.x, -j.pivot.y, -j.pivot.z);
    }

    /** Cyclic coordinate descent over the leg's hinge joints (any axes), within each joint's limit. */
    public static void solveIK(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang) {
        int k = ang.length;
        Vec3 eff = L.effector();
        for (int iter = 0; iter < 12; iter++) {
            for (int jIdx = k - 1; jIdx >= 0; jIdx--) {
                Matrix4d above = new Matrix4d();
                for (int i = 0; i < jIdx; i++) above.mul(jt(m.joints.get(L.joints().get(i)), ang[i]));
                Matrix4d all = new Matrix4d(above);
                for (int i = jIdx; i < k; i++) all.mul(jt(m.joints.get(L.joints().get(i)), ang[i]));
                StructureModel.Joint j = m.joints.get(L.joints().get(jIdx));
                Vector3d piv = above.transformPosition(new Vector3d(j.pivot.x, j.pivot.y, j.pivot.z));
                Vec3 a = StructureModel.axisVec(j.axis);
                Vector3d axw = above.transformDirection(new Vector3d(a.x, a.y, a.z)).normalize();
                Vector3d f = all.transformPosition(new Vector3d(eff.x, eff.y, eff.z));
                Vector3d v1 = new Vector3d(f).sub(piv), v2 = new Vector3d(target.x, target.y, target.z).sub(piv);
                v1.sub(new Vector3d(axw).mul(v1.dot(axw))); v2.sub(new Vector3d(axw).mul(v2.dot(axw)));
                if (v1.lengthSquared() < 1e-8 || v2.lengthSquared() < 1e-8) continue;
                double turn = Math.atan2(new Vector3d(v1).cross(v2).dot(axw), v1.dot(v2));
                double lim = L.limits()[jIdx];
                ang[jIdx] = Math.max(-lim, Math.min(lim, ang[jIdx] + turn));
            }
        }
    }

    /** Is p (x,z) within r of the support area (convex hull of the feet)? */
    public static boolean insideSupport(List<Vec3> feet, Vec3 p, double r) {
        if (feet.isEmpty()) return false;
        List<double[]> pts = new ArrayList<>(); for (Vec3 f : feet) pts.add(new double[]{f.x, f.z});
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        List<double[]> hull = new ArrayList<>();
        if (pts.size() <= 2) hull.addAll(pts);
        else for (int pass = 0; pass < 2; pass++) {
            int start = hull.size();
            for (int i = 0; i < pts.size(); i++) {
                double[] q = pts.get(pass == 0 ? i : pts.size() - 1 - i);
                while (hull.size() >= start + 2 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), q) <= 0) hull.remove(hull.size() - 1);
                hull.add(q);
            }
            hull.remove(hull.size() - 1);
        }
        if (hull.isEmpty()) hull.add(pts.get(0));
        double[] q = {p.x, p.z};
        if (hull.size() >= 3) {
            boolean in = true;
            for (int i = 0; i < hull.size(); i++) if (cross(hull.get(i), hull.get((i + 1) % hull.size()), q) < 0) { in = false; break; }
            if (in) return true;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < hull.size(); i++) best = Math.min(best, segDist(hull.get(i), hull.get((i + 1) % hull.size()), q));
        return best <= r;
    }
    private static double cross(double[] o, double[] a, double[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }
    private static double segDist(double[] a, double[] b, double[] p) {
        double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / l2));
        double ex = a[0] + t * dx - p[0], ez = a[1] + t * dz - p[1];
        return Math.sqrt(ex * ex + ez * ez);
    }
}
