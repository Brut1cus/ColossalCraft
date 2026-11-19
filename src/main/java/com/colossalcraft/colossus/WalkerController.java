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
    private boolean[] swingObstacle = new boolean[0];
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
        if (m.ikAngles != null) for (StructureModel.LegChain L : m.legChains) for (int j : L.joints()) m.ikAngles[j] = Double.NaN; // other joints keep their own dynamics
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
        state = new State[n]; foot = new Vec3[n]; swingFrom = new Vec3[n]; swingTo = new Vec3[n]; swingT = new double[n]; swingLift = new double[n]; swingObstacle = new boolean[n]; footRaise = new double[n]; slipping = new boolean[n]; next = new Vec3[n]; nextOk = new boolean[n]; footYaw = new double[n]; Arrays.fill(footYaw, c.yaw);
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
    private static double margin(StructureModel m) {
        double g = m.coreGyro <= 0 ? 0 : 0.25 + 1.5 * Math.min(1, m.coreGyro / m.mass);
        return g + Math.min(0.75, m.ankleTorque() / Math.max(1, m.mass * StructureModel.G)); // powered ankles push back like a human's
    }

    // ------------------------------------------------------------------ main step

    /** Gait clock in step cycles: each leg group lifts when its phase passes the stance share. */
    private double clock;
    /** Fastest the walker can turn (rad/s): what its steps can follow. ColossusEntity eases the turn up to this. */
    public double turnRateMax = 0.3;
    /** Body yaw when each foot was planted: hip twist joints keep a planted foot pointing that way while the body turns. */
    private double[] footYaw = new double[0];
    /** Turn rate the pilot asks for (radians per second). The body only turns as far as its planted feet let it (see {@link #yawRateLimits}). */
    public double turnWant;
    /** How far the body may turn over a planted foot of leg i (radians): a hip twist joint turns the leg as far as its range lets it;
     *  a plain leg can only pivot its foot on the ground a little. */
    public double twistCap(StructureModel m, int i) {
        StructureModel.LegChain L = m.legChains.get(i);
        StructureModel.Joint hj = m.joints.get(L.joints().get(0));
        if (twistHip(m, L) || hj.universal) return Math.min(0.7, hj.limRad());
        return 0.2;
    }
    /**
     * Turning by walking: the body can only turn while every planted foot stays where it is, so each planted foot's twist limit
     * bounds the turn. Feet then step round one at a time (each landing pre-turned into the turn) to let the body turn further.
     * Returns the allowed {lowest, highest} turn rate for this tick; no limit while nothing is planted.
     */
    public double[] yawRateLimits(ColossusEntity c, double dt, double accel) {
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY; boolean any = false;
        for (int i = 0; i < state.length && i < c.model.legChains.size(); i++) {
            if (state[i] != State.STANCE) continue;
            any = true;
            double cap = twistCap(c.model, i), d = wrap(c.yaw - footYaw[i]);
            double up = cap - d, down = cap + d; // room left each way
            // slow down in time to stop at the limit (like a body braking over its foot), never a sudden halt
            hi = Math.min(hi, up > 0 ? Math.min(up / dt, Math.sqrt(2 * accel * up)) : up / dt * 0.3);
            lo = Math.max(lo, down > 0 ? -Math.min(down / dt, Math.sqrt(2 * accel * down)) : -down / dt * 0.3);
        }
        if (!any) return new double[]{lo, hi};
        if (lo > hi) { double mid = (lo + hi) / 2; lo = hi = mid; }
        return new double[]{lo, hi};
    }
    /** Largest twist between the body and any planted foot (radians), and how many steps have been taken (for tests). */
    public double maxTwist(ColossusEntity c) { double w = 0; for (int i = 0; i < state.length; i++) if (state[i] == State.STANCE) w = Math.max(w, Math.abs(wrap(c.yaw - footYaw[i])) - twistCap(c.model, i)); return w; }
    public int steps;
    /** A foot lands: it points a little into the turn, so the body can keep turning over it. */
    private boolean[] justLanded = new boolean[0];
    private void land(ColossusEntity c, int i) {
        if (justLanded.length != state.length) justLanded = new boolean[state.length];
        justLanded[i] = true;
        double lead = turnWant * 0.35;
        double cap = twistCap(c.model, i);
        footYaw[i] = c.yaw + Math.max(-cap * 0.8, Math.min(cap * 0.8, lead));
    }
    private double inSmooth, ySmooth = Double.NaN;
    private Vec3 lastAccel = Vec3.ZERO;
    /** Comfortable limits for the body: acceleration (m/s^2) and how fast acceleration may change (m/s^3). */
    private static final double A_COMFORT = 2.5, JERK = 18;
    /** A powered Step Controller is aboard: steps go to the best foothold it finds near where the gait wants the foot. */
    public boolean planner;
    /** Where each foot will step next (for the Step Controller screen), and whether a good foothold was found. */
    public Vec3[] next = new Vec3[0]; public boolean[] nextOk = new boolean[0];
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
        double speed = m.stepSpeed(sprint) * Math.max(0.1, m.gaitScale()); // cycles per second, capped by power and gears
        double cycle = 1 / Math.max(0.05, speed), duty = m.duty();
        double swingDur = (1 - duty) * cycle;
        height = Math.max(0.6, Math.min(1.15, height + raise * 0.4 * dt));
        double reachH = Double.MAX_VALUE;
        for (StructureModel.LegChain L : m.legChains) reachH = Math.min(reachH, L.length() * Math.sin(Math.min(L.limits()[0], Math.toRadians(60))));
        double stride = Math.min(1.6 * reachH, m.gaitStride * 1.2 * reachH);  // foot travel per cycle, relative to the hip
        double vGait = stride / (duty * cycle); lastStride = stride;                                  // the speed this gait walks at
        // the pilot's stick is eased in and out (about 0.6 s from stop to full), so the walker never lurches
        double inRaw = Math.max(-1, Math.min(1, fwd));
        double ramp = Math.abs(inRaw) < Math.abs(inSmooth) ? 0.3 : 0.6; // eases in over 0.6 s, out over 0.3 s (stops promptly)
        inSmooth += Math.max(-dt / ramp, Math.min(dt / ramp, inRaw - inSmooth));
        double in = Math.abs(inSmooth) < 0.02 && inRaw == 0 ? 0 : inSmooth;
        Vec3 vCmd = fW.scale(in * vGait);
        { // turning: as fast as the steps can carry the feet round (a foot travels about a stride per step)
            double rad = 0; for (StructureModel.LegChain L : m.legChains) { Vec3 d = L.hip().subtract(m.com); rad += Math.sqrt(d.x * d.x + d.z * d.z); }
            rad = Math.max(1, rad / Math.max(1, n));
            turnRateMax = Math.max(0.1, Math.min(1.2, 0.9 * vGait / rad));
        }
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
                if (mayLand && !Double.isNaN(gy) && f.y - gy < 0.15) { state[i] = State.STANCE; foot[i] = new Vec3(f.x, gy, f.z); footYaw[i] = c.yaw; }
                else foot[i] = f;
            } else if (state[i] == State.SWING) {
                swingT[i] += dt / Math.max(0.1, swingDur);
                double t = Math.min(1, swingT[i]);
                // up a step: reach the new height by mid-swing (clear the riser); down a step: drop late (clear the edge)
                double dyTo = swingTo[i].y - swingFrom[i].y;
                boolean climb = dyTo > 0.05 || swingObstacle[i]; // something to get over: go up, across, then down
                double ty = dyTo > 0.05 ? Math.min(1, t / 0.35) : dyTo < -0.05 ? Math.max(0, (t - 0.65) / 0.35) : t;
                double tx = climb ? smoothstep(0.25, 0.8, t) : dyTo < -0.05 ? smoothstep(0, 0.75, t) : smoothstep(0, 1, t);
                double bump = climb ? Math.min(1, Math.min(t / 0.25, (1 - t) / 0.2)) : Math.sin(Math.PI * t);
                Vec3 h0 = swingFrom[i].add(swingTo[i].subtract(swingFrom[i]).scale(tx));
                Vec3 p = new Vec3(h0.x, swingFrom[i].y + dyTo * ty + bump * swingLift[i], h0.z);
                foot[i] = p;
                if (t >= 1) {
                    double gy = soleGround(c, i, p.add(0, 0.5, 0));
                    // the foot lands where the leg really put it (a heavy or weak leg lags), unless that's far from the planned spot
                    Vec3 real = footFromPose(c, i);
                    Vec3 at = Math.hypot(real.x - p.x, real.z - p.z) < 1.0 ? real : p;
                    double gy2 = soleGround(c, i, new Vec3(at.x, p.y + 0.5, at.z));
                    if (!Double.isNaN(gy2) && Math.abs(gy2 - gy) < 0.6) { foot[i] = new Vec3(at.x, gy2, at.z); state[i] = State.STANCE; land(c, i); }
                    else if (!Double.isNaN(gy)) { foot[i] = new Vec3(p.x, gy, p.z); state[i] = State.STANCE; land(c, i); }
                    else { swingTo[i] = swingTo[i].subtract(0, 0.3, 0); swingFrom[i] = foot[i]; swingT[i] = 0.7; }
                }
            }
        }

        // ---- 2. gait clock: runs while walking or turning, while a foot is up, or until the feet are back under the hips
        boolean anySwing = false; for (State s : state) anySwing |= s == State.SWING;
        boolean moving = Math.abs(in) > 0.01 || Math.abs(yawRate) > 0.05 || Math.abs(turnWant) > 0.05; // asked to turn: step, so the body can turn
        double turnPlan = Math.abs(turnWant) > Math.abs(yawRate) ? turnWant : yawRate; // where the feet should go: the turn asked for
        // where the feet aim: under the hip, moved on by the speed asked for (the body leans into it while the feet hold it)
        Vec3 vPlace = vCmd;
        boolean[] off = new boolean[n]; boolean square = false;
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) {
            Vec3 d = foot[i].subtract(neutral[i]);
            off[i] = Math.sqrt(d.x * d.x + d.z * d.z) > 0.3 * stride + 0.25 || Math.abs(wrap(c.yaw - footYaw[i])) > 0.6 * twistCap(m, i); // twisted far: step round
            square |= off[i];
        }
        waitingGroup = -1;
        // a foot lifts only once the weight (and where it is heading) is over the feet that stay down: they must carry it alone
        double liftR = 0.45 + margin(m) + (biped ? 0.15 : 0);
        Vec3 com = c.position();
        // ---- balance (Stage 2): the area the feet can press on, and where the body would come to rest (capture point)
        supportHull = supportArea(c);
        double footY = 0; int nd = 0; for (int i = 0; i < n; i++) if (state[i] == State.STANCE) { footY += foot[i].y; nd++; }
        omega = Math.sqrt(g / Math.max(0.5, com.y - (nd > 0 ? footY / nd : com.y - 2)));
        capture = new Vec3(com.x + c.vel.x / omega, com.y, com.z + c.vel.z / omega);
        // walking is controlled falling forward: the next step catches it. Only past what a normal step covers is a catch step needed.
        boolean cpOut = supportHull.size() > 0 && hullDistance(supportHull, capture.x, capture.z) > 0.15 + (moving ? 0.5 * stride : 0);
        cpOutTicks = cpOut ? cpOutTicks + 1 : 0;
        // catch step: where the body would come to rest is outside the feet (a push, an overhang, stopping hard): step under it
        if (groups > 1 && !anySwing && (cpOutTicks > 2 || outsideTicks > 6)) {
            List<Vec3> down = new ArrayList<>(); for (int i = 0; i < n; i++) if (state[i] == State.STANCE) down.add(foot[i]);
            if (down.size() == n) {
                Vec3 sc = Vec3.ZERO; for (Vec3 f : down) sc = sc.add(f); sc = sc.scale(1.0 / n);
                Vec3 dir = new Vec3(capture.x - sc.x, 0, capture.z - sc.z); // towards where the body would come to rest
                if (dir.lengthSqr() > 1e-4) {
                    int bestG = -1; double best = Double.NEGATIVE_INFINITY;
                    for (int G = 0; G < groups; G++) { double sum = 0; for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == G) sum -= foot[i].subtract(sc).dot(dir); if (sum > best) { best = sum; bestG = G; } }
                    double reachOut = 0.6 * reachH + stride * 0.5;
                    for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == bestG) {
                        Vec3 to = foot[i].add(dir.scale(Math.min(1.6, 1 + 0.6 / Math.max(0.3, dir.length()))));
                        Vec3 off2 = to.subtract(neutral[i]); off2 = new Vec3(off2.x, 0, off2.z);
                        if (off2.length() > reachOut) to = neutral[i].add(off2.normalize().scale(reachOut));
                        startSwing(c, i, to, H[i]);
                    }
                    anySwing = true; outsideTicks = 0; cpOutTicks = 0; catchSteps++;
                }
            }
        }
        if (groups > 1 && (moving || anySwing || square)) {
            double next = clock + speed * dt;
            for (int G = 0; G < groups && waitingGroup < 0; G++) {
                double o = (double) G / groups;
                if (Math.floor(clock + o - duty) == Math.floor(next + o - duty)) continue; // this group's lift moment isn't now
                boolean need = moving; List<Integer> lift = new ArrayList<>();
                for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == G && state[i] == State.STANCE) { lift.add(i); need |= off[i]; }
                if (lift.isEmpty() || !need) continue;
                if (!canLift(G, n, m, com, rW, fW, liftR, stride)) {
                    // can't lift this foot yet (the weight isn't over the others). Waited long? Step another foot instead, one it is safe to lift.
                    int alt = -1;
                    if (waitTicks > 30) for (int G2 = 0; G2 < groups && alt < 0; G2++) if (G2 != G && canLift(G2, n, m, com, rW, fW, liftR, stride)) alt = G2;
                    if (alt < 0) { waitingGroup = G; next = clock; waitTicks++; break; } // hold the clock: lean first
                    lift.clear(); for (int i = 0; i < n; i++) if (m.legChains.get(i).group() == alt && state[i] == State.STANCE) lift.add(i);
                    clock = Math.floor(clock + (double) G / groups) + (double) (alt - G) / groups; next = clock + speed * dt; // the gait's timing follows the foot that really stepped
                }
                waitTicks = 0;
                for (int i : lift) {
                    Vec3 to = nominalTo(i, neutral[i], vPlace, duty * cycle * 0.5 + swingDur * 0.5, turnPlan, com, reachH, stride);
                    // each foot keeps to its own track: the body leans over the other foot before a step, but the foot doesn't follow it in
                    double keep = 1 - Math.min(1, Math.abs(turnPlan) / 0.3);
                    if (keep > 0) to = to.add(rW.scale(foot[i].subtract(to).dot(rW) * keep));
                    startSwing(c, i, to, H[i]);
                }
            }
            clock = next;
        }

        // ---- Step Controller preview: where each foot will go next
        if (planner && c.tickCount % 5 == 0) for (int i = 0; i < n; i++) {
            if (state[i] == State.SWING) { next[i] = swingTo[i]; nextOk[i] = true; continue; }
            if (state[i] != State.STANCE) { next[i] = null; continue; }
            Vec3 nom = nominalTo(i, neutral[i], vPlace, duty * cycle * 0.5 + swingDur * 0.5, turnPlan, com, reachH, stride);
            Vec3 f = pickFoothold(c, i, nom, avoidFor(i));
            next[i] = f != null ? f : nom; nextOk[i] = f != null;
        }
        if (!planner) Arrays.fill(next, null);

        m.walkClock = clock; m.walkAmount += ((moving ? 1 : 0) - m.walkAmount) * 0.1; // shoulders swing in time with the steps
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
        int comingG = waiting ? waitingGroup : (moving && groups > 1 ? nextLiftGroup(groups, duty) : -1);
        if (comingG >= 0 && !others(planted, comingG, m).isEmpty()) support.addAll(others(planted, comingG, m)); // lean early over the feet that will carry the next step
        else for (int i : planted) support.add(foot[i]);
        double rLean = Math.max(0.2, liftR - 0.3);
        Vec3 lean = Vec3.ZERO;
        if (!support.isEmpty() && !insideSupport(support, com, rLean)) {
            Vec3 q = closestOnSupport(support, com), o = new Vec3(com.x - q.x, 0, com.z - q.z);
            Vec3 target = o.lengthSqr() < 1e-9 ? q : q.add(o.normalize().scale(rLean));
            // lean so the capture point (where the body would come to rest) lands on the target, never past it:
            // speed = w * distance makes the body arrive over the foot without overshooting (w = its natural sway rate)
            lean = new Vec3(target.x - com.x, 0, target.z - com.z).scale(omega);
            if (lean.length() > 1.5) lean = lean.normalize().scale(1.5);
        }
        vDes = vDes.add(lean);
        // equilibrium: standing still, the body settles its weight over the middle of its feet (like a person does)
        if (!moving && !waiting && !anySwing && !support.isEmpty()) {
            Vec3 cen = Vec3.ZERO; for (Vec3 f : support) cen = cen.add(f); cen = cen.scale(1.0 / support.size());
            Vec3 dc = new Vec3(cen.x - com.x, 0, cen.z - com.z).scale(omega * 0.8);
            if (dc.length() > 0.6) dc = dc.normalize().scale(0.6);
            vDes = vDes.add(dc);
        }
        // the hull's standing height: the Locomotion Controller's setting (raise/lower shift it), but no lower than the knees can hold
        double load = M * g / planted.size(), kneeCap = 0; boolean knees = false; double minR = 1;
        for (int i : planted) {
            StructureModel.LegChain L = m.legChains.get(i);
            if (L.joints().size() > 1) {
                knees = true; minR = Math.min(minR, minReach(m, L) / maxReach(m, L));
                double hold = L.kneeTorque() * ratio / Math.max(1, load * 0.5 * L.length());
                kneeCap = Math.max(kneeCap, hold >= 1 ? 0 : Math.sqrt(1 - hold * hold)); // lowest height share it can hold
            }
        }
        double yWant;
        // height: each planted foot holds the body up at its built height (knee legs crouch with raise/lower);
        // weak knees let it sag, never below what the knees can fold to
        // the hull stands at the Locomotion Controller's height (raise/lower move it), never lower than the knees can hold
        // (weak knees keep the legs straighter) and never lower than the legs can fold
        double hk = knees ? Math.min(1, Math.max(Math.max(minR, kneeCap), m.gaitHeight * height)) : 1;
        // From the feet up: each planted leg wants its hip at its standing height above its own foot. The body stays level
        // if the legs can take up the difference (knees bend, legs stretch); only what they can't take up tilts the body.
        int np = planted.size();
        double[] hf = new double[np], hr = new double[np], hh = new double[np];
        double levelH = 0, bodyUpAvg = 0;
        Vec3 fl0 = Vec3.atLowerCornerOf(m.forward.getNormal()), rl0 = new Vec3(-fl0.z, 0, fl0.x);
        for (int k = 0; k < np; k++) {
            StructureModel.LegChain L = m.legChains.get(planted.get(k));
            double legUp = L.hip().y - L.foot().y, bodyUp = m.com.y - L.hip().y;
            levelH += foot[planted.get(k)].y + footRaise[planted.get(k)] + legUp * (L.joints().size() > 1 ? hk : 1) + bodyUp;
            bodyUpAvg += bodyUp;
        }
        levelH /= np; bodyUpAvg /= np;
        for (int k = 0; k < np; k++) {
            int i = planted.get(k);
            StructureModel.LegChain L = m.legChains.get(i);
            Vec3 d = L.hip().subtract(m.com);
            hf[k] = d.dot(fl0); hr[k] = d.dot(rl0);
            double bodyUp = m.com.y - L.hip().y, ground = foot[i].y + footRaise[i], eff = L.effector().y - L.foot().y; // contact height; ankle above the sole
            double lo, hi;
            if (L.joints().size() > 1) { lo = ground + eff + minReach(m, L) * 1.02; hi = ground + eff + maxReach(m, L) * 0.97; }
            else { lo = hi = ground + (L.hip().y - L.foot().y); }
            hh[k] = Math.max(lo, Math.min(hi, levelH - bodyUp)); // this leg's hip: level if it can, else as close as it reaches
        }
        // fit a plane through the hips: height + forward slope + sideways slope (least squares)
        double sf = 0, sr = 0, sh = 0, sff = 0, srr = 0, sfr = 0, sfh = 0, srh = 0;
        for (int k = 0; k < np; k++) { sf += hf[k]; sr += hr[k]; sh += hh[k]; sff += hf[k] * hf[k]; srr += hr[k] * hr[k]; sfr += hf[k] * hr[k]; sfh += hf[k] * hh[k]; srh += hr[k] * hh[k]; }
        double mf = sf / np, mr = sr / np, mh = sh / np;
        double cff = sff / np - mf * mf, crr = srr / np - mr * mr, cfr = sfr / np - mf * mr, cfh = sfh / np - mf * mh, crh = srh / np - mr * mh;
        double slopeF = 0, slopeR = 0;
        double det = cff * crr - cfr * cfr;
        if (det > 1e-3) { slopeF = (cfh * crr - crh * cfr) / det; slopeR = (crh * cff - cfh * cfr) / det; }
        else if (crr > 1e-3 && crr >= cff) slopeR = crh / crr;   // feet side by side: only a sideways tilt is known
        else if (cff > 1e-3) slopeF = cfh / cff;                 // feet one behind the other: only a forward tilt
        double pitchT = Math.max(-0.4, Math.min(0.4, Math.atan(slopeF))), rollT = Math.max(-0.4, Math.min(0.4, -Math.atan(slopeR)));
        c.bodyPitch += (pitchT - c.bodyPitch) * 0.15; c.bodyRoll += (rollT - c.bodyRoll) * 0.15;
        yWant = mh - slopeF * mf - slopeR * mr + bodyUpAvg; // hip plane height under the centre of mass, plus the body above the hips
        // ---- forces (Stage 1 physics): the body moves only by what its planted feet push against the ground, plus gravity and thrust.
        // Each foot pushes up at most what its leg's joints can hold in their current bend, and sideways at most its grip
        // (friction = grip x the weight on it) and what its hip motor can push. Stopping is the feet pushing back.
        Matrix4d[] Wl = c.freshSegWorlds();
        double[] capN = new double[np], muF = new double[np], hipF = new double[np], share = new double[np];
        double wsum = 0;
        for (int k = 0; k < np; k++) {
            int i = planted.get(k); StructureModel.LegChain L = m.legChains.get(i);
            muF[k] = Math.min(L.mu(), groundMu(level, foot[i]));
            hipF[k] = L.hipTorque() * ratio / Math.max(0.5, H[i].y - foot[i].y);
            capN[k] = legSupport(c, i, Wl, ratio);
            Vec3 d = foot[i].subtract(com); share[k] = 1 / (0.4 + Math.sqrt(d.x * d.x + d.z * d.z)); wsum += share[k]; // a foot nearer the weight carries more
        }
        for (int k = 0; k < np; k++) share[k] /= wsum;
        int SUB = 4; double hs = dt / SUB;
        Vec3 v = c.vel; double y = c.getY(); Vec3 disp = Vec3.ZERO; boolean slip = false; lastSupport = 0; zmpLimited = false;
        if (footN.length != n) footN = new double[n];
        Arrays.fill(footN, 0);
        for (int sub = 0; sub < SUB; sub++) {
            // what the legs try to do: reach the wanted speed (gently: jerk-limited, comfortable) and hold the hull at its height
            Vec3 vhs = new Vec3(v.x, 0, v.z), acc = vDes.subtract(vhs).scale(1 / 0.35);
            if (acc.length() > A_COMFORT) acc = acc.normalize().scale(A_COMFORT);
            // the feet can only push where they touch: the centre of pressure must stay inside the support area, which limits
            // how hard the body can speed up, slow down or lean (a = w^2 * (centre of mass - centre of pressure))
            if (!supportHull.isEmpty()) {
                double cx = c.getX() + disp.x, cz = c.getZ() + disp.z, w2 = omega * omega;
                double px = cx - acc.x / w2, pz = cz - acc.z / w2;
                if (hullDistance(supportHull, px, pz) > 1e-6) { double[] q = closestOnHull(supportHull, px, pz); acc = new Vec3((cx - q[0]) * w2, 0, (cz - q[1]) * w2); zmpLimited = true; }
                if (acc.length() > 4) acc = acc.normalize().scale(4);
            }
            Vec3 da = acc.subtract(lastAccel); // and the push changes gradually
            if (da.length() > JERK * hs) acc = lastAccel.add(da.normalize().scale(JERK * hs));
            lastAccel = acc;
            double ay = Math.max(-3, Math.min(3, 36 * (yWant - y) - 12 * v.y));
            double fx = M * acc.x - ext.x, fz = M * acc.z - ext.z, fy = M * (ay + g) - ext.y;
            // what the feet can really push
            double Fx = 0, Fy = 0, Fz = 0;
            for (int k = 0; k < np; k++) {
                double N = Math.max(0, Math.min(capN[k], fy * share[k]));
                double hx = fx * share[k], hz = fz * share[k], hl = Math.sqrt(hx * hx + hz * hz), lim = Math.min(muF[k] * N, hipF[k]);
                if (hl > lim) { if (muF[k] * N <= hipF[k]) { slip = true; slipping[planted.get(k)] = true; } hx *= lim / hl; hz *= lim / hl; }
                Fx += hx; Fy += N; Fz += hz;
                if (sub == SUB - 1) footN[planted.get(k)] = N;
            }
            lastSupport = Fy;
            v = v.add((Fx + ext.x) / M * hs, (Fy + ext.y) / M * hs - g * hs, (Fz + ext.z) / M * hs);
            y += v.y * hs; disp = disp.add(v.scale(hs));
        }
        if (!slip) for (int i : planted) slipping[i] = false;
        c.vel = v;
        Vec3 beforeMove = c.position();
        c.moveBody(disp);
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
            if (corr.y > 0 && c.vel.y < 0) c.vel = new Vec3(c.vel.x, 0, c.vel.z); // folded legs (or the ground) stop the fall
        }
        c.onGroundC = true;
        // legs don't walk through buildings: if a leg would end up inside a wall, the body doesn't move that way
        if (legInWall(c)) { c.setPos(beforeMove.x, c.getY(), beforeMove.z); c.vel = new Vec3(c.vel.x * 0.3, c.vel.y, c.vel.z * 0.3); }

        // ---- 4. IK, legs kept out of the ground, and tipping
        solveAllIK(c);
        legDyn.step(c, this, dt, ratio, new Vec3(lastAccel.x, 0, lastAccel.z)); // Stage 3: lifted legs move by their motors' torque toward the IK's pose
        tiltAnkles(c, fW, rW);
        keepLegsOutOfGround(c);
        if (justLanded.length == n) { Crushing.feet(c, this, justLanded); Arrays.fill(justLanded, false); } // heavy feet crush what they land on or swing into
        else Crushing.feet(c, this, new boolean[n]);
        // tipping: the weight must stay over the feet. A foot in mid-step counts where it will land (it is about to catch the weight).
        List<Vec3> feetDown = new ArrayList<>();
        for (int i = 0; i < n; i++) if (state[i] == State.STANCE) feetDown.add(foot[i]); else if (state[i] == State.SWING) feetDown.add(swingTo[i]);
        // tipping: nothing can catch it when the weight is well outside the area the feet (and landing feet) can press on
        List<double[]> reach = new ArrayList<>(supportHull);
        for (int i = 0; i < n; i++) if (state[i] == State.SWING) reach.add(new double[]{swingTo[i].x, swingTo[i].z});
        reach = hull(reach);
        boolean outside = reach.isEmpty() ? !insideSupport(feetDown, c.position(), 0.5 + margin(m) + (biped ? 0.75 : 0)) : hullDistance(reach, c.getX(), c.getZ()) > 0.45 + margin(m);
        outsideTicks = outside ? outsideTicks + 1 : Math.max(0, outsideTicks - 2);
        unbalanced = outsideTicks > 30;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : " ").append(state[i] == State.STANCE ? (slipping[i] ? "SLIP" : "DOWN") : state[i] == State.SWING ? "STEP" : "AIR");
        status = sb + (waiting ? "  shifting weight" : cpOutTicks > 0 ? "  catching" : "");
    }

    /** Upward force the legs pushed last tick (N), for tests and the HUD. */
    public double lastSupport;
    /** Stage 3: the lifted legs' joint dynamics (motor torque, inertia, gravity). */
    public final LegDynamics legDyn = new LegDynamics();
    /** Balance (Stage 2): the area the planted feet can press on (convex, x/z), the pendulum rate sqrt(g / height),
     *  where the body would come to rest if the feet stopped it now (capture point), and counters. */
    public List<double[]> supportHull = new ArrayList<>();
    public double omega = 1; public Vec3 capture = Vec3.ZERO; public boolean zmpLimited; public int catchSteps; private int cpOutTicks;
    /** The support area: every sole block of a planted foot (a foot on a passive ball ankle presses only on its middle half). */
    List<double[]> supportArea(ColossusEntity c) {
        StructureModel m = c.model; List<double[]> pts = new ArrayList<>();
        Matrix4d[] W = c.freshSegWorlds();
        for (int i = 0; i < m.legChains.size() && i < state.length; i++) {
            if (state[i] != State.STANCE) continue;
            StructureModel.LegChain L = m.legChains.get(i);
            boolean passive = false; double ax = foot[i].x, az = foot[i].z;
            if (L.ankle() != null) for (StructureModel.Joint j : m.joints) if (j.ball && j.pivot.equals(L.ankle())) {
                passive = !j.powered;
                int ps = Math.max(0, j.parentSeg);
                if (ps < W.length) { Vector3d a = W[ps].transformPosition(new Vector3d(j.pivot.x, j.pivot.y, j.pivot.z)); ax = a.x; az = a.z; }
            }
            if (L.soles().isEmpty() || L.footSeg() >= W.length) { pts.add(new double[]{foot[i].x, foot[i].z}); continue; }
            // a passive ball ankle can't push off the foot's edges, only press on its middle half
            double k = passive ? 0.5 : 1.0, cx = 0, cz = 0; List<double[]> sole = new ArrayList<>();
            for (BlockPos p : L.soles()) {
                Vector3d v = W[L.footSeg()].transformPosition(new Vector3d(p.getX() + .5, p.getY(), p.getZ() + .5));
                for (double dx = -0.45; dx <= 0.46; dx += 0.9) for (double dz = -0.45; dz <= 0.46; dz += 0.9) { sole.add(new double[]{v.x + dx, v.z + dz}); cx += v.x + dx; cz += v.z + dz; }
            }
            cx /= sole.size(); cz /= sole.size();
            if (passive) { cx = ax; cz = az; } // the weight comes down through the ankle: press around the point under it
            for (double[] q : sole) pts.add(new double[]{cx + (q[0] - cx) * k, cz + (q[1] - cz) * k});
        }
        return hull(pts);
    }
    private int waitTicks; private double lastStride = 1;
    /** May group G lift? The feet that stay must hold the weight: sideways it must be over them (strictly), and it may only be ahead
     *  of them (it then falls forward and the swinging foot catches it), never behind (it would fall back where no foot goes). */
    private boolean canLift(int G, int n, StructureModel m, Vec3 com, Vec3 rW, Vec3 fW, double liftR, double stride) {
        List<Vec3> others = new ArrayList<>();
        for (int i = 0; i < n; i++) if (m.legChains.get(i).group() != G) { if (state[i] == State.STANCE) others.add(foot[i]); else return false; }
        if (others.isEmpty()) return false;
        if (!sidewaysOver(others, capture, rW, liftR + 0.35)) return false;
        double back = Double.MAX_VALUE; for (Vec3 f : others) back = Math.min(back, f.dot(fW));
        if (waitTicks > 60) { // stuck for 3 s: a step is fine while the weight is between the feet (it tips toward the swinging foot, which lands there and catches it)
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) if (state[i] == State.STANCE) { double s = foot[i].dot(rW); lo = Math.min(lo, s); hi = Math.max(hi, s); }
            double cs = com.dot(rW);
            return cs >= lo - 0.2 && cs <= hi + 0.2 && capture.dot(fW) >= back - 0.2;
        }
        if (waitTicks > 20) return capture.dot(fW) >= back; // stuck a while: a step is fine if the body would fall forward (the swinging foot catches it)
        return insideSupport(others, com, liftR);
    }
    /** Is the point within r of the feet along one direction (sideways: strict; forward/back: the push-off carries it over)? */
    static boolean sidewaysOver(List<Vec3> feet, Vec3 p, Vec3 right, double r) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Vec3 f : feet) { double s = f.dot(right); lo = Math.min(lo, s); hi = Math.max(hi, s); }
        double s = p.dot(right);
        return s >= lo - r && s <= hi + r;
    }
    /** Convex hull (x/z) of some points, counter-clockwise. */
    static List<double[]> hull(List<double[]> pts) {
        List<double[]> p = new ArrayList<>(pts);
        if (p.size() < 3) return p;
        p.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] h = new double[p.size() * 2][]; int k = 0;
        for (double[] q : p) { while (k >= 2 && cross(h[k - 2], h[k - 1], q) <= 0) k--; h[k++] = q; }
        for (int i = p.size() - 2, t = k + 1; i >= 0; i--) { double[] q = p.get(i); while (k >= t && cross(h[k - 2], h[k - 1], q) <= 0) k--; h[k++] = q; }
        List<double[]> out = new ArrayList<>(); for (int i = 0; i < k - 1; i++) out.add(h[i]);
        return out;
    }
    /** Closest point of the support area to (x, z) (the point itself when inside). */
    static double[] closestOnHull(List<double[]> h, double x, double z) {
        if (h.size() == 1) return h.get(0).clone();
        boolean inside = h.size() >= 3;
        for (int i = 0; i < h.size() && inside; i++) if (cross(h.get(i), h.get((i + 1) % h.size()), new double[]{x, z}) < 0) inside = false;
        if (inside) return new double[]{x, z};
        double best = Double.MAX_VALUE; double[] bp = h.get(0);
        for (int i = 0; i < h.size(); i++) {
            double[] a = h.get(i), b = h.get((i + 1) % h.size());
            double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz, t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / l2));
            double qx = a[0] + dx * t, qz = a[1] + dz * t, d = (qx - x) * (qx - x) + (qz - z) * (qz - z);
            if (d < best) { best = d; bp = new double[]{qx, qz}; }
        }
        return bp;
    }
    static double hullDistance(List<double[]> h, double x, double z) { if (h.isEmpty()) return Double.MAX_VALUE; double[] q = closestOnHull(h, x, z); return Math.hypot(q[0] - x, q[1] - z); }
    /** How hard each leg's foot pushed up last tick (N): what every joint in that leg really carries. */
    public double[] footN = new double[0];
    /**
     * Most a leg can push up on its foot right now: every motor-driven joint in it must hold the foot's push times its lever
     * (how far, sideways, the foot is from the joint). A straight leg takes it in its bones (the joint's own strength);
     * the more it bends, the less it can hold. Passive ball joints carry force but no turning.
     */
    double legSupport(ColossusEntity c, int i, Matrix4d[] W, double ratio) {
        StructureModel m = c.model; StructureModel.LegChain L = m.legChains.get(i);
        double best = Double.MAX_VALUE; Vec3 f = foot[i];
        for (int jid : L.joints()) {
            StructureModel.Joint J = m.joints.get(jid);
            best = Math.min(best, J.forceCap);
            if ((J.ball && !J.universal && !J.hinged) || J.mode == StructureModel.LOCKED) continue;
            int ps = Math.max(0, J.parentSeg);
            if (ps >= W.length) continue;
            Vector3d p = W[ps].transformPosition(new Vector3d(J.pivot.x, J.pivot.y, J.pivot.z));
            Vec3 ax = StructureModel.axisVec(J.axis); Vector3d a = W[ps].transformDirection(new Vector3d(ax.x, ax.y, ax.z)).normalize();
            double rx = f.x - p.x, rz = f.z - p.z, lever = Math.abs(-rz * a.x + rx * a.z);
            if (lever > 0.08) best = Math.min(best, J.motorTorque * ratio / lever);
        }
        return best == Double.MAX_VALUE ? M_INF : best;
    }
    private static final double M_INF = 1e12;

    /** Powered ball ankles tilt planted feet to the slope under them (within their limit); lifted feet ease back to flat. */
    private void tiltAnkles(ColossusEntity c, Vec3 fW, Vec3 rW) {
        StructureModel m = c.model;
        for (int i = 0; i < m.legChains.size(); i++) {
            StructureModel.LegChain L = m.legChains.get(i);
            if (L.ankle() == null) continue;
            StructureModel.Joint aj = null;
            for (StructureModel.Joint j : m.joints) if (j.ball && j.powered && j.pivot.equals(L.ankle())) aj = j;
            if (aj == null || aj.id >= m.ballPitch.length) continue;
            double tp = 0, tr = 0;
            if (state[i] == State.STANCE) {
                Vec3 p = foot[i]; double top = p.y + 1.5;
                double hf = groundFrom(c.level(), p.x + fW.x * 0.8, top, p.z + fW.z * 0.8, 4), hb = groundFrom(c.level(), p.x - fW.x * 0.8, top, p.z - fW.z * 0.8, 4);
                double hr = groundFrom(c.level(), p.x + rW.x * 0.8, top, p.z + rW.z * 0.8, 4), hl = groundFrom(c.level(), p.x - rW.x * 0.8, top, p.z - rW.z * 0.8, 4);
                if (!Double.isNaN(hf) && !Double.isNaN(hb)) tp = Math.atan2(hf - hb, 1.6);
                if (!Double.isNaN(hr) && !Double.isNaN(hl)) tr = Math.atan2(hl - hr, 1.6);
                double lim = aj.limRad(), lim2 = aj.lim2Rad(); // forward/back and sideways ranges
                tp = Math.max(-lim, Math.min(lim, tp)); tr = Math.max(-lim2, Math.min(lim2, tr));
            }
            m.ballPitch[aj.id] += (tp - m.ballPitch[aj.id]) * 0.3; m.ballRoll[aj.id] += (tr - m.ballRoll[aj.id]) * 0.3;
        }
    }

    /** Lift foot i towards a spot: the Step Controller (if any) picks the footing there, the step lands on the real ground,
     *  lifts high enough to clear what's in between, and never higher than the leg can fold. */
    private void startSwing(ColossusEntity c, int i, Vec3 to, Vec3 hipW) {
        StructureModel m = c.model;
        double top = Math.min(hipW.y - 0.3, foot[i].y + 2.5);
        if (planner) {
            // the Step Controller looks around the spot for good footing; if there's none, a shorter step; else step in place
            Vec3 f = pickFoothold(c, i, to, avoidFor(i));
            if (f == null) f = pickFoothold(c, i, foot[i].add(to.subtract(foot[i]).scale(0.5)), avoidFor(i));
            to = f != null ? f : foot[i];
        } else {
            double gy = soleGround(c, i, new Vec3(to.x, top - 1, to.z));
            if (!Double.isNaN(gy) && gy > foot[i].y - 3) to = new Vec3(to.x, gy, to.z);
            else to = foot[i]; // a wall or a drop there: step in place
        }
        { // never land so far ahead of the feet that stay that the body can't get over it before the next step
            Vec3 fl = Vec3.atLowerCornerOf(m.forward.getNormal()); double cs = Math.cos(c.yaw), sn = Math.sin(c.yaw);
            Vec3 fw = new Vec3(fl.x * cs - fl.z * sn, 0, fl.x * sn + fl.z * cs);
            double front = -Double.MAX_VALUE; for (int k = 0; k < state.length; k++) if (k != i && state[k] == State.STANCE) front = Math.max(front, foot[k].dot(fw));
            double maxAhead = 0.75 * lastStride + 0.4;
            if (front > -Double.MAX_VALUE && to.dot(fw) - front > maxAhead) {
                Vec3 back = to.subtract(fw.scale(to.dot(fw) - front - maxAhead));
                double gy = soleGround(c, i, new Vec3(back.x, top - 1, back.z));
                if (!Double.isNaN(gy) && gy > foot[i].y - 3) to = new Vec3(back.x, gy, back.z);
            }
        }
        double clear = Math.max(foot[i].y, to.y);
        for (int k = 1; k < 4; k++) {
            Vec3 q = foot[i].add(to.subtract(foot[i]).scale(k / 4.0));
            double qy = soleGround(c, i, new Vec3(q.x, top - 1, q.z));
            if (!Double.isNaN(qy)) clear = Math.max(clear, qy);
        }
        StructureModel.LegChain L = m.legChains.get(i);
        swingLift[i] = m.gaitLift * L.length() + (clear - Math.max(foot[i].y, to.y)) + 0.1;
        swingObstacle[i] = clear - Math.max(foot[i].y, to.y) > 0.05;
        // how much shorter the leg can get from how it stands now (the knee only folds so far)
        double now = hipW.distanceTo(effectorWorld(c, i));
        double room = L.joints().size() > 1 ? 0.8 * Math.max(0, now - minReach(m, L)) : 0.3;
        swingLift[i] = Math.max(0.2, Math.min(swingLift[i], room - Math.max(0, to.y - foot[i].y)));
        swingFrom[i] = foot[i]; swingTo[i] = to; swingT[i] = 0; state[i] = State.SWING; steps++;
    }

    /** Where the gait wants foot i to land: under where its hip will be halfway through the next stance, turned with the body. */
    private static Vec3 nominalTo(int i, Vec3 neutral, Vec3 vCmd, double tAhead, double yawRate, Vec3 com, double reachH, double stride) {
        Vec3 to = neutral.add(vCmd.scale(tAhead));
        double turn = yawRate * tAhead;
        if (Math.abs(turn) > 1e-4) { Vec3 r = to.subtract(com); double ct = Math.cos(turn), st = Math.sin(turn); to = com.add(r.x * ct - r.z * st, r.y, r.x * st + r.z * ct); }
        Vec3 offs = to.subtract(neutral); offs = new Vec3(offs.x, 0, offs.z);
        double lim = 0.6 * reachH + stride * 0.5;
        if (offs.length() > lim) to = neutral.add(offs.normalize().scale(lim));
        return to;
    }
    /** Spots other feet use (planted, or landing): a new step keeps clear of them. */
    private List<Vec3> avoidFor(int i) {
        List<Vec3> a = new ArrayList<>();
        for (int k = 0; k < state.length; k++) if (k != i) { if (state[k] == State.STANCE) a.add(foot[k]); else if (state[k] == State.SWING) a.add(swingTo[k]); }
        return a;
    }
    /**
     * Step Controller: look at a small grid of spots around where the foot should go and pick the best foothold.
     * Every sole block needs ground under it, the soles must be nearly level, the rise or drop must be one the leg can take,
     * and lava, fire, cactus and the like are refused. Water and slippery ground cost extra. Closer to the wanted spot is better.
     * Returns the contact point on the ground, or null if nothing nearby is good.
     */
    Vec3 pickFoothold(ColossusEntity c, int i, Vec3 want, List<Vec3> avoid) {
        StructureModel.LegChain L = c.model.legChains.get(i);
        Level level = c.level();
        Vec3 from = foot[i];
        double maxRise = Math.min(1.6, 0.35 * L.length()), top = from.y + maxRise + 1;
        Vec3 best = null; double bestScore = Double.MAX_VALUE;
        for (double dx = -1; dx <= 1.001; dx += 0.5) for (double dz = -1; dz <= 1.001; dz += 0.5) {
            Vec3 p = new Vec3(want.x + dx, 0, want.z + dz);
            boolean clear = true;
            for (Vec3 a : avoid) if ((a.x - p.x) * (a.x - p.x) + (a.z - p.z) * (a.z - p.z) < 0.9 * 0.9) { clear = false; break; }
            if (!clear) continue;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY; int miss = 0;
            for (BlockPos sb : L.soles()) {
                Vec3 off = levelOffset(c, L.foot(), new Vec3(sb.getX() + .5, L.foot().y, sb.getZ() + .5));
                double gy = groundFrom(level, p.x + off.x, top, p.z + off.z, (int) Math.ceil(top - from.y) + 3);
                if (Double.isNaN(gy) || gy < from.y - 2) { miss++; continue; }
                hi = Math.max(hi, gy); lo = Math.min(lo, gy);
            }
            if (miss > 0 || hi - lo > 0.55) continue;            // over an edge, or too uneven
            double rise = hi - from.y;
            if (rise > maxRise || rise < -1.5) continue;
            BlockPos under = BlockPos.containing(p.x, hi - 0.05, p.z), at = BlockPos.containing(p.x, hi + 0.05, p.z);
            if (hazard(level.getBlockState(under)) || hazard(level.getBlockState(at)) || level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA)) continue;
            double score = Math.sqrt(dx * dx + dz * dz) + Math.abs(rise) * 0.8 + (hi - lo) * 2;
            if (!level.getFluidState(at).isEmpty()) score += 3;
            if (groundMu(level, new Vec3(p.x, hi, p.z)) < 0.2) score += 2;
            if (score < bestScore) { bestScore = score; best = new Vec3(p.x, hi, p.z); }
        }
        return best;
    }
    static boolean hazard(BlockState s) {
        net.minecraft.world.level.block.Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.BaseFireBlock || b instanceof net.minecraft.world.level.block.MagmaBlock || b instanceof net.minecraft.world.level.block.CactusBlock
                || b instanceof net.minecraft.world.level.block.CampfireBlock || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock || b instanceof net.minecraft.world.level.block.PowderSnowBlock
                || b instanceof net.minecraft.world.level.block.WitherRoseBlock || s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    private static double smoothstep(double a, double b, double t) { double x = Math.max(0, Math.min(1, (t - a) / (b - a))); return x * x * (3 - 2 * x); }

    /** The leg group whose turn to lift comes soonest on the gait clock. */
    private int nextLiftGroup(int groups, double duty) {
        int best = 0; double soonest = Double.MAX_VALUE;
        for (int G = 0; G < groups; G++) {
            double ph = clock + (double) G / groups - duty, until = Math.ceil(ph) - ph;
            if (until < soonest) { soonest = until; best = G; }
        }
        return best;
    }

    private List<Vec3> others(List<Integer> planted, int group, StructureModel m) {
        List<Vec3> o = new ArrayList<>(); for (int i : planted) if (m.legChains.get(i).group() != group) o.add(foot[i]); return o;
    }
    /** Where the IK effector (ankle or foot) must be for foot i's contact point. */
    private Vec3 effectorWorld(ColossusEntity c, int i) {
        StructureModel.LegChain L = c.model.legChains.get(i);
        return foot[i].add(levelOffset(c, L.foot(), L.effector())).add(0, i < footRaise.length ? footRaise[i] : 0, 0);
    }
    /** Is any leg block (not the soles) deep inside a wall (a solid column at least two blocks tall)? */
    private static boolean legInWall(ColossusEntity c) {
        Matrix4d[] W = c.freshSegWorlds();
        Level level = c.level();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (StructureModel.LegChain L : c.model.legChains) for (int sgi : legSegs(c.model, L)) {
            if (sgi == L.footSeg()) continue; // feet touch the ground; they are handled by footing
            for (BlockPos b : c.model.segs.get(sgi).blocks) {
                Vector3d p = W[sgi].transformPosition(new Vector3d(b.getX() + .5, b.getY() + .5, b.getZ() + .5));
                mp.set(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
                if (level.getBlockState(mp).getCollisionShape(level, mp).isEmpty()) continue;
                mp.move(0, 1, 0);
                if (!level.getBlockState(mp).getCollisionShape(level, mp).isEmpty()) return true;
            }
        }
        return false;
    }
    /** How far the hip should twist (about the vertical) so a planted foot keeps pointing where it landed. */
    private double twistFor(ColossusEntity c, int i) { return i < footYaw.length && state[i] == State.STANCE ? wrap(c.yaw - footYaw[i]) : 0; }
    /** Hip twist joint (a vertical-axis Leg joint at the top of a leg): set its angle, return the target turned back for the rest of the leg. */
    private static Vec3 untwist(StructureModel m, StructureModel.LegChain L, Vec3 t, double tw) {
        StructureModel.Joint j = m.joints.get(L.joints().get(0));
        double c = Math.cos(-tw), s = Math.sin(-tw);
        Vec3 d = t.subtract(j.pivot);
        return j.pivot.add(d.x * c + d.z * s, d.y, -d.x * s + d.z * c); // about +Y by -tw
    }
    static boolean twistHip(StructureModel m, StructureModel.LegChain L) { StructureModel.Joint j = m.joints.get(L.joints().get(0)); return !j.universal && j.axis == net.minecraft.core.Direction.Axis.Y && L.joints().size() > 1; }
    /** The leg without its top joint (for solving below a hip twist). */
    static StructureModel.LegChain below(StructureModel m, StructureModel.LegChain L) {
        List<Integer> js = L.joints().subList(1, L.joints().size());
        return new StructureModel.LegChain(js, L.footSeg(), L.bodySeg(), m.joints.get(js.get(0)).pivot, L.foot(), L.length(), L.minLen(), L.hipTorque(), L.vCap(), L.mu(), L.group(),
                Arrays.copyOfRange(L.limits(), 1, L.limits().length), L.kneeTorque(), L.ankle(), L.soles());
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
                Vec3 tl = hipRoll(m, L, new Vec3(t.x, t.y, t.z), state[i] == State.STANCE ? MAX_TURN_PLANTED : MAX_TURN, twistFor(c, i));
                t = new Vector3d(tl.x, tl.y, tl.z);
                double[] ang = new double[L.joints().size()];
                for (int k = 0; k < ang.length; k++) { double a = m.ikAngles[L.joints().get(k)]; ang[k] = Double.isNaN(a) ? 0 : a; }
                if (twistHip(m, L)) {
                    Vec3 ut = untwist(m, L, new Vec3(t.x, t.y, t.z), ang[0]);
                    double[] rest = Arrays.copyOfRange(ang, 1, ang.length);
                    solve(m, below(m, L), ut, rest, digi);
                    System.arraycopy(rest, 0, ang, 1, rest.length);
                } else solve(m, L, new Vec3(t.x, t.y, t.z), ang, digi);
                for (int k = 0; k < ang.length; k++) { double prev = m.ikPrev[L.joints().get(k)]; m.ikAngles[L.joints().get(k)] = m.joints.get(L.joints().get(k)).clampAngle(Double.isNaN(prev) ? ang[k] : smooth(prev, ang[k], state[i] == State.STANCE ? MAX_TURN_PLANTED : MAX_TURN)); }
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
            boolean free0 = i >= state.length || state[i] != State.STANCE;
            double twA = twistFor(c, i);
            Vec3 tl = hipRoll(m, L, new Vec3(t.x, t.y, t.z), free0 ? MAX_TURN : MAX_TURN_PLANTED, twA);
            t = new Vector3d(tl.x, tl.y, tl.z);
            double[] ang = new double[L.joints().size()];
            for (int k = 0; k < ang.length; k++) { double a = m.ikAngles[L.joints().get(k)]; ang[k] = Double.isNaN(a) ? 0 : a; }
            double[] before = ang.clone();
            if (twistHip(m, L)) { // hip twist: turn the leg about the vertical, then solve the rest of it
                double lim = L.limits()[0];
                ang[0] = smooth(before[0], Math.max(-lim, Math.min(lim, twA)), free0 ? MAX_TURN : MAX_TURN_PLANTED);
                Vec3 ut = untwist(m, L, new Vec3(t.x, t.y, t.z), ang[0]);
                double[] rest = Arrays.copyOfRange(ang, 1, ang.length);
                solve(m, below(m, L), ut, rest, digi);
                System.arraycopy(rest, 0, ang, 1, rest.length);
            } else solve(m, L, new Vec3(t.x, t.y, t.z), ang, digi);
            boolean free = i >= state.length || state[i] != State.STANCE; // a planted leg must follow its foot exactly
            for (int k = 0; k < ang.length; k++) m.ikAngles[L.joints().get(k)] = m.joints.get(L.joints().get(k)).clampAngle(smooth(before[k], ang[k], free ? MAX_TURN : MAX_TURN_PLANTED));
        }
    }
    /** A leg in the air turns continuously: at most this far per tick (about 460 degrees a second), never a jump. */
    private static final double MAX_TURN = 0.4, MAX_TURN_PLANTED = 0.6; // a planted leg follows its foot almost exactly
    private static double smooth(double from, double to, double max) {
        double d = wrap(to - from);
        return from + Math.max(-max, Math.min(max, d));
    }

    /** Pick the solver from the Locomotion Controller: Analytic 2-link and FABRIK work in the leg's plane; anything else uses CCD. */
    public static void solve(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang, boolean kneeBack) {
        boolean planar = true;
        for (int j : L.joints()) planar &= m.joints.get(j).axis == m.joints.get(L.joints().get(0)).axis;
        int mode = m.ikMode;
        if (mode == StructureModel.IK_AUTO) mode = !planar ? StructureModel.IK_CCD : ang.length == 2 ? StructureModel.IK_ANALYTIC2 : ang.length == 3 ? StructureModel.IK_ANALYTIC3 : StructureModel.IK_FABRIK;
        if (planar && mode == StructureModel.IK_ANALYTIC2 && ang.length == 2) { analytic2(m, L, target, ang, kneeBack); return; }
        if (planar && mode == StructureModel.IK_ANALYTIC3 && ang.length == 3) { analytic3(m, L, target, ang, kneeBack); return; }
        if (planar && mode != StructureModel.IK_CCD && ang.length >= 1) { fabrik(m, L, target, ang, kneeBack); return; }
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

    /**
     * Powered ball hip: tilt the whole leg sideways so the target lies in the leg's swing plane, then return the target turned
     * back into that plane for the planar solvers. Other legs: the target as it is.
     */
    public static Vec3 hipRoll(StructureModel m, StructureModel.LegChain L, Vec3 t, double maxTurn) { return hipRoll(m, L, t, maxTurn, 0); }
    public static Vec3 hipRoll(StructureModel m, StructureModel.LegChain L, Vec3 t, double maxTurn, double twist) {
        StructureModel.Joint hj = m.joints.get(L.joints().get(0));
        if (!hj.universal || hj.id >= m.ikRoll.length) return t;
        // a powered ball hip also twists about the vertical so a planted foot keeps its heading while the body turns
        double lim0 = hj.limRad();
        double yw = smooth(Double.isNaN(m.ikYaw[hj.id]) ? 0 : m.ikYaw[hj.id], Math.max(-lim0, Math.min(lim0, twist)), maxTurn);
        m.ikYaw[hj.id] = yw;
        { double c0 = Math.cos(-yw), s0 = Math.sin(-yw); Vec3 d0 = t.subtract(hj.pivot); t = hj.pivot.add(d0.x * c0 + d0.z * s0, d0.y, -d0.x * s0 + d0.z * c0); }
        Vec3 f = Vec3.atLowerCornerOf(m.forward.getNormal()), r = new Vec3(-f.z, 0, f.x), d = t.subtract(hj.pivot);
        double lat = d.dot(r) - L.foot().subtract(hj.pivot).dot(r); // sideways offset compared with the built leg
        double roll = -Math.atan2(lat, Math.max(0.3, -d.y));
        double lim = hj.lim2Rad(); // its sideways range
        roll = Math.max(-lim, Math.min(lim, roll));
        double prev = Double.isNaN(m.ikRoll[hj.id]) ? 0 : m.ikRoll[hj.id];
        roll = smooth(prev, roll, maxTurn);
        m.ikRoll[hj.id] = roll;
        // turn the target back by -roll about the forward axis through the hip
        double c = Math.cos(-roll), sn = Math.sin(-roll);
        Vec3 dd = d.scale(c).add(f.cross(d).scale(sn)).add(f.scale(f.dot(d) * (1 - c)));
        return hj.pivot.add(dd);
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

    /** Which side of the hip-foot line the knee goes (+1/-1 in the leg's 2D frame): the side facing forward when the leg hangs down. */
    private static int bendSide(StructureModel m, Vec3[] B, boolean kneeBack) {
        Vec3 f = Vec3.atLowerCornerOf(m.forward.getNormal());
        double fx = f.dot(B[0]), fy = f.dot(B[1]), dx = -B[0].y, dy = -B[1].y; // forward and down in the plane
        double cr = dx * fy - dy * fx;
        int s = cr >= 0 ? 1 : -1;
        if (Math.abs(cr) < 1e-6) s = 1; // leg plane doesn't contain "forward" (side-swinging leg): any fixed side
        return kneeBack ? -s : s;
    }
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
        double[] r = twoLink(m, L, B, rp[0], rp[1], rp[2], target, kneeBack);
        anglesFromSegments(L, new double[]{r[2], r[3]}, new double[]{r[0], r[1]}, ang);
    }

    /**
     * Analytic three-link IK for hip + knee + ankle hinges with the foot kept flat: the ankle goes where the foot needs it
     * (the foot keeps its built angle), hip and knee are solved exactly for that, and the ankle takes up the rest.
     */
    public static void analytic3(StructureModel m, StructureModel.LegChain L, Vec3 target, double[] ang, boolean kneeBack) {
        Vec3[] B = plane(m, L);
        Vec3[] rp = restPoints(m, L); // hip, knee, ankle, foot
        Vec3 ankleT = target.subtract(rp[3].subtract(rp[2]));
        double[] r = twoLink(m, L, B, rp[0], rp[1], rp[2], ankleT, kneeBack);
        double[] p2 = to2(rp[2], rp[0], B), p3 = to2(rp[3], rp[0], B);
        double footRest = Math.atan2(p3[1] - p2[1], p3[0] - p2[0]);
        anglesFromSegments(L, new double[]{r[2], r[3], footRest}, new double[]{r[0], r[1], footRest}, ang);
    }

    /** Exact hip/knee solution in the leg's plane: {hip abs angle, knee abs angle, hip rest angle, knee rest angle}. */
    private static double[] twoLink(StructureModel m, StructureModel.LegChain L, Vec3[] B, Vec3 r0, Vec3 r1, Vec3 r2, Vec3 target, boolean kneeBack) {
        double[] p0 = to2(r0, r0, B), p1 = to2(r1, r0, B), p2 = to2(r2, r0, B), t = to2(target, r0, B);
        double l0 = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]), l1 = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
        double[] restAbs = {Math.atan2(p1[1] - p0[1], p1[0] - p0[0]), Math.atan2(p2[1] - p1[1], p2[0] - p1[0])};
        // the knee can't bend past its limit: the leg is never shorter than that (it reaches towards the target instead of breaking)
        double kneeLim = L.limits().length > 1 ? L.limits()[1] : Math.PI;
        double dMin = Math.sqrt(Math.max(0, l0 * l0 + l1 * l1 - 2 * l0 * l1 * Math.cos(Math.PI - Math.min(Math.PI, kneeLim))));
        double d = Math.max(Math.max(Math.abs(l0 - l1) + 1e-3, dMin + 1e-3), Math.min(l0 + l1 - 1e-3, Math.hypot(t[0], t[1])));
        double base = Math.atan2(t[1], t[0]);
        double cosA = Math.max(-1, Math.min(1, (l0 * l0 + d * d - l1 * l1) / (2 * l0 * d)));
        double A = Math.acos(cosA);
        // The knee always bends to the same side of the hip-foot line (forward, or back for digitigrade). The side is fixed
        // from the leg's plane, never re-picked from the pose, so the knee can't flip over when the leg swings far.
        int sgn = bendSide(m, B, kneeBack);
        double a0 = base + sgn * A;
        double[] knee = {l0 * Math.cos(a0), l0 * Math.sin(a0)};
        double tx = d * Math.cos(base), ty = d * Math.sin(base);
        double a1 = Math.atan2(ty - knee[1], tx - knee[0]);
        return new double[]{a0, a1, restAbs[0], restAbs[1]};
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
