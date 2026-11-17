package com.colossalcraft.colossus;

import com.colossalcraft.block.*;
import com.colossalcraft.block.SimpleParts.*;
import com.colossalcraft.block.Grid;
import static com.colossalcraft.block.SimpleParts.SIZE;
import static com.colossalcraft.block.SimpleParts.GU;
import static com.colossalcraft.block.SimpleParts.GV;
import com.colossalcraft.tier.EngineTier;
import com.colossalcraft.tier.Parts;
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
    public static final String[] MODES = {"LOCKED", "LEG", "LEG", "SPIN", "SWAY", "PROGRAM"};
    /** LEG (1) drives a leg from the hip; 2 is an old save value and behaves like LEG. */
    public static final int LOCKED = 0, WALK_A = 1, LEG = 1, WALK_B = 2, SPIN = 3, SWAY = 4, PROGRAM = 5;
    public static boolean isLegMode(int m) { return m == LEG || m == WALK_B; }
    public static final int KEYS = 8;
    /** Reserved key in the joint config for whole-walker gait settings. */
    public static final String GAIT = "_gait";
    /** Reserved key for Locomotion Controller settings (IK solver, gait type, keys). */
    public static final String LOCO = "_loco";
    public static final String[] IK_MODES = {"Auto", "Analytic 2-link", "Analytic 3-link", "FABRIK", "CCD"};
    public static final int IK_AUTO = 0, IK_ANALYTIC2 = 1, IK_ANALYTIC3 = 2, IK_FABRIK = 3, IK_CCD = 4;
    public static final String[] GAIT_MODES = {"Biped", "Biped (digitigrade)", "Quadruped", "Spider", "Custom", "Triped", "Wave"};
    public static final int GAIT_BIPED = 0, GAIT_DIGI = 1, GAIT_QUAD = 2, GAIT_SPIDER = 3, GAIT_CUSTOM = 4, GAIT_TRIPED = 5, GAIT_WAVE = 6;
    public int ikMode = 0, gaitMode = 0, locoControllers, jumpDrives;
    /** Steps per second (one full leg cycle). */
    public double gaitSpeed = 0.5;
    /** Gait shape (Locomotion Controller): stride as a share of the legs' reach, step height as a share of leg length,
     *  stance share of each cycle (0 = the gait type's default). */
    public double gaitStride = 0.45, gaitLift = 0.2, gaitDutyCfg = 0;
    /** Hull height (Locomotion Controller): the hips' height above the feet as a share of the legs' standing height. */
    public double gaitHeight = 0.95;
    /** Share of each step cycle a foot is on the ground. */
    public double duty() {
        if (gaitDutyCfg > 0) return gaitDutyCfg;
        int n = Math.max(1, legChains.size());
        return switch (gaitMode) {
            case GAIT_QUAD -> 0.7; case GAIT_SPIDER, GAIT_CUSTOM -> 0.65;
            case GAIT_TRIPED -> 0.75;                                   // one leg of three up at a time
            case GAIT_WAVE -> Math.max(0.6, 1 - 0.9 / n);              // one leg up at a time: slow and very steady
            default -> 0.6;
        };
    }
    /** Grip of a block from its slipperiness (vanilla friction: 0.6 normal, 0.98 ice). */
    public static double grip(double friction) { return Math.max(0.02, Math.min(1.5, 2.5 * (1 - friction))); }

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
        /** Assembly side (1 for a 1x1 or small joint), joint kind, and whether it has its own motor (powered ball joints). */
        public int size = 1; public JointShape shape = JointShape.FULL; public boolean powered;
        /** A powered ball joint set to Leg or Sway: a two-way joint (forward/back like a hinge about {@link #axis}, plus sideways). */
        public boolean universal;
        public Direction.Axis axis; public long key;
        public String label() { return (size > 1 || height > 1 ? (ball ? size + "x" + height + "x" + size + " " : size + "x" + size + " ") : "") + shape.title; }
        /** Ball joints: layers high (1..size), and how far it can turn either way at most (degrees). Others: the limit cap. */
        public int height = 1, romCap = 180;
        /** How far it can fold either way before its limb hits the rest of the Colossus (radians). */
        public double fold = Math.PI, foldPos = Math.PI, foldNeg = Math.PI;
        /** The joint angle held inside its range of motion and inside each fold side (knees fold one way only as far as they fit). */
        public double clampAngle(double a) { double rom = Math.toRadians(Math.max(2, Math.min(180, amp * 2))); return Math.max(-Math.min(rom, foldNeg), Math.min(Math.min(rom, foldPos), a)); }
        /** Turn limit either way (radians): the range of motion set in the Joints tab, but never into the Colossus itself. */
        public double limRad() { return Math.min(Math.toRadians(Math.max(2, Math.min(180, amp * 2))), fold); }
        public final List<BlockPos> blocks = new ArrayList<>();
        public final List<Integer> childSegs = new ArrayList<>();
        public Vec3 pivot = Vec3.ZERO, subCom = Vec3.ZERO;
        public double subMass, motorTorque, motorPower, motorDrive, reqTorque, forceCap, momentCap, staticRatio;
        /** Standing estimate (feet on the ground): weight this joint carries from above (kg), turning force on its axis (N*m),
         *  load vs strength, and the share of the whole rig's weight its feet carry (0-1). */
        public double standMass, standTorque, standRatio, footShare;
        /** Motor torque needed to hold this joint: lifted (swing) or standing, whichever is bigger. */
        public double needTorque() { return Math.max(reqTorque, standTorque); }
        /** Fastest the gears let this joint turn (degrees per second). 0 = no motor. */
        public double maxSpeed;
        /** Program: keyframe angles (degrees), phase offset (degrees), speed factor, play even when standing still. */
        public int[] keys = new int[KEYS]; public int phase; public double speedFactor = 1; public boolean loop;
        /** Leg data: the foot it stands on (local), automatic step timing (0 or 0.5). */
        public boolean leg; public Vec3 foot = Vec3.ZERO; public double autoPhase;
        /** Ball joint: levels the part below it, within limit (degrees = amp * 2). Step group (Custom gait). */
        public boolean ball; public int group;
        /** Propeller data (Spin joints): sum of blade area * r^2, area * r^3, and which way along the axis it pushes. */
        public double bladeA2, bladeA3; public int blades; public double thrustSign = 1;
        /** Wheel data (Spin joints without blades): radius (blocks), +-1 so positive spin drives forward, touches the ground. */
        public double wheelRadius; public int wheelSign = 1; public boolean wheelGround;
        /** Gear ratios seen by this joint's motors (for the screen). */
        public final List<Double> ratios = new ArrayList<>();
        public boolean stalled() { return !ball && mode != LOCKED && !childSegs.isEmpty() && (motorTorque <= 0 || motorTorque < reqTorque); }
        public boolean active() { return mode != LOCKED && !childSegs.isEmpty() && !stalled(); }
    }
    /** What a joint is for and which mode it should use: {role text, recommended mode, -1 = any}. */
    public Object[] role(Joint j) {
        if (j.ball && j.powered) {
            boolean footBelow = j.footShare > 0;
            boolean topOfLeg = footBelow; // no other leg joint above it on the way to the body
            int cur = Math.max(0, j.parentSeg), guard = 0;
            while (topOfLeg && cur > 0 && guard++ < 1000) { Seg sg = segs.get(cur); if (sg.parentJoint < 0) break; Joint pj = joints.get(sg.parentJoint); if (isLegMode(pj.mode)) topOfLeg = false; cur = Math.max(0, pj.parentSeg); }
            boolean ankle = footBelow && !topOfLeg;
            if (ankle) return new Object[]{"Ankle (powered ball): Locked keeps the foot flat and tilts it to slopes.", LOCKED};
            if (footBelow) return new Object[]{"Hip (powered ball): Leg swings the leg forward/back AND sideways, so the body can lean over a foot. Needs no extra motor.", LEG};
            return new Object[]{"Shoulder (powered ball): Sway swings the arm with the steps; Locked holds it still.", -1};
        }
        if (j.ball) return new Object[]{"Ankle (ball joint): keeps the foot level by itself. Set its range with the arrows. For a hip, use a Powered Ball Joint or put a Servo Motor against it. Bigger ball joints (2x2x2 cubes) turn further.", -1};
        if (j.childSegs.isEmpty()) return new Object[]{"Nothing hangs from this joint.", -1};
        boolean footBelow = j.footShare > 0;
        if (footBelow && j.axis == Direction.Axis.Y) return new Object[]{"Twists a whole leg: walkers can't step with it. Use Locked.", LOCKED};
        if (footBelow) {
            for (LegChain L : legChains) {
                int k = L.joints().indexOf(j.id);
                if (k == 0) return new Object[]{"Hip: swings the leg forward and back. Use Leg (needs a motor).", LEG};
                if (k > 0) return new Object[]{"Knee: bends the leg and holds the weight up. Use Leg (needs a motor).", LEG};
            }
            return new Object[]{"Hip or knee of a leg: use Leg so the leg can step (needs a motor).", LEG};
        }
        if (j.blades > 0) return new Object[]{"Rotor: blades on it make thrust. Use Spin.", SPIN};
        if (j.mode == SPIN) return new Object[]{j.wheelGround ? "Wheel: turns with the Spin keys (Keys tab) and drives the Colossus along the ground. Brake stops it." : "Spins with the Spin keys (Keys tab): clockwise, counter-clockwise, brake.", -1};
        return new Object[]{"Arm, tail or turret: Locked (rigid), Spin (keeps turning) or Sway (idle motion).", -1};
    }
    public record Thruster(BlockPos pos, Direction push, double force, double power, int seg) {}
    /** Motors of the same material and axis touching each other: one bigger motor (n x n grid). */
    public record MotorCluster(List<BlockPos> blocks, Direction.Axis axis, int k, int n, Vec3 center, int joint, double ratio) {}
    /** A position with its coordinate along an axis set to 0 (to compare grid squares stacked along that axis). */
    private static BlockPos flat(BlockPos p, Direction.Axis a) { return a == Direction.Axis.X ? new BlockPos(0, p.getY(), p.getZ()) : a == Direction.Axis.Y ? new BlockPos(p.getX(), 0, p.getZ()) : new BlockPos(p.getX(), p.getY(), 0); }
    public final List<Thruster> thrusterList = new ArrayList<>();
    public final List<MotorCluster> motorClusters = new ArrayList<>();
    /** Motor and gearbox blocks that turn: {joint id, ratio (turns per joint turn)}. */
    public final Map<BlockPos, double[]> spinParts = new HashMap<>();
    public final Set<Integer> footSegs = new TreeSet<>();
    /** Blocks of big ball joints that move with the child part (the rest stay with the parent). */
    /** Powered ball joints: how far each tilts its foot to match the ground (radians, about the right and forward axes). */
    public double[] ballPitch = new double[0], ballRoll = new double[0];
    /** Sideways angle of each powered hip (radians, from the IK). */
    public double[] ikRoll = new double[0], ikYaw = new double[0];
    /** The walker's step clock (cycles) and how much it's walking (0..1): shoulders swing in time. */
    public double walkClock, walkAmount;
    double rollAngle(Joint j, double time) { return j.id < ikRoll.length && !Double.isNaN(ikRoll[j.id]) ? ikRoll[j.id] : 0; }
    /** Ankle torque of powered ball joints that carry a foot: it lets a walker balance a little further out, like a gyro. */
    public double ankleTorque() { double t = 0; for (Joint j : joints) if (j.powered && j.footShare > 0) t += j.motorTorque; return t; }
    /**
     * A leg for the walker controller: joints set to Leg on the way from the body down to a foot (top first).
     * hip/foot are rest positions (local). length = hip to foot. minLen < length if the leg has a knee.
     * hipTorque limits sideways push (torque / hip height); vCap = what the weakest joint can carry straight down; mu = foot friction.
     */
    public record LegChain(List<Integer> joints, int footSeg, int bodySeg, Vec3 hip, Vec3 foot, double length, double minLen,
                           double hipTorque, double vCap, double mu, int group, double[] limits,
                           double kneeTorque, Vec3 ankle, List<BlockPos> soles) {
        /** The point IK steers: the ankle (ball joint) if there is one, else the foot. */
        public Vec3 effector() { return ankle != null ? ankle : foot; }
    }
    public final List<LegChain> legChains = new ArrayList<>();
    public final Set<Integer> legSegs = new HashSet<>();
    /** IK joint angles from the walker controller (radians, NaN = not driven), and the previous tick's, for smooth drawing. */
    public double[] ikAngles, ikPrev; public double ikT = 1;
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
    /** Smart footing (Locomotion Controller, on by default): each step looks for a safe, level foothold. Uses power. */
    public boolean smartFooting;
    public static final double SMART_FOOTING_POWER = 25;
    public BlockPos seat; public Direction forward = Direction.NORTH;
    public double radius = 1; public AABB localBounds = new AABB(0, 0, 0, 1, 1, 1);

    public StructureModel(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> bes, CompoundTag jointCfg) {
        this.blocks = blocks; this.bes = bes == null ? new HashMap<>() : bes;
        build(jointCfg == null ? new CompoundTag() : jointCfg);
    }

    public static String jointKey(long k) { return Long.toString(k); }
    /** Joint settings are kept by each joint's first block (relative to the Hull Core). Parking turned the build round: move them with it. */
    public static CompoundTag rotateCfg(CompoundTag cfg, StructureModel m, net.minecraft.world.level.block.Rotation rot) {
        if (rot == net.minecraft.world.level.block.Rotation.NONE) return cfg.copy();
        CompoundTag out = new CompoundTag();
        for (String k : cfg.getAllKeys()) { boolean joint; try { Long.parseLong(k); joint = true; } catch (NumberFormatException e) { joint = false; } if (!joint) out.put(k, cfg.get(k).copy()); }
        for (Joint j : m.joints) {
            String old = jointKey(j.key);
            if (!cfg.contains(old)) continue;
            BlockPos first = null;
            for (BlockPos p : j.blocks) { BlockPos q = p.rotate(rot); if (first == null || q.compareTo(first) < 0) first = q; }
            out.put(jointKey(first.asLong()), cfg.getCompound(old).copy());
        }
        return out;
    }

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
        // Bearings: one joint per square assembly (n x n, stacked along the axis is fine). Ball joints: one joint per n x h x n box.
        // Slab / panel / pin joints side by side join.
        List<List<BlockPos>> groups = new ArrayList<>();
        Set<BlockPos> jointPos = new HashSet<>(), grouped = new HashSet<>();
        Map<BlockPos, List<BlockPos>> balls = new LinkedHashMap<>();
        for (var e : blocks.entrySet()) {
            BlockPos p = e.getKey(); BlockState st = e.getValue();
            if (!(st.getBlock() instanceof JointBlock)) continue;
            jointPos.add(p);
            if (st.getBlock() instanceof BallJointBlock) { balls.computeIfAbsent(Grid.origin(p, st), x -> new ArrayList<>()).add(p); grouped.add(p); }
        }
        groups.addAll(balls.values());
        for (BlockPos start : jointPos) {
            if (grouped.contains(start)) continue;
            BlockState s0 = blocks.get(start); JointBlock jb0 = (JointBlock) s0.getBlock();
            Direction.Axis ax0 = s0.getValue(RotatedPillarBlock.AXIS);
            List<BlockPos> g = new ArrayList<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); grouped.add(start);
            BlockPos o0 = jb0 instanceof BearingBlock ? flat(Grid.origin(start, s0), ax0) : null;
            while (!q.isEmpty()) {
                BlockPos p = q.poll(); g.add(p);
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d); BlockState ns = blocks.get(n);
                    if (ns == null || !jointPos.contains(n) || grouped.contains(n) || !(ns.getBlock() instanceof JointBlock nb) || ns.getValue(RotatedPillarBlock.AXIS) != ax0) continue;
                    boolean ok = jb0 instanceof BearingBlock
                            ? nb instanceof BearingBlock && ns.getValue(SIZE).equals(s0.getValue(SIZE)) && flat(Grid.origin(n, ns), ax0).equals(o0)
                            : !(nb instanceof BearingBlock) && !nb.shape.ball();
                    if (ok) { grouped.add(n); q.add(n); }
                }
            }
            groups.add(g);
        }
        Map<BlockPos, Integer> clusterOf = new HashMap<>();
        for (List<BlockPos> g : groups) {
            BlockPos first = g.stream().min(Comparator.naturalOrder()).get();
            BlockState ss = blocks.get(first);
            JointBlock jb = (JointBlock) ss.getBlock();
            Joint j = new Joint(); j.id = joints.size();
            boolean bj = jb instanceof BallJointBlock;
            j.shape = jb.shape;
            j.axis = bj ? Direction.Axis.Y : ss.getValue(RotatedPillarBlock.AXIS);
            j.size = jb instanceof BearingBlock || bj ? ss.getValue(SIZE) : 1;
            j.height = bj ? Math.max(1, Math.min(j.size, ss.getValue(SimpleParts.HT))) : 1;
            double px = 0, py = 0, pz = 0;
            for (BlockPos p : g) { j.blocks.add(p); clusterOf.put(p, j.id); px += p.getX() + .5; py += p.getY() + .5; pz += p.getZ() + .5; }
            if (jb instanceof BearingBlock || bj) {
                int layers = jb instanceof BearingBlock ? Math.max(1, g.size() / (j.size * j.size)) : j.height;
                j.area = Parts.scale(j.size) * j.shape.strength * Math.sqrt(layers); // longer stacks help a little
            } else for (BlockPos p : g) j.area += ((JointBlock) blocks.get(p).getBlock()).shape.strength;
            j.k = j.blocks.size(); j.key = first.asLong();
            j.pivot = new Vec3(px / j.k, py / j.k, pz / j.k);
            j.forceCap = Parts.BEARING_FORCE * j.area; j.momentCap = Parts.BEARING_MOMENT * j.area;
            CompoundTag jc = cfg.getCompound(jointKey(j.key));
            j.mode = Math.max(0, Math.min(MODES.length - 1, jc.getByte("Mode")));
            if (jc.contains("Amp")) j.amp = jc.getInt("Amp");
            int[] k = jc.getIntArray("Keys"); for (int i = 0; i < Math.min(KEYS, k.length); i++) j.keys[i] = k[i];
            j.phase = jc.getInt("Phase"); if (jc.contains("SpeedX")) j.speedFactor = jc.getInt("SpeedX") / 100.0; j.loop = jc.getBoolean("Loop");
            j.group = jc.getInt("Group");
            j.ball = j.shape.ball();
            j.powered = j.shape == JointShape.POWERED_BALL;
            if (j.ball) { j.romCap = BallJointBlock.romCap(j.size, j.height); j.amp = Math.min(jc.contains("Amp") ? j.amp : Math.min(22, j.romCap / 2), Math.max(1, j.romCap / 2)); } // default +-44 degrees, never past what its shape allows
            else if (isLegMode(j.mode) && !jc.contains("Amp")) j.amp = 45; // legs: +-90 degrees, so knees can fold enough to lift a foot onto a step
            if (j.powered) { j.motorTorque += Parts.MOTOR_TORQUE * j.area; j.motorPower += Parts.MOTOR_POWER; }
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

        if (cfg.contains(GAIT)) {
            CompoundTag gc = cfg.getCompound(GAIT);
            if (gc.contains("Speed")) gaitSpeed = Math.max(0.05, gc.getInt("Speed") / 100.0);
            if (gc.contains("Stride")) gaitStride = Math.max(0.2, Math.min(1.0, gc.getInt("Stride") / 100.0));
            if (gc.contains("Lift")) gaitLift = Math.max(0.05, Math.min(0.5, gc.getInt("Lift") / 100.0));
            if (gc.contains("Height")) gaitHeight = Math.max(0.5, Math.min(1.0, gc.getInt("Height") / 100.0));
            if (gc.contains("Duty")) gaitDutyCfg = gc.getInt("Duty") <= 0 ? 0 : Math.max(0.5, Math.min(0.9, gc.getInt("Duty") / 100.0));
        }
        CompoundTag lcfg = cfg.getCompound(LOCO);
        if (cfg.contains(LOCO)) { ikMode = Math.floorMod(lcfg.getInt("IK"), IK_MODES.length); gaitMode = Math.floorMod(lcfg.getInt("Gait"), GAIT_MODES.length); }
        smartFooting = !lcfg.contains("Smart") || lcfg.getBoolean("Smart");
        // ---- gears: from each joint walk through touching gearboxes; a motor gets the ratio of the gearbox it touches ----
        Map<BlockPos, double[]> motorGear = new HashMap<>(); // motor -> {joint, ratio, efficiency}
        for (Joint j : joints) {
            ArrayDeque<BlockPos> gq = new ArrayDeque<>(); Map<BlockPos, double[]> seen = new HashMap<>();
            for (BlockPos jp : j.blocks) for (Direction d : Direction.values()) {
                BlockPos n = jp.relative(d); BlockState ns = blocks.get(n);
                if (ns == null) continue;
                if (ns.getBlock() instanceof MotorBlock) motorGear.putIfAbsent(n, new double[]{j.id, 1, 1});
                else if (ns.getBlock() instanceof GearboxBlock g && !seen.containsKey(n)) { double r = gearRatio(g, ns, d); seen.put(n, new double[]{r, g.efficiency}); gq.add(n); spinParts.put(n, new double[]{j.id, r, r}); }
            }
            while (!gq.isEmpty()) {
                BlockPos gp = gq.poll(); double[] cur = seen.get(gp);
                for (Direction d : Direction.values()) {
                    BlockPos n = gp.relative(d); BlockState ns = blocks.get(n);
                    if (ns == null) continue;
                    if (ns.getBlock() instanceof MotorBlock) motorGear.putIfAbsent(n, new double[]{j.id, cur[0], cur[1]});
                    else if (ns.getBlock() instanceof GearboxBlock g && !seen.containsKey(n) && seen.size() < 64) { double r = gearRatio(g, ns, d); seen.put(n, new double[]{cur[0] * r, cur[1] * g.efficiency}); gq.add(n); spinParts.put(n, new double[]{j.id, cur[0] * r, r}); }
                }
            }
        }
        // ---- parts: motors, engines, thrusters, gyros, seat ----
        for (var e : blocks.entrySet()) {
            BlockPos p = e.getKey(); BlockState s = e.getValue(); Block b = s.getBlock();
            if (b instanceof MotorBlock) {
                // handled as clusters below
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
                thrusterList.add(new Thruster(p, push, tb.tier.force, tb.tier.power, segOf.getOrDefault(p, 0)));
            } else if (b instanceof GyroBlock gb) { gyroCap += gb.capacity; gyroPower += gb.capacity / 3000.0; segGyro.merge(segOf.getOrDefault(p, 0), gb.capacity, Double::sum); }
            else if (b instanceof SailBlock && !(b instanceof RotorBladeBlock)) sails++;
            else if (b instanceof LocomotionControllerBlock) locoControllers++;
            else if (b instanceof JumpDriveBlock) jumpDrives++;
            else if (b instanceof PilotSeatBlock && seat == null) { seat = p; forward = s.getValue(PilotSeatBlock.FACING); }
        }

        // ---- motor clusters (parallel motors) ----
        Set<BlockPos> doneM = new HashSet<>();
        for (var e : blocks.entrySet()) {
            if (!(e.getValue().getBlock() instanceof MotorBlock) || doneM.contains(e.getKey())) continue;
            BlockState m0 = e.getValue();
            Direction.Axis ax = m0.getValue(RotatedPillarBlock.AXIS);
            int n = m0.getValue(SIZE);
            BlockPos o0 = flat(Grid.origin(e.getKey(), m0), ax);
            List<BlockPos> cl = new ArrayList<>(); ArrayDeque<BlockPos> mq = new ArrayDeque<>(); mq.add(e.getKey()); doneM.add(e.getKey());
            double cx = 0, cy = 0, cz = 0;
            while (!mq.isEmpty()) { // one square motor (possibly several layers deep along its shaft)
                BlockPos p = mq.poll(); cl.add(p); cx += p.getX() + .5; cy += p.getY() + .5; cz += p.getZ() + .5;
                for (Direction d : Direction.values()) {
                    BlockPos nb = p.relative(d); BlockState ns = blocks.get(nb);
                    if (ns != null && !doneM.contains(nb) && ns.getBlock() instanceof MotorBlock && ns.getValue(RotatedPillarBlock.AXIS) == ax && ns.getValue(SIZE) == n
                            && flat(Grid.origin(nb, ns), ax).equals(o0)) { doneM.add(nb); mq.add(nb); }
                }
            }
            int k = cl.size(), layers = Math.max(1, k / (n * n));
            double sc = Parts.scale(n) * layers;
            double[] best = null;
            for (BlockPos p : cl) { double[] mg = motorGear.get(p); if (mg != null && (best == null || mg[1] < best[1])) best = mg; }
            Vec3 center = new Vec3(cx / k, cy / k, cz / k);
            if (best == null) { driveMotors += k; driveForce += Parts.MOTOR_DRIVE * sc; drivePower += Parts.MOTOR_POWER * sc; motorClusters.add(new MotorCluster(cl, ax, k, n, center, -1, 1)); continue; }
            Joint j = joints.get((int) best[0]); double ratio = best[1], eff = best[2];
            j.motors += k; j.motorTorque += Parts.MOTOR_TORQUE * sc * ratio * eff; j.motorPower += Parts.MOTOR_POWER * sc; j.motorDrive += Parts.MOTOR_DRIVE * sc;
            double sp = Parts.MOTOR_RPM * 6 / ratio;
            j.maxSpeed = j.maxSpeed == 0 ? sp : Math.min(j.maxSpeed, sp); j.ratios.add(ratio);
            if (j.ball) j.powered = true; // a motor on a ball joint assembly powers it
            motorClusters.add(new MotorCluster(cl, ax, k, n, center, j.id, ratio));
            for (BlockPos p : cl) spinParts.put(p, new double[]{j.id, ratio});
        }
        // ---- propeller blades on Spin joints ----
        for (var e : blocks.entrySet()) {
            if (!(e.getValue().getBlock() instanceof SailBlock)) continue;
            int sg = segOf.getOrDefault(e.getKey(), 0), guard = 0;
            Joint spinJ = null;
            while (sg > 0 && guard++ < 1000) { Seg sgm = segs.get(sg); if (sgm.parentJoint < 0) break; Joint pj = joints.get(sgm.parentJoint); if (pj.mode == SPIN) { spinJ = pj; break; } sg = Math.max(0, pj.parentSeg); }
            if (spinJ == null) continue;
            Vec3 a = axisVec(spinJ.axis), c = Vec3.atCenterOf(e.getKey()).subtract(spinJ.pivot);
            double r = c.subtract(a.scale(a.dot(c))).length();
            double area = e.getValue().getBlock() instanceof RotorBladeBlock ? 0.6 : 1.0;
            spinJ.bladeA2 += area * r * r; spinJ.bladeA3 += area * r * r * r; spinJ.blades++;
        }
        for (Joint j : joints) if (j.blades > 0) { double d = j.subCom.subtract(j.pivot).dot(axisVec(j.axis)); j.thrustSign = d < 0 ? -1 : 1; }
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
        // powered ball joints set to Leg (hips) or Sway (shoulders) swing like a hinge across the forward direction, plus sideways
        for (Joint j : joints) if (j.ball && j.powered && (isLegMode(j.mode) || j.mode == SWAY)) {
            j.universal = true; j.axis = forward.getAxis() == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
        }
        // wheels: Spin joints without blades. Size, which way "forward" turns them, and whether they reach the ground
        for (Joint j : joints) if (j.mode == SPIN && j.blades == 0 && !j.childSegs.isEmpty()) {
            Vec3 a = axisVec(j.axis); double rMax = 0, low = Double.MAX_VALUE;
            for (int cs : j.childSegs) for (BlockPos p : segs.get(cs).blocks) {
                Vec3 r = Vec3.atCenterOf(p).subtract(j.pivot); Vec3 perp = r.subtract(a.scale(a.dot(r)));
                rMax = Math.max(rMax, perp.length() + 0.5); low = Math.min(low, p.getY());
            }
            j.wheelRadius = rMax;
            Vec3 roll = a.cross(new Vec3(0, 1, 0)); // which way the ground pushes it when it turns forward (positive)
            Vec3 fw = Vec3.atLowerCornerOf(forward.getNormal());
            j.wheelSign = roll.dot(fw) < -0.5 ? -1 : 1;
            j.wheelGround = j.axis != Direction.Axis.Y && low <= localBounds.minY + 0.01;
            wheels++;
        }
        foldLimits();
        standLoads();
        footing();
    }
    /** How far each leg joint can fold before its limb hits another part of the Colossus (checked in 5 degree steps, both ways). */
    private void foldLimits() {
        for (Joint j : joints) {
            if (j.childSegs.isEmpty() || !(isLegMode(j.mode) || j.ball)) continue;
            Set<BlockPos> sub = new HashSet<>();
            ArrayDeque<Integer> q = new ArrayDeque<>(j.childSegs);
            while (!q.isEmpty() && sub.size() < 3000) { Seg sg = segs.get(q.poll()); sub.addAll(sg.blocks); for (int cj : sg.childJoints) q.addAll(joints.get(cj).childSegs); }
            Set<BlockPos> own = new HashSet<>(j.blocks);
            Direction.Axis[] axes = j.ball && !j.universal ? new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z} : new Direction.Axis[]{j.axis};
            double fold = Math.PI; double[] side = {Math.PI, Math.PI}; // [negative turn, positive turn]
            for (Direction.Axis ax : axes) for (int sgn = -1; sgn <= 1; sgn += 2) {
                Vec3 a = axisVec(ax); double safe = Math.PI;
                steps:
                for (int deg = 5; deg <= 180; deg += 5) {
                    double t = Math.toRadians(deg) * sgn, c = Math.cos(t), sn = Math.sin(t);
                    for (BlockPos b : sub) {
                        Vec3 r = Vec3.atCenterOf(b).subtract(j.pivot);
                        Vec3 rr = r.scale(c).add(a.cross(r).scale(sn)).add(a.scale(a.dot(r) * (1 - c))); // Rodrigues
                        BlockPos hit = BlockPos.containing(j.pivot.add(rr));
                        if (solid.contains(hit) && !sub.contains(hit) && !own.contains(hit)) { safe = Math.toRadians(deg - 5); break steps; }
                    }
                }
                fold = Math.min(fold, safe);
                side[sgn < 0 ? 0 : 1] = Math.min(side[sgn < 0 ? 0 : 1], safe);
            }
            double min = Math.toRadians(5);
            if (axes.length > 1) { j.foldNeg = j.foldPos = Math.max(min, fold); } // a free ball: the same either way
            else { j.foldNeg = Math.max(min, side[0]); j.foldPos = Math.max(min, side[1]); }
            j.fold = Math.max(j.foldNeg, j.foldPos);
        }
    }
    /** Spin joints with no blades: they turn with the Spin keys and drive on the ground like wheels. */
    public int wheels;
    /** Wheel angle (radians), the same for every wheel: each turns it its own way so "forward" always drives forward. */
    public double wheelAngle;

    /** Standing loads, worked out from the feet up. Each foot carries a share of the whole weight
     *  (equal legs placed evenly get equal shares, shifted toward the foot nearer the centre of mass).
     *  A joint then carries its feet's share minus everything below it, so the ankle carries the most. */
    private void standLoads() {
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : solid) minY = Math.min(minY, p.getY());
        Map<Integer, Vec3> contact = new TreeMap<>();
        Map<Integer, Integer> cnt = new HashMap<>();
        for (BlockPos p : solid) if (p.getY() == minY) {
            int sg = segOf.getOrDefault(p, 0);
            contact.merge(sg, new Vec3(p.getX() + .5, minY, p.getZ() + .5), Vec3::add); cnt.merge(sg, 1, Integer::sum);
        }
        if (contact.isEmpty() || mass <= 0) return;
        List<Integer> ids = new ArrayList<>(contact.keySet());
        int n = ids.size();
        Vec3[] pt = new Vec3[n]; double mx = 0, mz = 0;
        for (int i = 0; i < n; i++) { pt[i] = contact.get(ids.get(i)).scale(1.0 / cnt.get(ids.get(i))); mx += pt[i].x / n; mz += pt[i].z / n; }
        // even split, nudged so the shares balance the centre of mass (least change from even)
        double cxx = 0, cxz = 0, czz = 0;
        for (Vec3 q : pt) { double dx = q.x - mx, dz = q.z - mz; cxx += dx * dx; cxz += dx * dz; czz += dz * dz; }
        double ex = com.x - mx, ez = com.z - mz, eps = 1e-6, det = (cxx + eps) * (czz + eps) - cxz * cxz;
        double lx = ((czz + eps) * ex - cxz * ez) / det, lz = ((cxx + eps) * ez - cxz * ex) / det;
        double[] w = new double[n]; double sum = 0;
        for (int i = 0; i < n; i++) { w[i] = Math.max(0, 1.0 / n + lx * (pt[i].x - mx) + lz * (pt[i].z - mz)); sum += w[i]; }
        for (int i = 0; i < n; i++) w[i] = sum <= 0 ? 1.0 / n : w[i] / sum;
        for (Joint j : joints) {
            double share = 0; Vec3 fp = Vec3.ZERO;
            for (int i = 0; i < n; i++) {
                int cur = ids.get(i), guard = 0; boolean under = false;
                while (cur > 0 && guard++ < 1000) { Seg sg = segs.get(cur); if (sg.parentJoint < 0) break; if (sg.parentJoint == j.id) { under = true; break; } cur = Math.max(0, joints.get(sg.parentJoint).parentSeg); }
                if (under) { share += w[i]; fp = fp.add(pt[i].scale(w[i])); }
            }
            j.footShare = share;
            if (share <= 0) continue;
            fp = fp.scale(1 / share);
            double up = share * mass * G, down = j.subMass * G;
            j.standMass = Math.max(0, share * mass - j.subMass);
            // turning force on the joint: ground push at the feet, minus the weight of what is below the joint
            Vec3 tq = fp.subtract(j.pivot).cross(new Vec3(0, up, 0)).add(j.subCom.subtract(j.pivot).cross(new Vec3(0, -down, 0)));
            j.standTorque = Math.abs(tq.dot(axisVec(j.axis)));
            j.standRatio = Math.max(j.standMass * G / j.forceCap, tq.length() / j.momentCap);
        }
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
        this.footSegs.addAll(footSegs);
        setupLegs(minY);
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
                // only joints that move the leg need motors; Locked joints are rigid, ball joints are passive
                if (isLegMode(j.mode) && !j.ball) {
                    if (j.motors == 0) problem = "joint J" + j.id + " has no motor";
                    else if (j.motorTorque < j.needTorque()) problem = "joint J" + j.id + " motor too weak";
                }
                cur = Math.max(0, j.parentSeg);
            }
            feet.add(new Foot(sg, 0, problem == null, problem == null ? "" : problem));
            pathsOk &= problem == null;
        }
        footingGyro = coreGyro > 0 && pathsOk ? coreGyro : 0;
    }
    /** Legs: hip joints set to Leg or Program, with a horizontal axis, that have a foot below them. */
    private void setupLegs(int minY) {
        Vec3 f = Vec3.atLowerCornerOf(forward.getNormal()), right = new Vec3(-f.z, 0, f.x);
        List<Joint> left = new ArrayList<>(), rightL = new ArrayList<>();
        for (Joint j : joints) {
            if (!(isLegMode(j.mode) || j.mode == PROGRAM) || j.axis == Direction.Axis.Y) continue;
            double fx = 0, fz = 0; int n = 0;
            for (int fs : footSegs) {
                int cur = fs, guard = 0; boolean under = false;
                while (cur > 0 && guard++ < 1000) { Seg sg = segs.get(cur); if (sg.parentJoint < 0) break; if (sg.parentJoint == j.id) { under = true; break; } cur = Math.max(0, joints.get(sg.parentJoint).parentSeg); }
                if (!under) continue;
                for (BlockPos p : segs.get(fs).blocks) if (p.getY() == minY && solid.contains(p)) { fx += p.getX() + .5; fz += p.getZ() + .5; n++; }
            }
            if (n == 0) continue;
            j.leg = true; j.foot = new Vec3(fx / n, minY, fz / n);
            if (isLegMode(j.mode)) (j.pivot.subtract(com).dot(right) < 0 ? left : rightL).add(j);
        }
        Comparator<Joint> byFront = Comparator.comparingDouble(j -> -j.pivot.subtract(com).dot(f));
        left.sort(byFront); rightL.sort(byFront);
        for (int i = 0; i < left.size(); i++) left.get(i).autoPhase = (i % 2) * 0.5;
        for (int i = 0; i < rightL.size(); i++) rightL.get(i).autoPhase = ((i + 1) % 2) * 0.5;
        // ---- leg chains for the physics walker
        Set<Integer> usedHips = new HashSet<>();
        for (int fs : footSegs) {
            if (fs == 0) continue;
            List<Integer> chain = new ArrayList<>();
            int cur = fs, guard = 0, body = 0;
            while (cur > 0 && guard++ < 1000) {
                Seg sg = segs.get(cur); if (sg.parentJoint < 0) break;
                Joint j = joints.get(sg.parentJoint);
                if (isLegMode(j.mode) && (!j.ball || j.universal) && (j.axis != Direction.Axis.Y || (!chain.isEmpty() && j.universal == false))) chain.add(0, j.id); // plain ball joints level themselves, never IK; a vertical one above the leg twists it
                cur = Math.max(0, j.parentSeg); body = cur;
                if (!chain.isEmpty() && !isLegMode(j.mode) && !j.ball) break;
            }
            if (chain.isEmpty() || !usedHips.add(chain.get(0))) continue;
            Joint hipJ = joints.get(chain.get(0));
            body = Math.max(0, hipJ.parentSeg);
            double fx = 0, fz = 0, mu = 0; int n = 0;
            for (BlockPos p : segs.get(fs).blocks) if (p.getY() == minY && solid.contains(p)) {
                fx += p.getX() + .5; fz += p.getZ() + .5; n++;
                float slip = blocks.get(p).getBlock().getFriction(); // vanilla: 0.6 normal, 0.98 ice
                mu += grip(slip);
            }
            if (n == 0) continue;
            Vec3 foot = new Vec3(fx / n, minY, fz / n);
            List<BlockPos> soles = new ArrayList<>();
            for (BlockPos p : segs.get(fs).blocks) if (p.getY() == minY && solid.contains(p)) soles.add(p);
            // a ball joint between the last leg joint and the foot is the ankle
            Vec3 ankle = null;
            { int c3 = fs, g3 = 0;
              while (c3 > 0 && g3++ < 1000) { Seg sg = segs.get(c3); if (sg.parentJoint < 0) break; Joint pj = joints.get(sg.parentJoint);
                  if (pj.id == chain.get(chain.size() - 1)) break; if (pj.ball && !pj.universal) { ankle = pj.pivot; break; } c3 = Math.max(0, pj.parentSeg); } }
            double len = foot.distanceTo(hipJ.pivot);
            double vCap = Double.MAX_VALUE, knee = 0; double[] lim = new double[chain.size()];
            for (int i = 0; i < chain.size(); i++) {
                Joint j = joints.get(chain.get(i)); vCap = Math.min(vCap, j.forceCap); lim[i] = j.limRad();
                if (i > 0) knee += j.motorTorque;
            }
            legChains.add(new LegChain(chain, fs, body, hipJ.pivot, foot, len, chain.size() > 1 ? len * 0.55 : len * 0.97, hipJ.motorTorque, vCap, mu / n,
                    hipJ.group, lim, knee, ankle, soles));
            int c2 = fs, g2 = 0;
            while (c2 > 0 && g2++ < 1000) { legSegs.add(c2); Seg sg = segs.get(c2); if (sg.parentJoint < 0 || sg.parentJoint == hipJ.id) break; c2 = Math.max(0, joints.get(sg.parentJoint).parentSeg); }
        }
        assignGroups();
        ballPitch = new double[joints.size()]; ballRoll = new double[joints.size()]; ikRoll = new double[joints.size()]; ikYaw = new double[joints.size()];
        ikAngles = new double[joints.size()]; Arrays.fill(ikAngles, Double.NaN); ikPrev = ikAngles.clone();
    }

    /** Which legs step together, from the gait type. */
    private void assignGroups() {
        if (legChains.isEmpty()) return;
        Vec3 f = Vec3.atLowerCornerOf(forward.getNormal()), right = new Vec3(-f.z, 0, f.x);
        List<LegChain> out = new ArrayList<>();
        int n = legChains.size();
        int[] grp = new int[n];
        switch (gaitMode) {
            case GAIT_CUSTOM -> { for (int i = 0; i < n; i++) grp[i] = Math.max(0, Math.min(3, legChains.get(i).group())); }
            case GAIT_TRIPED -> { // three groups going round the body (three legs: one at a time)
                Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i;
                Arrays.sort(idx, Comparator.comparingDouble(i -> { Vec3 d = legChains.get(i).foot().subtract(com); return Math.atan2(d.z, d.x); }));
                for (int k = 0; k < n; k++) grp[idx[k]] = k % 3;
            }
            case GAIT_WAVE -> { // one leg at a time: right side back to front, then left side back to front
                Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i;
                Arrays.sort(idx, Comparator.comparingDouble((Integer i) -> legChains.get(i).foot().subtract(com).dot(right) < 0 ? 1 : 0)
                        .thenComparingDouble(i -> legChains.get(i).foot().subtract(com).dot(f)));
                for (int k = 0; k < n; k++) grp[idx[k]] = k;
            }
            case GAIT_SPIDER -> { // alternating tripods/waves going round the body
                Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i;
                Arrays.sort(idx, Comparator.comparingDouble(i -> { Vec3 d = legChains.get(i).foot().subtract(com); return Math.atan2(d.z, d.x); }));
                for (int k = 0; k < n; k++) grp[idx[k]] = k % 2;
            }
            default -> { // biped: left/right. quadruped: diagonal pairs (front-left with back-right)
                for (int i = 0; i < n; i++) {
                    Vec3 d = legChains.get(i).foot().subtract(com);
                    boolean left = d.dot(right) < 0, front = d.dot(f) > 0;
                    grp[i] = gaitMode == GAIT_QUAD ? ((left == front) ? 0 : 1) : (left ? 0 : 1);
                }
            }
        }
        for (int i = 0; i < n; i++) { LegChain L = legChains.get(i);
            out.add(new LegChain(L.joints(), L.footSeg(), L.bodySeg(), L.hip(), L.foot(), L.length(), L.minLen(), L.hipTorque(), L.vCap(), L.mu(), grp[i], L.limits(), L.kneeTorque(), L.ankle(), L.soles())); }
        legChains.clear(); legChains.addAll(out);
    }

    /**
     * Walking from the feet: for one gait step (phase0 -> phase1), feet that move backwards stay planted and push the body forwards.
     * Returns {local dx, local dz, total push force N}. desired = local direction the pilot wants (forward or back).
     */
    public double[] legStep(double phase0, double phase1, Vec3 desired, double time) {
        double sx = 0, sz = 0, force = 0; int stance = 0;
        for (Joint j : joints) {
            if (!j.leg || !j.active()) continue;
            double d = angle(j, phase1, 1, 0, time) - angle(j, phase0, 1, 0, time);
            Vec3 a = axisVec(j.axis), r = j.foot.subtract(j.pivot);
            Vec3 footMove = a.cross(r).scale(d);           // how the foot moves relative to the body
            Vec3 bodyMove = new Vec3(-footMove.x, 0, -footMove.z);
            if (bodyMove.dot(desired) <= 1e-9) continue;   // this foot is swinging forward through the air
            sx += bodyMove.x; sz += bodyMove.z; stance++;
            force += j.motorTorque / Math.max(0.5, r.length());
        }
        if (stance == 0) return new double[]{0, 0, 0};
        return new double[]{sx / stance, sz / stance, force};
    }

    /** A gearbox reached by stepping in direction d: its output (FACING) should point back the way we came; placed backwards it inverts. */
    private static double gearRatio(GearboxBlock g, BlockState s, Direction d) {
        Direction f = s.getValue(net.minecraft.world.level.block.DirectionalBlock.FACING);
        return f == d ? 1 / g.ratio : g.ratio;
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
            case WALK_A, WALK_B -> 4 * j.amp * cps;
            case PROGRAM -> { int mx = 0; for (int i = 0; i < KEYS; i++) mx = Math.max(mx, Math.abs(j.keys[(i + 1) % KEYS] - j.keys[i])); yield mx * KEYS * cps * j.speedFactor; }
            case SWAY -> j.amp * 0.5 * 0.06 * 20;
            case SPIN -> 360;
            default -> 0;
        };
    }
    /** Fraction of the asked gait speed the gears allow (1 = full speed). */
    public double gaitScale() {
        double sc = 1;
        for (Joint j : joints) if (!j.ball && (j.mode == WALK_A || j.mode == WALK_B || j.mode == PROGRAM) && j.active()) { double need = neededSpeed(j); if (need > 0) sc = Math.min(sc, j.maxSpeed / need); }
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
    /** Power the leg motors use at full effort (CJ/t): their draw at 0.5 steps a second. */
    public double legMotorPower() {
        double p = 0; Set<Integer> seen = new HashSet<>();
        for (LegChain L : legChains) for (int j : L.joints()) if (seen.add(j)) p += joints.get(j).motorPower;
        return p;
    }
    /** Fastest step rate (cycles/s) the engines can feed, after gyros, Step Controllers and powered ball joints. */
    public double powerSpeedCap() {
        double legs = legMotorPower();
        if (legs <= 0) return 4;
        double base = gyroPower + (smartFooting ? SMART_FOOTING_POWER : 0);
        for (Joint j : joints) if (j.powered) base += j.motorPower;
        double spare = output - base;
        return Math.max(0.05, Math.min(4, 0.5 * spare / legs));
    }
    /** Step rate actually used: the Locomotion Controller's setting, but no faster than the power allows. */
    public double stepSpeed(boolean sprint) { return Math.min(gaitSpeed * (sprint ? 1.6 : 1), powerSpeedCap()); }
    public double maxDraw() { double d = drivePower + gyroPower + walkPower(); for (double v : thrustPow) d += v; return d; }

    /** Hinge angle (radians) for a joint given the animation state. */
    public double angle(Joint j, double walkPhase, double walkBlend, double spin, double time) {
        if (ikAngles != null && j.id < ikAngles.length && !Double.isNaN(ikAngles[j.id])) {
            double a1 = ikAngles[j.id], a0 = ikPrev != null && j.id < ikPrev.length && !Double.isNaN(ikPrev[j.id]) ? ikPrev[j.id] : a1;
            return a0 + (a1 - a0) * ikT;
        }
        if (!j.active()) return 0;
        double amp = Math.toRadians(j.amp);
        return switch (j.mode) {
            case WALK_A, WALK_B -> {
                // stance: foot sweeps from front (+amp) to back (-amp); swing: returns to the front
                double f = walkPhase / (2 * Math.PI) + j.autoPhase + j.phase / 360.0; f -= Math.floor(f);
                yield amp * (f < 0.5 ? 1 - 4 * f : -1 + 4 * (f - 0.5)) * walkBlend;
            }
            case SPIN -> j.blades > 0 ? spin : wheelAngle * j.wheelSign;
            case SWAY -> walkAmount > 0.01
                    ? amp * 0.6 * walkAmount * Math.sin(2 * Math.PI * walkClock + (j.pivot.subtract(com).dot(new Vec3(-forward.getNormal().getZ(), 0, forward.getNormal().getX())) < 0 ? 0 : Math.PI)) // arms swing with the steps, left and right opposite
                    : amp * 0.5 * Math.sin(time * 0.06 + j.id * 0.9);
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
            if (j.universal) { // powered hip/shoulder: sideways (about forward), then forward/back (about its hinge axis)
                Vec3 f = Vec3.atLowerCornerOf(forward.getNormal()), a = axisVec(j.axis);
                double roll = rollAngle(j, time), pitch = angle(j, walkPhase, walkBlend, spin, time), yawA = j.id < ikYaw.length ? ikYaw[j.id] : 0;
                out[s.id] = new Matrix4d(parent).translate(j.pivot.x, j.pivot.y, j.pivot.z).rotate(yawA, 0, 1, 0).rotate(roll, f.x, f.y, f.z).rotate(pitch, a.x, a.y, a.z).translate(-j.pivot.x, -j.pivot.y, -j.pivot.z);
                continue;
            }
            if (j.ball) { // undo the parent's turning (within the limit) so the part below stays level
                org.joml.Quaterniond q = parent.getNormalizedRotation(new org.joml.Quaterniond()).invert();
                if (j.powered && j.id < ballPitch.length && (ballPitch[j.id] != 0 || ballRoll[j.id] != 0)) { // a powered ankle tilts the foot to the ground
                    Vec3 f = Vec3.atLowerCornerOf(forward.getNormal()), r = new Vec3(-f.z, 0, f.x);
                    q.mul(new org.joml.Quaterniond().rotateAxis(ballPitch[j.id], r.x, r.y, r.z).rotateAxis(ballRoll[j.id], f.x, f.y, f.z));
                }
                double angQ = 2 * Math.acos(Math.min(1, Math.abs(q.w))), lim = j.limRad();
                if (angQ > lim) q = new org.joml.Quaterniond().slerp(q, lim / angQ);
                out[s.id] = new Matrix4d(parent).translate(j.pivot.x, j.pivot.y, j.pivot.z).rotate(q).translate(-j.pivot.x, -j.pivot.y, -j.pivot.z);
                continue;
            }
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
