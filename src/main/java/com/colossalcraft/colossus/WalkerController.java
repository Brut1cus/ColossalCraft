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
 *  1. Contact: planted feet are locked in world space (until they slip or lift).
 *  2. Gait: a group of feet lifts only after the centre of mass is over the other feet; it swings in an arc to a spot ahead and plants.
 *  3. Balance: works out the force the Hull needs (track the pilot's speed, keep the weight over the support feet).
 *  4. Ground reaction: each planted foot can push sideways at most min(friction * load, hip torque / hip height). Too much -> the foot slips.
 *  5. The Hull integrates those forces (+ gravity, thrust). Legs are rigid struts: the Hull can't get further from a planted foot than the leg reaches.
 *  6. IK: joint angles are solved so every leg reaches its foot. Those angles are what is drawn.
 */
public class WalkerController {
    public enum State { STANCE, SWING, AIR }

    public State[] state = new State[0];
    public Vec3[] foot = new Vec3[0];        // world position of each foot
    private Vec3[] swingFrom = new Vec3[0], swingTo = new Vec3[0];
    private double[] swingT = new double[0];
    public boolean[] slipping = new boolean[0];
    private int nextGroup = 0, waitingGroup = -1;
    private Vec3 track;
    public boolean unbalanced;
    private int outsideTicks;
    public String status = "";
    private int legsHash;

    private static final double KP = 8, KD = 5;

    public boolean usable(StructureModel m) { return !m.legChains.isEmpty(); }

    /** Stop driving the legs (they go back to their normal joint behaviour). */
    public void release(StructureModel m) {
        if (m.ikAngles != null) Arrays.fill(m.ikAngles, Double.NaN);
        legsHash = 0;
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

    /** Where foot i is right now according to the Hull pose and current joint angles. */
    private Vec3 footFromPose(ColossusEntity c, int i) {
        StructureModel.LegChain L = c.model.legChains.get(i);
        Vector3d v = c.segWorld(L.footSeg()).transformPosition(new Vector3d(L.foot().x, L.foot().y, L.foot().z));
        return new Vec3(v.x, v.y, v.z);
    }
    private Vec3 hip(ColossusEntity c, int i) {
        StructureModel.LegChain L = c.model.legChains.get(i);
        Vector3d v = c.segWorld(L.bodySeg()).transformPosition(new Vector3d(L.hip().x, L.hip().y, L.hip().z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** Top of the ground under (x, z), searching down from y; NaN if nothing within 3 blocks. */
    public static double groundY(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 0.4); by >= (int) Math.floor(y) - 3; by--) {
            mp.set(bx, by, bz);
            BlockState s = level.getBlockState(mp);
            VoxelShape sh = s.getCollisionShape(level, mp);
            if (!sh.isEmpty()) { double top = by + sh.max(net.minecraft.core.Direction.Axis.Y); if (top <= y + 0.45) return top; }
        }
        return Double.NaN;
    }
    private static double groundMu(Level level, Vec3 p) {
        BlockState s = level.getBlockState(BlockPos.containing(p.x, p.y - 0.05, p.z));
        return Math.max(0.02, Math.min(1.2, 2.0 * (1 - s.getBlock().getFriction())));
    }

    // ------------------------------------------------------------------ main step

    /**
     * @param ext   outside forces on the Hull besides gravity (thrust, buoyancy, lift), N
     * @param ratio how much of the wanted power is available (0..1)
     * @param fwd   pilot forward input (-1..1)
     */
    public void step(ColossusEntity c, Vec3 ext, double ratio, double fwd) {
        ensure(c);
        StructureModel m = c.model;
        Level level = c.level();
        double dt = 0.05, M = m.mass, g = StructureModel.G;
        int n = m.legChains.size();
        int groups = 1; for (StructureModel.LegChain L : m.legChains) groups = Math.max(groups, L.group() + 1);
        double yw = c.yaw, cs = Math.cos(yw), sn = Math.sin(yw);
        Vec3 fl = Vec3.atLowerCornerOf(m.forward.getNormal());
        Vec3 fW = new Vec3(fl.x * cs - fl.z * sn, 0, fl.x * sn + fl.z * cs), rW = new Vec3(-fW.z, 0, fW.x);
        double cycle = 1 / Math.max(0.05, m.gaitSpeed), swingDur = cycle / Math.max(2, groups) * (groups == 1 ? 1 : 1);
        double reachH = Double.MAX_VALUE;
        for (StructureModel.LegChain L : m.legChains) reachH = Math.min(reachH, L.length() * Math.sin(Math.min(L.limits()[0], Math.toRadians(60))));
        double stepMax = 0.6 * reachH;
        double vMax = stepMax / cycle * m.gaitScale();
        Vec3 vCmd = fW.scale(Math.max(-1, Math.min(1, fwd)) * vMax);

        Vec3[] H = new Vec3[n];
        for (int i = 0; i < n; i++) H[i] = hip(c, i);

        // ---- 1. feet: touchdown, swing progress
        for (int i = 0; i < n; i++) {
            if (state[i] == State.AIR) {
                Vec3 f = footFromPose(c, i);
                double gy = groundY(level, f.x, f.y, f.z);
                if (!Double.isNaN(gy) && f.y - gy < 0.15) { state[i] = State.STANCE; foot[i] = new Vec3(f.x, gy, f.z); }
                else foot[i] = f;
            } else if (state[i] == State.SWING) {
                swingT[i] += dt / swingDur;
                double t = Math.min(1, swingT[i]);
                double lift = 0.25 * m.legChains.get(i).length();
                Vec3 p = swingFrom[i].add(swingTo[i].subtract(swingFrom[i]).scale(t)).add(0, Math.sin(Math.PI * t) * lift, 0);
                foot[i] = p;
                if (t >= 1) {
                    double gy = groundY(level, p.x, p.y + 0.5, p.z);
                    if (!Double.isNaN(gy)) { foot[i] = new Vec3(p.x, gy, p.z); state[i] = State.STANCE; }
                    else { swingTo[i] = swingTo[i].subtract(0, 0.3, 0); swingFrom[i] = foot[i]; swingT[i] = 0.7; } // reach down for the ground
                }
            }
        }

        // ---- 2. gait: lift the next group only when the weight sits over the other feet
        boolean anySwing = false; for (State s : state) anySwing |= s == State.SWING;
        boolean wantStep = Math.abs(fwd) > 0.01;
        for (int i = 0; i < n && !wantStep; i++) if (state[i] == State.STANCE) {
            Vec3 off = foot[i].subtract(H[i]); off = new Vec3(off.x, 0, off.z);
            if (off.length() > 0.85 * reachH) wantStep = true; // a foot got left too far behind/ahead
        }
        waitingGroup = -1;
        if (!anySwing && wantStep && groups > 1) {
            int G = nextGroup % groups;
            List<Vec3> others = new ArrayList<>(); boolean othersDown = true;
            for (int i = 0; i < n; i++) if (m.legChains.get(i).group() != G) { if (state[i] == State.STANCE) others.add(foot[i]); else othersDown = false; }
            if (othersDown && !others.isEmpty()) {
                double margin = margin(m);
                if (insideSupport(others, c.position(), 0.5 + margin)) {
                    for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == G && state[i] == State.STANCE) {
                        Vec3 hipProj = new Vec3(H[i].x, foot[i].y, H[i].z);
                        Vec3 vh = new Vec3(c.vel.x, 0, c.vel.z);
                        Vec3 dir = vCmd.lengthSqr() > 1e-6 ? vCmd.normalize() : fW;
                        double stepLen = Math.min(stepMax, Math.max(vCmd.length(), vh.length()) * cycle);
                        Vec3 to = hipProj.add(dir.scale(stepLen * 0.5)).add(vh.scale(swingDur * 0.5)); // Raibert-style placement
                        Vec3 off = to.subtract(hipProj); if (off.length() > stepMax) to = hipProj.add(off.normalize().scale(stepMax));
                        swingFrom[i] = foot[i]; swingTo[i] = to; swingT[i] = 0; state[i] = State.SWING;
                    }
                    nextGroup = G + 1;
                } else waitingGroup = G; // shift weight first
            }
        }

        // ---- 3. balance controller
        List<Integer> planted = new ArrayList<>();
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) planted.add(i);
        Vec3 com = c.position();
        if (planted.isEmpty()) {
            // nothing on the ground: free fall
            c.vel = c.vel.add(ext.scale(dt / M)).add(0, -g * dt, 0);
            c.moveBody(c.vel.scale(dt));
            c.onGroundC = false;
            status = "airborne";
            solveAllIK(c);
            unbalanced = false;
            return;
        }
        if (track == null || track.subtract(com).multiply(1, 0, 1).length() > 1.5) track = new Vec3(com.x, 0, com.z);
        track = track.add(vCmd.scale(dt));
        Vec3 desired = track;
        List<Vec3> support = new ArrayList<>();
        if (waitingGroup >= 0) { for (int i : planted) if (m.legChains.get(i).group() != waitingGroup) support.add(foot[i]); }
        else for (int i : planted) support.add(foot[i]);
        boolean waiting = waitingGroup >= 0;
        if (!support.isEmpty()) {
            Vec3 sc = Vec3.ZERO; for (Vec3 p : support) sc = sc.add(p); sc = sc.scale(1.0 / support.size());
            if (waiting) { desired = new Vec3(sc.x, 0, sc.z); track = new Vec3(com.x, 0, com.z); } // stop and put the weight over the foot that will carry it
            else desired = desired.add(rW.scale(sc.subtract(desired).dot(rW)));                  // lateral weight shift while walking
        }
        Vec3 vh = new Vec3(c.vel.x, 0, c.vel.z);
        Vec3 vDes = vCmd;
        // brake before a planted leg runs out of reach: the Hull may not outrun its feet
        for (int i : planted) {
            Vec3 o = H[i].subtract(foot[i]); o = new Vec3(o.x, 0, o.z);
            double ol = o.length(); if (ol < 1e-6) continue;
            Vec3 od = o.scale(1 / ol);
            double room = Math.max(0, 0.95 * reachH - ol);       // how much further the hip may go from this foot
            double vMaxAway = Math.sqrt(2 * 3.0 * room);            // stop in time at ~3 m/s^2
            double away = vDes.dot(od);
            if (away > vMaxAway) vDes = vDes.subtract(od.scale(away - vMaxAway));
            double tAway = track.subtract(new Vec3(foot[i].x, 0, foot[i].z)).dot(od);
            if (tAway > 0.95 * reachH) track = track.subtract(od.scale(tAway - 0.95 * reachH));
        }
        if (waiting) vDes = Vec3.ZERO;
        else desired = new Vec3(track.x, 0, track.z).add(rW.scale(desired.subtract(track).dot(rW)));
        Vec3 aDes = new Vec3(desired.x - com.x, 0, desired.z - com.z).scale(KP).add(vDes.subtract(vh).scale(KD));

        // ---- 4. ground reaction limits: friction and hip torque
        double load = M * g / planted.size();
        double capSum = 0, fricSum = 0;
        for (int i : planted) {
            StructureModel.LegChain L = m.legChains.get(i);
            double mu = Math.min(L.mu(), groundMu(level, foot[i]));
            double h = Math.max(0.5, H[i].y - foot[i].y);
            double fric = mu * load, torque = L.hipTorque() * ratio / h;
            fricSum += fric; capSum += Math.min(fric, torque);
        }
        Vec3 Fdem = aDes.scale(M);
        Vec3 Fh = Fdem.length() > capSum ? (Fdem.length() < 1e-9 ? Vec3.ZERO : Fdem.normalize().scale(capSum)) : Fdem;
        boolean slip = Fdem.length() > fricSum + 1e-6 && fricSum <= capSum + 1e-6;
        for (int i : planted) slipping[i] = slip;

        // ---- 5. integrate the Hull from ground reaction + outside forces
        Vec3 F = Fh.add(ext.x, ext.y, ext.z);
        c.vel = c.vel.add(F.scale(dt / M)).add(0, -g * dt, 0);
        Vec3 before = c.position();
        c.moveBody(c.vel.scale(dt));
        if (slip) { // feet slide along with part of the Hull's motion
            Vec3 moved = c.position().subtract(before).multiply(1, 0, 1).scale(1 - fricSum / Math.max(1e-6, Fdem.length()));
            for (int i : planted) foot[i] = foot[i].add(moved);
        }
        // rigid legs: the Hull can't be further from (or, for knee-less legs, closer to) a planted foot than the leg allows
        // A stiff leg pushes along its own axis: its sideways share is load * tan(leg angle).
        // The foot holds only while tan(angle) <= static grip; past that only the upward support acts and the foot slides.
        for (int it = 0; it < 3; it++) {
            Vec3 corr = Vec3.ZERO; int k = 0;
            for (int i : planted) {
                StructureModel.LegChain L = m.legChains.get(i);
                Vec3 hp = hip(c, i), d = hp.subtract(foot[i]);
                double len = d.length();
                double target = len > L.length() ? L.length() : len < L.minLen() ? L.minLen() : len;
                if (target == len || len < 1e-6) continue;
                Vec3 ci = d.scale((target - len) / len);              // move the hip along the leg
                double tan = Math.sqrt(d.x * d.x + d.z * d.z) / Math.max(1e-6, Math.abs(d.y));
                double muS = 1.25 * Math.min(L.mu(), groundMu(level, foot[i]));
                if (tan > muS) {                                        // can't hold sideways: foot slides, Hull only gets the vertical part
                    foot[i] = foot[i].add(-ci.x, 0, -ci.z); slipping[i] = true;
                    ci = new Vec3(0, ci.y, 0);
                }
                corr = corr.add(ci); k++;
            }
            if (k == 0) break;
            corr = corr.scale(1.0 / k);
            c.setPos(c.getX() + corr.x, c.getY() + corr.y, c.getZ() + corr.z);
            if (corr.y > 0 && c.vel.y < 0) c.vel = new Vec3(c.vel.x, 0, c.vel.z); // the legs hold the weight
        }
        c.onGroundC = true;

        // ---- 6. IK and balance check
        solveAllIK(c);
        List<Vec3> feetDown = new ArrayList<>(); for (int i : planted) feetDown.add(foot[i]);
        boolean outside = !insideSupport(feetDown, c.position(), 0.5 + margin(m));
        outsideTicks = outside ? outsideTicks + 1 : 0;
        unbalanced = outsideTicks > 15; // brief excursions are caught by the next step
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : " ").append(state[i] == State.STANCE ? (slipping[i] ? "SLIP" : "DOWN") : state[i] == State.SWING ? "STEP" : "AIR");
        status = sb + (waitingGroup >= 0 ? "  shifting weight" : "");
    }

    private static double margin(StructureModel m) { return m.coreGyro <= 0 ? 0 : 0.25 + 1.5 * Math.min(1, m.coreGyro / m.mass); }

    private void solveAllIK(ColossusEntity c) {
        StructureModel m = c.model;
        System.arraycopy(m.ikAngles, 0, m.ikPrev, 0, m.ikAngles.length);
        for (int i = 0; i < m.legChains.size(); i++) {
            StructureModel.LegChain L = m.legChains.get(i);
            Matrix4d inv = new Matrix4d(c.segWorld(L.bodySeg())).invert();
            Vector3d t = inv.transformPosition(new Vector3d(foot[i].x, foot[i].y, foot[i].z));
            double[] ang = new double[L.joints().size()];
            for (int k = 0; k < ang.length; k++) { double a = m.ikAngles[L.joints().get(k)]; ang[k] = Double.isNaN(a) ? 0 : a; }
            solveIK(m, L, new Vec3(t.x, t.y, t.z), ang);
            for (int k = 0; k < ang.length; k++) m.ikAngles[L.joints().get(k)] = ang[k];
        }
    }

    /** Joint transform (rest frame): turn by angle around the joint's axis through its pivot. */
    private static Matrix4d jt(StructureModel.Joint j, double a) {
        Vec3 ax = StructureModel.axisVec(j.axis);
        return new Matrix4d().translate(j.pivot.x, j.pivot.y, j.pivot.z).rotate(a, ax.x, ax.y, ax.z).translate(-j.pivot.x, -j.pivot.y, -j.pivot.z);
    }

    /** Cyclic coordinate descent over the leg's hinge joints, within each joint's limit. Target is in the body's local frame. */
    public static void solveIK(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang) {
        int k = ang.length;
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
                Vector3d f = all.transformPosition(new Vector3d(L.foot().x, L.foot().y, L.foot().z));
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
