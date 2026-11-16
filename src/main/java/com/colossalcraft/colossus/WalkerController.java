package com.colossalcraft.colossus;

import com.colossalcraft.ColossalCraft;
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
 * Feet-first walker: the Colossus stands on its feet and is placed from the lowest point up.
 *
 * Each tick:
 *  1. Contact: planted feet are locked in world space. A foot plants on the highest open ground under any of its sole blocks.
 *  2. Gait: a clock runs through the step cycle. Each leg group lifts when its phase passes the stance share (Locomotion Controller),
 *     but only once the weight is over the other feet; until then the clock waits and the body leans. Steps land on the real ground.
 *  3. Body: moves at the gait's own speed (stride / stance time), leans sideways just enough over the feet carrying the weight,
 *     and changes speed only as fast as grip and hip motors allow. Its height comes from the planted feet (knee legs can crouch;
 *     weak knees sag). Legs are struts with reach limits.
 *  4. IK (Analytic 2-link, FABRIK or CCD) bends every leg to its foot; any leg block inside the ground lifts that foot.
 *     If the weight stays outside the feet, it tips over.
 */
public class WalkerController {
    public enum State { STANCE, SWING, AIR }

    public State[] state = new State[0];
    public Vec3[] foot = new Vec3[0];        // world contact point of each foot (centre of the soles, on the ground)
    private Vec3[] swingFrom = new Vec3[0], swingTo = new Vec3[0];
    private double[] swingT = new double[0], swingLift = new double[0];
    /** How far each foot's IK target is raised so no block of the leg sinks into the ground (lowest-point collision). */
    private double[] footRaise = new double[0];
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
    /** Is any foot standing on the ground? */
    public boolean planted() { for (State st : state) if (st == State.STANCE) return true; return false; }

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
        state = new State[n]; foot = new Vec3[n]; swingFrom = new Vec3[n]; swingTo = new Vec3[n]; swingT = new double[n]; swingLift = new double[n]; footRaise = new double[n]; slipping = new boolean[n];
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
        boolean open = false; // a surface only counts with open space above it (not the inside of a wall)
        for (int by = (int) Math.floor(y + 1.0); by >= (int) Math.floor(y) - 3; by--) {
            mp.set(bx, by, bz);
            BlockState s = level.getBlockState(mp);
            VoxelShape sh = s.getCollisionShape(level, mp);
            if (sh.isEmpty()) open = true;
            else if (open || sh.max(net.minecraft.core.Direction.Axis.Y) < 0.99) return by + sh.max(net.minecraft.core.Direction.Axis.Y);
        }
        return Double.NaN;
    }
    /** Ground under (x, z) searching down from 'top' for 'depth' blocks. NaN if none. */
    public static double groundFrom(Level level, double x, double top, double z, int depth) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), t = (int) Math.floor(top);
        boolean open = false;
        for (int by = t; by >= t - depth; by--) {
            mp.set(bx, by, bz);
            VoxelShape sh = level.getBlockState(mp).getCollisionShape(level, mp);
            if (sh.isEmpty()) open = true;
            else if (open) { double y = by + sh.max(net.minecraft.core.Direction.Axis.Y); if (y <= top + 1e-6) return y; }
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
        return StructureModel.grip(s.getBlock().getFriction());
    }
    private static double margin(StructureModel m) { return m.coreGyro <= 0 ? 0 : 0.25 + 1.5 * Math.min(1, m.coreGyro / m.mass); }

    // ------------------------------------------------------------------ main step

    /** Gait clock in step cycles: each leg group lifts when its phase passes the stance share. */
    private double clock;
    private double lastYaw = Double.NaN;

    /**
     * Feet first: planted feet are fixed, the gait clock decides when each group steps, and the body is placed from the feet up.
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
        double speed = m.gaitSpeed * (sprint ? 1.6 : 1) * Math.max(0.1, m.gaitScale()); // cycles per second
        double cycle = 1 / Math.max(0.05, speed), duty = m.duty();
        double swingDur = (1 - duty) * cycle;
        height = Math.max(0.6, Math.min(1.15, height + raise * 0.4 * dt));
        double reachH = Double.MAX_VALUE;
        for (StructureModel.LegChain L : m.legChains) reachH = Math.min(reachH, L.length() * Math.sin(Math.min(L.limits()[0], Math.toRadians(60))));
        double stride = Math.min(1.6 * reachH, m.gaitStride * 1.2 * reachH);  // foot travel per cycle, relative to the hip
        double vGait = stride / (duty * cycle);                                  // the speed this gait walks at
        double in = Math.max(-1, Math.min(1, fwd));
        Vec3 vCmd = fW.scale(in * vGait);
        double yawRate = Double.isNaN(lastYaw) ? 0 : wrap(yw - lastYaw) / dt; lastYaw = yw;

        if (launchTicks > 0) launchTicks--;
        for (int i = 0; i < n; i++) footRaise[i] = Math.max(0, footRaise[i] - 0.05);
        Vec3[] H = new Vec3[n], neutral = new Vec3[n];
        for (int i = 0; i < n; i++) {
            H[i] = hip(c, i);
            StructureModel.LegChain L = m.legChains.get(i);
            Vec3 o = levelOffset(c, L.hip(), L.foot());
            neutral[i] = new Vec3(H[i].x + o.x, foot[i].y, H[i].z + o.z); // where the foot rests under its hip
        }

        // ---- 1. feet: touchdown, swing progress
        for (int i = 0; i < n; i++) {
            if (state[i] == State.AIR) {
                Vec3 f = footFromPose(c, i);
                double gy = soleGround(c, i, f);
                boolean mayLand = launchTicks <= 0 && c.vel.y <= 0.1;
                if (mayLand && !Double.isNaN(gy) && f.y - gy < 0.15) { state[i] = State.STANCE; foot[i] = new Vec3(f.x, gy, f.z); }
                else foot[i] = f;
            } else if (state[i] == State.SWING) {
                swingT[i] += dt / Math.max(0.1, swingDur);
                double t = Math.min(1, swingT[i]);
                Vec3 p = swingFrom[i].add(swingTo[i].subtract(swingFrom[i]).scale(t)).add(0, Math.sin(Math.PI * t) * swingLift[i], 0);
                foot[i] = p;
                if (t >= 1) {
                    double gy = soleGround(c, i, p.add(0, 0.5, 0));
                    if (!Double.isNaN(gy)) { foot[i] = new Vec3(p.x, gy, p.z); state[i] = State.STANCE; }
                    else { swingTo[i] = swingTo[i].subtract(0, 0.3, 0); swingFrom[i] = foot[i]; swingT[i] = 0.7; }
                }
            }
        }

        // ---- 2. gait clock: runs while walking or turning, while a foot is up, or until the feet are back under the hips
        boolean anySwing = false; for (State s : state) anySwing |= s == State.SWING;
        boolean moving = Math.abs(in) > 0.01 || Math.abs(yawRate) > 0.05;
        boolean[] off = new boolean[n]; boolean square = false;
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) {
            Vec3 d = foot[i].subtract(neutral[i]);
            off[i] = Math.sqrt(d.x * d.x + d.z * d.z) > 0.3 * stride + 0.25;
            square |= off[i];
        }
        waitingGroup = -1;
        double liftR = 0.5 + margin(m) + (biped ? 0.75 : 0);
        Vec3 com = c.position();
        if (groups > 1 && (moving || anySwing || square)) {
            double next = clock + speed * dt;
            for (int G = 0; G < groups && waitingGroup < 0; G++) {
                double o = (double) G / groups;
                if (Math.floor(clock + o - duty) == Math.floor(next + o - duty)) continue; // this group's lift moment isn't now
                boolean need = moving; List<Integer> lift = new ArrayList<>();
                for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == G && state[i] == State.STANCE) { lift.add(i); need |= off[i]; }
                if (lift.isEmpty() || !need) continue;
                List<Vec3> others = new ArrayList<>(); boolean othersDown = true;
                for (int i = 0; i < n; i++) if (m.legChains.get(i).group() != G) { if (state[i] == State.STANCE) others.add(foot[i]); else othersDown = false; }
                if (!othersDown || others.isEmpty() || !insideSupport(others, com, liftR)) { waitingGroup = G; next = clock; break; } // hold the clock: lean first
                for (int i : lift) {
                    // land where the hip will be halfway through the next stance (the gait's own speed), turned with the body
                    double tAhead = duty * cycle * 0.5 + swingDur * 0.5;
                    Vec3 to = neutral[i].add(vCmd.scale(tAhead));
                    double turn = yawRate * tAhead;
                    if (Math.abs(turn) > 1e-4) { Vec3 r = to.subtract(com); double ct = Math.cos(turn), st = Math.sin(turn); to = com.add(r.x * ct - r.z * st, r.y, r.x * st + r.z * ct); }
                    Vec3 offs = to.subtract(neutral[i]); offs = new Vec3(offs.x, 0, offs.z);
                    if (offs.length() > 0.6 * reachH + stride * 0.5) to = neutral[i].add(offs.normalize().scale(0.6 * reachH + stride * 0.5));
                    // land on the real ground there and lift high enough to clear what is in between
                    double top = Math.min(H[i].y - 0.3, foot[i].y + 2.5);
                    double gy = soleGround(c, i, new Vec3(to.x, top - 1, to.z));
                    if (!Double.isNaN(gy) && gy > foot[i].y - 3) to = new Vec3(to.x, gy, to.z);
                    else to = foot[i]; // a wall or a drop there: step in place
                    double clear = Math.max(foot[i].y, to.y);
                    for (int k = 1; k < 4; k++) {
                        Vec3 q = foot[i].add(to.subtract(foot[i]).scale(k / 4.0));
                        double qy = soleGround(c, i, new Vec3(q.x, top - 1, q.z));
                        if (!Double.isNaN(qy)) clear = Math.max(clear, qy);
                    }
                    swingLift[i] = m.gaitLift * m.legChains.get(i).length() + (clear - Math.max(foot[i].y, to.y)) + 0.1;
                    swingFrom[i] = foot[i]; swingTo[i] = to; swingT[i] = 0; state[i] = State.SWING;
                }
            }
            clock = next;
        }

        // ---- 3. body, from the feet up
        List<Integer> planted = new ArrayList<>();
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) planted.add(i);
        if (planted.isEmpty() || ext.y > M * g * 1.05) {
            // nothing on the ground (or thrust carries it): fall with every block colliding, legs relax to their built pose
            for (int i = 0; i < n; i++) if (state[i] == State.STANCE) state[i] = State.AIR;
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
        boolean waiting = waitingGroup >= 0;
        // forward: the gait's speed (none while leaning for a step), braked before a planted leg runs out of reach
        Vec3 vh = new Vec3(c.vel.x, 0, c.vel.z);
        Vec3 vDes = waiting ? Vec3.ZERO : vCmd;
        for (int i : planted) {
            Vec3 o = H[i].subtract(foot[i]); o = new Vec3(o.x, 0, o.z);
            double ol = o.length(); if (ol < 1e-6) continue;
            Vec3 od = o.scale(1 / ol);
            double room = Math.max(0, 0.95 * reachH - ol), vMaxAway = Math.sqrt(2 * 3.0 * room), away = vDes.dot(od);
            if (away > vMaxAway) vDes = vDes.subtract(od.scale(away - vMaxAway));
        }
        // sideways: lean just far enough over the feet that carry the weight (the gyro's margin covers the rest)
        List<Vec3> support = new ArrayList<>();
        if (waiting) support.addAll(others(planted, waitingGroup, m)); else for (int i : planted) support.add(foot[i]);
        double rLean = Math.max(0.3, liftR - 0.35);
        Vec3 lean = Vec3.ZERO;
        if (!support.isEmpty() && !insideSupport(support, com, rLean)) {
            Vec3 q = closestOnSupport(support, com), o = new Vec3(com.x - q.x, 0, com.z - q.z);
            Vec3 target = o.lengthSqr() < 1e-9 ? q : q.add(o.normalize().scale(rLean));
            lean = new Vec3(target.x - com.x, 0, target.z - com.z).scale(4);
            if (lean.length() > 2.5) lean = lean.normalize().scale(2.5);
        }
        vDes = vDes.add(lean);
        // grip: feet change the body's speed only as fast as friction and hip motors allow
        double load = M * g / planted.size(), fricSum = 0, capSum = 0, kneeCap = 0; boolean knees = false;
        double yWant = 0, minR = 1;
        for (int i : planted) {
            StructureModel.LegChain L = m.legChains.get(i);
            double mu = Math.min(L.mu(), groundMu(level, foot[i]));
            double h = Math.max(0.5, H[i].y - foot[i].y);
            double fric = mu * load; fricSum += fric; capSum += Math.min(fric, L.hipTorque() * ratio / h);
            if (L.joints().size() > 1) { knees = true; kneeCap += L.kneeTorque() * ratio / Math.max(0.3, 0.35 * L.length()); minR = Math.min(minR, minReach(m, L) / maxReach(m, L)); }
        }
        Vec3 dv = vDes.subtract(vh);
        double aMax = capSum / M;
        boolean slip = dv.length() > fricSum / M * dt + 1e-6 && fricSum <= capSum + 1e-6;
        if (dv.length() > aMax * dt) dv = dv.normalize().scale(aMax * dt);
        Vec3 vNew = vh.add(dv).add(ext.x * dt / M, 0, ext.z * dt / M);
        for (int i : planted) slipping[i] = slip;
        // height: each planted foot holds the body up at its built height (knee legs crouch with raise/lower);
        // weak knees let it sag, never below what the knees can fold to
        double hk = knees ? height : 1;
        if (knees) { double s = Math.max(0, Math.min(1, kneeCap / (M * g))); hk = minR + (hk - minR) * s; }
        for (int i : planted) {
            StructureModel.LegChain L = m.legChains.get(i);
            double legUp = L.hip().y - L.foot().y, bodyUp = m.com.y - L.hip().y;
            yWant += foot[i].y + footRaise[i] + legUp * (L.joints().size() > 1 ? hk : 1) + bodyUp;
        }
        yWant /= planted.size();
        double vy = Math.max(-6, Math.min(4, (yWant - c.getY()) * 6));
        c.vel = new Vec3(vNew.x, vy, vNew.z);
        c.moveBody(c.vel.scale(dt));
        // legs are struts: never longer than they reach, never shorter than the knees fold
        for (int it = 0; it < 3; it++) {
            Vec3 corr = Vec3.ZERO; int k = 0;
            for (int i : planted) {
                StructureModel.LegChain L = m.legChains.get(i);
                Vec3 hp = hip(c, i), e = effectorWorld(c, i), d = hp.subtract(e);
                double len = d.length();
                double maxL = L.joints().size() > 1 ? maxReach(m, L) : L.hip().distanceTo(L.effector());
                double minL = L.joints().size() > 1 ? minReach(m, L) : 0.97 * maxL;
                double target = len > maxL ? maxL : len < minL ? minL : len;
                if (target == len || len < 1e-6) continue;
                Vec3 ci = d.scale((target - len) / len);
                double tan = Math.sqrt(d.x * d.x + d.z * d.z) / Math.max(1e-6, Math.abs(d.y));
                double muS = 1.5 * Math.min(L.mu(), groundMu(level, foot[i]));
                if (L.joints().size() == 1 && tan > muS) { foot[i] = foot[i].add(-ci.x, 0, -ci.z); slipping[i] = true; ci = new Vec3(0, ci.y, 0); }
                corr = corr.add(ci); k++;
            }
            if (k == 0) break;
            corr = corr.scale(1.0 / k);
            c.setPos(c.getX() + corr.x, c.getY() + corr.y, c.getZ() + corr.z);
        }
        c.onGroundC = true;

        // ---- 4. IK, legs kept out of the ground, and tipping
        solveAllIK(c);
        keepLegsOutOfGround(c);
        // tipping: the weight must stay over the feet. A foot in mid-step counts where it will land (it is about to catch the weight).
        List<Vec3> feetDown = new ArrayList<>();
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) feetDown.add(foot[i]); else if (state[i] == State.SWING) feetDown.add(swingTo[i]);
        boolean outside = !insideSupport(feetDown, c.position(), 0.5 + margin(m) + (biped ? 0.75 : 0));
        outsideTicks = outside ? outsideTicks + 1 : Math.max(0, outsideTicks - 2);
        unbalanced = outsideTicks > 30;
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
        return foot[i].add(levelOffset(c, L.foot(), L.effector())).add(0, i < footRaise.length ? footRaise[i] : 0, 0);
    }
    /** Shortest the leg can get: every joint below the hip bent to its limit (same way). */
    private static double minReach(StructureModel m, StructureModel.LegChain L) {
        double best = 0;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            Matrix4d all = new Matrix4d();
            for (int k = 1; k < L.joints().size(); k++) all.mul(jt(m.joints.get(L.joints().get(k)), sgn * L.limits()[k]));
            Vector3d e = all.transformPosition(new Vector3d(L.effector().x, L.effector().y, L.effector().z));
            best = Math.max(best, new Vec3(e.x, e.y, e.z).distanceTo(L.hip()));
        }
        return Math.max(best, 0.3 * maxReach(m, L));
    }
    /** Parts of leg i (everything between the hip and the foot). */
    private static List<Integer> legSegs(StructureModel m, StructureModel.LegChain L) {
        List<Integer> out = new ArrayList<>();
        int cur = L.footSeg(), guard = 0, hipId = L.joints().get(0);
        while (cur > 0 && guard++ < 1000) {
            out.add(cur);
            StructureModel.Seg sg = m.segs.get(cur);
            if (sg.parentJoint < 0 || sg.parentJoint == hipId) break;
            cur = Math.max(0, m.joints.get(sg.parentJoint).parentSeg);
        }
        return out;
    }
    /** How deep the lowest corner of any block of leg i is inside the ground (0 if none). */
    private static double legPenetration(ColossusEntity c, StructureModel.LegChain L, Matrix4d[] W) {
        Level level = c.level();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        double deepest = 0;
        for (int sgi : legSegs(c.model, L)) {
            Matrix4d w = W[sgi];
            for (BlockPos b : c.model.segs.get(sgi).blocks) {
                if (c.model.segs.get(sgi).blocks.contains(b.below())) continue; // only the bottom faces
                for (int k = 0; k < 4; k++) {
                    double lx = b.getX() + ((k & 1) == 0 ? 0.05 : 0.95), lz = b.getZ() + ((k & 2) == 0 ? 0.05 : 0.95);
                    Vector3d pt = w.transformPosition(new Vector3d(lx, b.getY() + 0.02, lz));
                    double py = pt.y;
                    // inside a block? climb its column to the surface. A column taller than one block is a wall, not ground: lifting won't help.
                    double surface = Double.NaN;
                    for (int up = 0; up < 3; up++) {
                        mp.set((int) Math.floor(pt.x), (int) Math.floor(py) + up, (int) Math.floor(pt.z));
                        VoxelShape sh = level.getBlockState(mp).getCollisionShape(level, mp);
                        if (sh.isEmpty()) break;
                        double topY = mp.getY() + sh.max(net.minecraft.core.Direction.Axis.Y), botY = mp.getY() + sh.min(net.minecraft.core.Direction.Axis.Y);
                        if (up == 0 && (pt.y < botY - 1e-6 || pt.y >= topY)) break;
                        surface = topY;
                    }
                    if (!Double.isNaN(surface) && surface - pt.y <= 1.0) deepest = Math.max(deepest, surface - pt.y);
                }
            }
        }
        return Math.min(1.5, deepest);
    }
    /** Deepest any leg block is inside the ground right now (for tests and debugging). */
    public static double maxLegPenetration(ColossusEntity c) {
        Matrix4d[] W = c.freshSegWorlds(); double d = 0;
        for (StructureModel.LegChain L : c.model.legChains) d = Math.max(d, legPenetration(c, L, W));
        return d;
    }
    /** Lowest-point collision: if any block of a leg ends up in the ground, raise that foot's target and solve again. */
    private void keepLegsOutOfGround(ColossusEntity c) {
        StructureModel m = c.model;
        boolean digi = m.gaitMode == StructureModel.GAIT_DIGI;
        for (int pass = 0; pass < 3; pass++) {
            Matrix4d[] W = c.freshSegWorlds();
            boolean any = false;
            for (int i = 0; i < m.legChains.size(); i++) {
                if (state[i] == State.AIR) continue;
                double pen = legPenetration(c, m.legChains.get(i), W);
                if (pen < 0.01) continue;
                footRaise[i] = Math.min(2.0, footRaise[i] + pen); any = true;
                StructureModel.LegChain L = m.legChains.get(i);
                Matrix4d inv = new Matrix4d(c.segWorld(L.bodySeg())).invert();
                Vec3 tw = effectorWorld(c, i);
                Vector3d t = inv.transformPosition(new Vector3d(tw.x, tw.y, tw.z));
                double[] ang = new double[L.joints().size()];
                for (int k = 0; k < ang.length; k++) { double a = m.ikAngles[L.joints().get(k)]; ang[k] = Double.isNaN(a) ? 0 : a; }
                solve(m, L, new Vec3(t.x, t.y, t.z), ang, digi);
                for (int k = 0; k < ang.length; k++) m.ikAngles[L.joints().get(k)] = ang[k];
            }
            if (!any) break;
        }
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
    /** Nearest point (y = 0) of the support area (feet hull) to p. */
    public static Vec3 closestOnSupport(List<Vec3> feet, Vec3 p) {
        if (insideSupport(feet, p, 0)) return new Vec3(p.x, 0, p.z);
        double best = Double.MAX_VALUE; Vec3 out = new Vec3(feet.get(0).x, 0, feet.get(0).z);
        for (int i = 0; i < feet.size(); i++) for (int j = i; j < feet.size(); j++) { // every edge between feet covers the hull's edges
            double[] a = {feet.get(i).x, feet.get(i).z}, b = {feet.get(j).x, feet.get(j).z};
            double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
            double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p.x - a[0]) * dx + (p.z - a[1]) * dz) / l2));
            double qx = a[0] + t * dx, qz = a[1] + t * dz, d = (qx - p.x) * (qx - p.x) + (qz - p.z) * (qz - p.z);
            if (d < best) { best = d; out = new Vec3(qx, 0, qz); }
        }
        return out;
    }
    private static double cross(double[] o, double[] a, double[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }
    private static double segDist(double[] a, double[] b, double[] p) {
        double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / l2));
        double ex = a[0] + t * dx - p[0], ez = a[1] + t * dz - p[1];
        return Math.sqrt(ex * ex + ez * ez);
    }
}
