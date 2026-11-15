package com.colossalcraft.colossus;

import com.colossalcraft.block.*;
import com.colossalcraft.block.SimpleParts.*;
import com.colossalcraft.tier.EngineTier;
import com.colossalcraft.tier.MaterialTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;

import java.util.*;

/**
 * Pure analysis of a set of blocks (local coords, Hull Core at 0,0,0):
 * mass, centre of mass, Segments split by joint clusters, the joint tree rooted at the Hull Core's Segment,
 * power sources, thrust, motors, gyros.
 */
public class StructureModel {
    public static final double G = 9.81;
    public static final String[] MODES = {"LOCKED", "WALK A", "WALK B", "SPIN", "SWAY", "PROGRAM"};
    public static final int LOCKED = 0, WALK_A = 1, WALK_B = 2, SPIN = 3, SWAY = 4, PROGRAM = 5;
    public static final int KEYS = 8;
    /** Reserved key in the joint config for whole-walker gait settings. */
    public static final String GAIT = "_gait";
    /** Steps per second (one full leg cycle). */
    public double gaitSpeed = 0.5;

    public static class Seg {
        public int id, parentJoint = -1, depth;
        public final List<BlockPos> blocks = new ArrayList<>();
        public double mass, subMass; public Vec3 com = Vec3.ZERO, subMoment = Vec3.ZERO;
        public final List<Integer> childJoints = new ArrayList<>();
    }
    public static class Joint {
        public int id, parentSeg = -1, depth, k, mode, amp = 30, motors;
        /** Load-bearing size: sum of each joint block's shape strength (full 1, slab/panel 0.5, pin 0.25). */
        public double area;
        public MaterialTier tier; public Direction.Axis axis; public long key;
        public final List<BlockPos> blocks = new ArrayList<>();
        public final List<Integer> childSegs = new ArrayList<>();
        public Vec3 pivot = Vec3.ZERO, subCom = Vec3.ZERO;
        public double subMass, motorTorque, motorPower, motorDrive, reqTorque, forceCap, momentCap, staticRatio;
        /** Fastest the gears let this joint turn (degrees per second). 0 = no motor. */
        public double maxSpeed;
        /** Program: keyframe angles (degrees), phase offset (degrees), speed factor, play even when standing still. */
        public int[] keys = new int[KEYS]; public int phase; public double speedFactor = 1; public boolean loop;
        /** Gear ratios seen by this joint's motors (for the screen). */
        public final List<Double> ratios = new ArrayList<>();
        public boolean stalled() { return mode != LOCKED && !childSegs.isEmpty() && (motorTorque <= 0 || motorTorque < reqTorque); }
        public boolean active() { return mode != LOCKED && !childSegs.isEmpty() && !stalled(); }
    }
    public record EngineInfo(String name, long energy, long capacity, int output, boolean running, String note, boolean zeroPoint) {}

    public final Map<BlockPos, BlockState> blocks;
    public final Map<BlockPos, CompoundTag> bes;
    public final Map<BlockPos, Integer> segOf = new HashMap<>();
    /** Joint block -> joint id (bearings are drawn turning with their joint). */
    public final Map<BlockPos, Integer> jointOf = new HashMap<>();
    /** Footing: a legged Colossus stands on parts that hang from joints. It needs a gyro in a foot and motors on every joint from that foot up to the core. */
    public boolean legged;
    public final List<Foot> feet = new ArrayList<>();
    public double footingGyro;
    public record Foot(int seg, double gyro, boolean ok, String problem) {}
    private final Map<Integer, Double> segGyro = new HashMap<>();
    public final Set<BlockPos> solid = new HashSet<>();
    public final List<BlockPos> surface = new ArrayList<>();
    public final Set<BlockPos> visible = new HashSet<>();
    public final Map<BlockPos, AABB> shapeBounds = new HashMap<>();
    public final List<Seg> segs = new ArrayList<>();       // BFS order: parents before children
    public final List<Joint> joints = new ArrayList<>();
    public final List<EngineInfo> engines = new ArrayList<>();

    public double mass; public Vec3 com = Vec3.ZERO;
    public long energy; public boolean infinite; public double output;
    public final double[] thrustCap = new double[6], thrustPow = new double[6];
    public double driveForce, drivePower, gyroCap, gyroPower;
    public int sails, hearts, thrusters, driveMotors;
    public BlockPos seat; public Direction forward = Direction.NORTH;
    public double radius = 1; public AABB localBounds = new AABB(0, 0, 0, 1, 1, 1);

    public StructureModel(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> bes, CompoundTag jointCfg) {
        this.blocks = blocks; this.bes = bes == null ? new HashMap<>() : bes;
        build(jointCfg == null ? new CompoundTag() : jointCfg);
    }

    public static String jointKey(long k) { return Long.toString(k); }

    private void build(CompoundTag cfg) {
        // ---- mass, solids, bounds ----
        double mx = 0, my = 0, mz = 0;
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        Map<BlockPos, Double> bm = new HashMap<>();
        for (var e : blocks.entrySet()) {
            BlockPos p = e.getKey(); BlockState s = e.getValue();
            double m = Density.mass(s); bm.put(p, m);
            mass += m; mx += m * (p.getX() + .5); my += m * (p.getY() + .5); mz += m * (p.getZ() + .5);
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
            try {
                var sh = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (!sh.isEmpty()) { solid.add(p); shapeBounds.put(p, sh.bounds()); }
            } catch (Exception ex) { solid.add(p); shapeBounds.put(p, new AABB(0, 0, 0, 1, 1, 1)); }
            Block b = s.getBlock();
            if (b instanceof HullCoreBlock) hearts++;
        }
        if (blocks.isEmpty()) return;
        mass = Math.max(mass, 1);
        com = new Vec3(mx / mass, my / mass, mz / mass);
        localBounds = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
        for (BlockPos p : blocks.keySet()) {
            double dx = p.getX() + .5 - com.x, dz = p.getZ() + .5 - com.z, dy = p.getY() + .5 - com.y;
            radius = Math.max(radius, Math.sqrt(dx * dx + dz * dz + dy * dy * 0.25) + 0.7);
            boolean vis = false, surf = false;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                BlockState ns = blocks.get(n);
                if (ns == null || !ns.canOcclude()) vis = true;
                if (!solid.contains(n)) surf = true;
            }
            if (vis) visible.add(p);
            if (surf && solid.contains(p)) surface.add(p);
        }

        // ---- joint clusters ----
        Set<BlockPos> jointPos = new HashSet<>();
        for (var e : blocks.entrySet()) if (e.getValue().getBlock() instanceof JointBlock) jointPos.add(e.getKey());
        Map<BlockPos, Integer> clusterOf = new HashMap<>();
        for (BlockPos start : jointPos) {
            if (clusterOf.containsKey(start)) continue;
            BlockState ss = blocks.get(start);
            Joint j = new Joint(); j.id = joints.size(); j.tier = ((JointBlock) ss.getBlock()).tier;
            j.axis = ss.hasProperty(RotatedPillarBlock.AXIS) ? ss.getValue(RotatedPillarBlock.AXIS) : Direction.Axis.Y;
            ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); clusterOf.put(start, j.id);
            double px = 0, py = 0, pz = 0; long minKey = Long.MAX_VALUE; BlockPos minP = start;
            while (!q.isEmpty()) {
                BlockPos p = q.poll(); j.blocks.add(p); j.area += ((JointBlock) blocks.get(p).getBlock()).shape.strength; px += p.getX() + .5; py += p.getY() + .5; pz += p.getZ() + .5;
                if (p.compareTo(minP) < 0) minP = p;
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d); BlockState ns = blocks.get(n);
                    if (ns != null && jointPos.contains(n) && !clusterOf.containsKey(n) && ((JointBlock) ns.getBlock()).tier == j.tier
                            && ns.getValue(RotatedPillarBlock.AXIS) == j.axis) { clusterOf.put(n, j.id); q.add(n); }
                }
            }
            j.k = j.blocks.size(); j.key = minP.asLong();
            j.pivot = new Vec3(px / j.k, py / j.k, pz / j.k);
            j.forceCap = MaterialTier.forceCap(j.tier, j.area); j.momentCap = MaterialTier.momentCap(j.tier, j.area);
            CompoundTag jc = cfg.getCompound(jointKey(j.key));
            j.mode = Math.max(0, Math.min(MODES.length - 1, jc.getByte("Mode")));
            if (jc.contains("Amp")) j.amp = jc.getInt("Amp");
            int[] k = jc.getIntArray("Keys"); for (int i = 0; i < Math.min(KEYS, k.length); i++) j.keys[i] = k[i];
            j.phase = jc.getInt("Phase"); if (jc.contains("SpeedX")) j.speedFactor = jc.getInt("SpeedX") / 100.0; j.loop = jc.getBoolean("Loop");
            joints.add(j);
        }

        // ---- segments (non-joint connected components) ----
        Map<BlockPos, Integer> compOf = new HashMap<>();
        List<Seg> comps = new ArrayList<>();
        for (BlockPos start : blocks.keySet()) {
            if (jointPos.contains(start) || compOf.containsKey(start)) continue;
            Seg s = new Seg(); s.id = comps.size(); comps.add(s);
            ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); compOf.put(start, s.id);
            while (!q.isEmpty()) {
                BlockPos p = q.poll(); s.blocks.add(p);
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (blocks.containsKey(n) && !jointPos.contains(n) && !compOf.containsKey(n)) { compOf.put(n, s.id); q.add(n); }
                }
            }
        }
        // cluster -> touching comps
        List<Set<Integer>> touch = new ArrayList<>();
        for (Joint j : joints) {
            Set<Integer> t = new LinkedHashSet<>();
            for (BlockPos p : j.blocks) for (Direction d : Direction.values()) { Integer c = compOf.get(p.relative(d)); if (c != null) t.add(c); }
            touch.add(t);
        }
        // ---- BFS tree from the Hull Core segment ----
        Integer root = compOf.get(BlockPos.ZERO);
        if (root == null) root = comps.isEmpty() ? null : 0;
        int[] newId = new int[comps.size()]; Arrays.fill(newId, -1);
        boolean[] jointSeen = new boolean[joints.size()];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        if (root != null) { q.add(root); newId[root] = 0; segs.add(comps.get(root)); }
        while (!q.isEmpty()) {
            int c = q.poll(); Seg s = comps.get(c);
            for (Joint j : joints) {
                if (jointSeen[j.id] || !touch.get(j.id).contains(c)) continue;
                jointSeen[j.id] = true; j.parentSeg = newId[c]; j.depth = s.depth + 1; s.childJoints.add(j.id);
                for (int o : touch.get(j.id)) {
                    if (newId[o] >= 0) continue;
                    Seg cs = comps.get(o); newId[o] = segs.size(); segs.add(cs);
                    cs.parentJoint = j.id; cs.depth = s.depth + 2; j.childSegs.add(newId[o]); q.add(o);
                }
            }
        }
        for (int i = 0; i < comps.size(); i++) if (newId[i] < 0) { newId[i] = segs.size(); segs.add(comps.get(i)); } // safety
        for (int i = 0; i < segs.size(); i++) segs.get(i).id = i;
        for (Seg s : segs) for (BlockPos p : s.blocks) segOf.put(p, s.id);
        for (Joint j : joints) { int ps = Math.max(0, j.parentSeg); for (BlockPos p : j.blocks) { segOf.put(p, ps); } if (j.parentSeg >= 0) segs.get(ps).blocks.addAll(j.blocks); }

        // ---- segment masses & subtree masses (reverse BFS) ----
        for (Seg s : segs) {
            double sx = 0, sy = 0, sz = 0;
            for (BlockPos p : s.blocks) { double m = bm.get(p); s.mass += m; sx += m * (p.getX() + .5); sy += m * (p.getY() + .5); sz += m * (p.getZ() + .5); }
            s.com = s.mass > 0 ? new Vec3(sx / s.mass, sy / s.mass, sz / s.mass) : Vec3.ZERO;
            s.subMass = s.mass; s.subMoment = new Vec3(sx, sy, sz);
        }
        for (int i = segs.size() - 1; i >= 0; i--) {
            Seg s = segs.get(i);
            if (s.parentJoint >= 0) {
                Joint j = joints.get(s.parentJoint);
                j.subMass += s.subMass; j.subCom = j.subCom.add(s.subMoment);
                if (j.parentSeg >= 0) { Seg ps = segs.get(j.parentSeg); ps.subMass += s.subMass; ps.subMoment = ps.subMoment.add(s.subMoment); }
            }
        }
        for (Joint j : joints) j.subCom = j.subMass > 0 ? j.subCom.scale(1 / j.subMass) : j.pivot;

        if (cfg.contains(GAIT)) gaitSpeed = Math.max(0.05, cfg.getCompound(GAIT).getInt("Speed") / 100.0);
        // ---- gears: from each joint walk through touching gearboxes; a motor gets the ratio of the gearbox it touches ----
        Map<BlockPos, double[]> motorGear = new HashMap<>(); // motor -> {joint, ratio, efficiency}
        for (Joint j : joints) {
            ArrayDeque<BlockPos> gq = new ArrayDeque<>(); Map<BlockPos, double[]> seen = new HashMap<>();
            for (BlockPos jp : j.blocks) for (Direction d : Direction.values()) {
                BlockPos n = jp.relative(d); BlockState ns = blocks.get(n);
                if (ns == null) continue;
                if (ns.getBlock() instanceof MotorBlock) motorGear.putIfAbsent(n, new double[]{j.id, 1, 1});
                else if (ns.getBlock() instanceof GearboxBlock g && !seen.containsKey(n)) { seen.put(n, new double[]{g.ratio, g.efficiency}); gq.add(n); }
            }
            while (!gq.isEmpty()) {
                BlockPos gp = gq.poll(); double[] cur = seen.get(gp);
                for (Direction d : Direction.values()) {
                    BlockPos n = gp.relative(d); BlockState ns = blocks.get(n);
                    if (ns == null) continue;
                    if (ns.getBlock() instanceof MotorBlock) motorGear.putIfAbsent(n, new double[]{j.id, cur[0], cur[1]});
                    else if (ns.getBlock() instanceof GearboxBlock g && !seen.containsKey(n) && seen.size() < 64) { seen.put(n, new double[]{cur[0] * g.ratio, cur[1] * g.efficiency}); gq.add(n); }
                }
            }
        }
        // ---- parts: motors, engines, thrusters, gyros, seat ----
        for (var e : blocks.entrySet()) {
            BlockPos p = e.getKey(); BlockState s = e.getValue(); Block b = s.getBlock();
            if (b instanceof MotorBlock mb) {
                double[] mg = motorGear.get(p);
                if (mg != null) {
                    Joint j = joints.get((int) mg[0]); double ratio = mg[1], eff = mg[2];
                    j.motors++; j.motorTorque += mb.tier.motorTorque * ratio * eff; j.motorPower += mb.tier.motorPower; j.motorDrive += mb.tier.driveForce;
                    double sp = mb.tier.motorRpm * 6 / ratio;
                    j.maxSpeed = j.maxSpeed == 0 ? sp : Math.min(j.maxSpeed, sp); j.ratios.add(ratio);
                }
                else { driveMotors++; driveForce += mb.tier.driveForce; drivePower += mb.tier.motorPower; }
            } else if (b instanceof ColossalBattery cb) {
                CompoundTag tag = this.bes.get(p); long en = tag == null ? 0 : tag.getLong("Energy");
                output += cb.output(); energy += en;
                engines.add(new EngineInfo(cb.title(), en, cb.capacity(), cb.output(), true, "", false));
            } else if (b instanceof ControllerBlock cb) {
                boolean formed = s.getValue(ControllerBlock.FORMED);
                if (cb.engine != null) addEngine(p, cb.engine, formed, formed ? "" : "structure not formed");
                else if (formed) { gyroCap += cb.gyroCapacity; gyroPower += cb.gyroCapacity / 3000.0; segGyro.merge(segOf.getOrDefault(p, 0), cb.gyroCapacity, Double::sum); }
            } else if (b instanceof PowerBlock pb) {
                addEngine(p, pb.tier, true, "");
            } else if (b instanceof ThrusterBlock tb) {
                Direction push = s.getValue(ThrusterBlock.FACING).getOpposite();
                thrustCap[push.ordinal()] += tb.tier.force; thrustPow[push.ordinal()] += tb.tier.power; thrusters++;
            } else if (b instanceof GyroBlock gb) { gyroCap += gb.capacity; gyroPower += gb.capacity / 3000.0; segGyro.merge(segOf.getOrDefault(p, 0), gb.capacity, Double::sum); }
            else if (b instanceof SailBlock) sails++;
            else if (b instanceof PilotSeatBlock && seat == null) { seat = p; forward = s.getValue(PilotSeatBlock.FACING); }
        }

        for (Joint j : joints) for (BlockPos p : j.blocks) jointOf.put(p, j.id);
        // ---- joint torque & static load ----
        for (Joint j : joints) {
            Vec3 r = j.subCom.subtract(j.pivot);
            Vec3 a = axisVec(j.axis);
            Vec3 perp = r.subtract(a.scale(a.dot(r)));
            j.reqTorque = j.axis == Direction.Axis.Y ? j.subMass * G * 0.1 * perp.length() : j.subMass * G * perp.length();
            double f = j.subMass * G, mo = j.subMass * G * Math.sqrt(r.x * r.x + r.z * r.z);
            j.staticRatio = j.subMass <= 0 ? 0 : Math.max(f / j.forceCap, mo / j.momentCap);
        }
        footing();
    }

    /** Footing and balance, worked out from the feet up.
     *  Feet = parts holding the lowest blocks. A walker stands if:
     *  1) the Hull Core's part has a gyro (it needs power while running),
     *  2) every joint from each foot up to the core has a strong enough motor,
     *  3) the centre of mass is over the feet (the support area), widened a little by the gyro. */
    private void footing() {
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : solid) minY = Math.min(minY, p.getY());
        Set<Integer> footSegs = new TreeSet<>();
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : solid) if (p.getY() == minY) {
            footSegs.add(segOf.getOrDefault(p, 0));
            x0 = Math.min(x0, p.getX()); z0 = Math.min(z0, p.getZ()); x1 = Math.max(x1, p.getX() + 1); z1 = Math.max(z1, p.getZ() + 1);
        }
        legged = footSegs.stream().anyMatch(sg -> sg != 0) && joints.stream().anyMatch(j -> j.mode == WALK_A || j.mode == WALK_B || j.mode == PROGRAM);
        coreGyro = segGyro.getOrDefault(0, 0.0);
        if (!legged) return;
        support = new AABB(x0, minY, z0, x1, minY + 1, z1);
        double margin = coreGyro <= 0 ? 0 : 0.25 + 1.5 * Math.min(1, coreGyro / mass);
        AABB area = support.inflate(margin, 0, margin);
        double cx = Math.max(area.minX, Math.min(area.maxX, com.x)), cz = Math.max(area.minZ, Math.min(area.maxZ, com.z));
        balanceOffset = new Vec3(com.x - cx, 0, com.z - cz);
        boolean pathsOk = true;
        for (int sg : footSegs) {
            String problem = null;
            int cur = sg, guard = 0;
            while (problem == null && cur > 0 && guard++ < 1000) {
                Seg seg = segs.get(cur);
                if (seg.parentJoint < 0) break;
                Joint j = joints.get(seg.parentJoint);
                if (j.motors == 0) problem = "joint J" + j.id + " has no motor";
                else if (j.motorTorque < j.reqTorque) problem = "joint J" + j.id + " motor too weak";
                cur = Math.max(0, j.parentSeg);
            }
            feet.add(new Foot(sg, 0, problem == null, problem == null ? "" : problem));
            pathsOk &= problem == null;
        }
        footingGyro = coreGyro > 0 && pathsOk ? coreGyro : 0;
    }
    public double coreGyro;
    public AABB support;
    /** How far (blocks) the centre of mass sits outside the support area. Zero when balanced. */
    public Vec3 balanceOffset = Vec3.ZERO;
    public boolean balanced() { return !legged || balanceOffset.lengthSqr() < 1e-6; }
    public boolean footingOk() { return !legged || footingGyro > 0; }

    /** Degrees per second this joint's movement asks for at the current gait speed. */
    public double neededSpeed(Joint j) {
        double cps = gaitSpeed;
        return switch (j.mode) {
            case WALK_A, WALK_B -> j.amp * 2 * Math.PI * cps;
            case PROGRAM -> { int mx = 0; for (int i = 0; i < KEYS; i++) mx = Math.max(mx, Math.abs(j.keys[(i + 1) % KEYS] - j.keys[i])); yield mx * KEYS * cps * j.speedFactor; }
            case SWAY -> j.amp * 0.5 * 0.06 * 20;
            case SPIN -> 360;
            default -> 0;
        };
    }
    /** Fraction of the asked gait speed the gears allow (1 = full speed). */
    public double gaitScale() {
        double sc = 1;
        for (Joint j : joints) if ((j.mode == WALK_A || j.mode == WALK_B || j.mode == PROGRAM) && j.active()) { double need = neededSpeed(j); if (need > 0) sc = Math.min(sc, j.maxSpeed / need); }
        return sc;
    }

    private void addEngine(BlockPos p, EngineTier t, boolean running, String note) {
        CompoundTag tag = bes.get(p);
        long en = tag == null ? 0 : tag.getLong("Energy");
        boolean primed = tag != null && tag.getBoolean("Primed");
        if (running) {
            output += t.output;
            if (t == EngineTier.ZERO_POINT) { if (primed) infinite = true; else note = "not primed (use a Nether Star)"; }
            energy += en;
        }
        engines.add(new EngineInfo(t.title, t == EngineTier.ZERO_POINT && primed ? -1 : en, t.capacity, t.output, running, note, t == EngineTier.ZERO_POINT));
    }

    public static Vec3 axisVec(Direction.Axis a) { return a == Direction.Axis.X ? new Vec3(1, 0, 0) : a == Direction.Axis.Y ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1); }

    public boolean hasWalkers() { for (Joint j : joints) if ((j.mode == WALK_A || j.mode == WALK_B || j.mode == PROGRAM) && !j.stalled()) return true; return false; }
    public double walkDrive() { double d = 0; for (Joint j : joints) if ((j.mode == WALK_A || j.mode == WALK_B || j.mode == PROGRAM) && !j.stalled()) d += j.motorDrive; return d; }
    public double walkPower() { double d = 0; for (Joint j : joints) if (j.active()) d += j.motorPower; return d; }
    public double maxDraw() { double d = drivePower + gyroPower + walkPower(); for (double v : thrustPow) d += v; return d; }

    /** Hinge angle (radians) for a joint given the animation state. */
    public double angle(Joint j, double walkPhase, double walkBlend, double spin, double time) {
        if (!j.active()) return 0;
        double amp = Math.toRadians(j.amp);
        return switch (j.mode) {
            case WALK_A -> amp * Math.sin(walkPhase) * walkBlend;
            case WALK_B -> amp * Math.sin(walkPhase + Math.PI) * walkBlend;
            case SPIN -> spin;
            case SWAY -> amp * 0.5 * Math.sin(time * 0.06 + j.id * 0.9);
            case PROGRAM -> {
                double f = walkPhase / (2 * Math.PI) * j.speedFactor + j.phase / 360.0;
                if (j.loop) f = time / 20.0 * gaitSpeed * j.speedFactor + j.phase / 360.0;
                f = f - Math.floor(f);
                double idx = f * KEYS; int i0 = (int) Math.floor(idx) % KEYS, i1 = (i0 + 1) % KEYS; double t = idx - Math.floor(idx);
                double deg = j.keys[i0] * (1 - t) + j.keys[i1] * t;
                yield Math.toRadians(deg) * (j.loop ? 1 : walkBlend);
            }
            default -> 0;
        };
    }

    /** Per-segment local transforms (articulation only). */
    public Matrix4d[] transforms(double walkPhase, double walkBlend, double spin, double time) {
        Matrix4d[] out = new Matrix4d[segs.size()];
        for (Seg s : segs) {
            if (s.parentJoint < 0) { out[s.id] = new Matrix4d(); continue; }
            Joint j = joints.get(s.parentJoint);
            Matrix4d parent = j.parentSeg >= 0 && out[j.parentSeg] != null ? out[j.parentSeg] : new Matrix4d();
            double ang = angle(j, walkPhase, walkBlend, spin, time);
            Vec3 a = axisVec(j.axis);
            out[s.id] = new Matrix4d(parent).translate(j.pivot.x, j.pivot.y, j.pivot.z).rotate(ang, a.x, a.y, a.z).translate(-j.pivot.x, -j.pivot.y, -j.pivot.z);
        }
        return out;
    }

    /** All local positions removed if this joint snaps (joint blocks + everything hanging on it). */
    public Set<BlockPos> subtreeBlocks(int jointId) {
        Set<BlockPos> out = new HashSet<>(joints.get(jointId).blocks);
        ArrayDeque<Integer> q = new ArrayDeque<>(joints.get(jointId).childSegs);
        while (!q.isEmpty()) {
            Seg s = segs.get(q.poll()); out.addAll(s.blocks);
            for (int cj : s.childJoints) { out.addAll(joints.get(cj).blocks); q.addAll(joints.get(cj).childSegs); }
        }
        return out;
    }
}
