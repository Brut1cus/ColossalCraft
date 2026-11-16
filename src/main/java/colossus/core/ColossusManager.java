package colossus.core;

import colossus.assembly.BodyPlanner;
import colossus.assembly.JointKind;
import colossus.brain.balance.GyroController;
import colossus.brain.walker.Limb;
import colossus.brain.walker.LimbDiscovery;
import colossus.brain.walker.WalkerBrain;
import colossus.brain.balance.GyroStabilizer;
import colossus.core.data.ColossusData;
import colossus.gyro.GyroStabilizerBlockEntity;
import colossus.net.PoseSync;
import colossus.physics.BackendFactory;
import colossus.physics.PhysicsWorld;
import colossus.physics.api.*;
import colossus.physics.math.Pose;
import colossus.physics.math.Vec3;
import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Game-thread side of the tick pipeline for one dimension:
 * <ol>
 *   <li>read the last physics snapshot, update Segment poses, track joint strain (creak → slip → break)</li>
 *   <li>refresh the terrain cache around bodies</li>
 *   <li>push commands (new bodies, joint motor targets) to the physics thread; set Gyro Stabilizer throttles from
 *   redstone on board (the gyros themselves run every physics step as a {@code PhysicsWorld.StepController})</li>
 *   <li>sync poses to clients at 20 Hz</li>
 * </ol>
 * The brain (gaits, IK, balance) plugs in between steps 1 and 3 in Phase 2.
 */
public final class ColossusManager {
    private static final Map<ResourceKey<Level>, ColossusManager> MANAGERS = new HashMap<>();

    private final ServerLevel level;
    private PhysicsWorld physics;
    private final LevelTerrain terrain = new LevelTerrain();
    private final Map<UUID, Live> live = new HashMap<>();

    /** Runtime handles of one Colossus. Index-aligned with its record's segments and joints. */
    static final class Live {
        final List<BodyHandle> bodies = new ArrayList<>();
        final List<ConstraintHandle> joints = new ArrayList<>();
        double[] strainSeconds;
        boolean ready;
        /** Physics time of the snapshot strain was last integrated on; -1 before the first. */
        double strainTime = -1;
        /** Gyro Stabilizers, if any: the controller runs on the physics thread; the units map each rotor to its blocks. */
        GyroController gyros;
        final List<GyroBinding> gyroUnits = new ArrayList<>();
        /** The walker brain, if the machine has legs (runs on the physics thread every step). */
        WalkerBrain walker;
    }

    /** One rotor and the ship-space blocks whose redstone power drives it. */
    record GyroBinding(int segment, GyroStabilizer.Rotor rotor, List<BlockPos> blocks) {}

    /** Read-only view of one Segment's gyros, for commands and tests. */
    public record GyroInfo(int segment, List<GyroStabilizer.Rotor> rotors, double torqueCapacity, double saturation) {}

    private ColossusManager(ServerLevel level) { this.level = level; }

    public static ColossusManager get(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level.dimension(), k -> new ColossusManager(level));
    }

    public PhysicsWorld physics() {
        if (physics == null) {
            PhysicsBackend backend = BackendFactory.create(ColossusConfig.PHYSICS_BACKEND.get(), ColossusConfig.XPBD_SUBSTEPS.get(), Colossus.LOG::info);
            backend.setGravity(new Vec3(0, -ColossusConfig.GRAVITY.get(), 0));
            backend.setTerrain(terrain);
            physics = new PhysicsWorld(backend, ColossusConfig.PHYSICS_HZ.get(), true, "colossus-physics-" + level.dimension().location().getPath());
            Colossus.LOG.info("Physics started for {} ({} backend, {} Hz)", level.dimension().location(), backend.id(), ColossusConfig.PHYSICS_HZ.get());
        }
        return physics;
    }

    // ------------------------------------------------------------------ events

    public static void onLevelTick(LevelTickEvent.Post e) {
        if (e.getLevel() instanceof ServerLevel sl) {
            ColossusManager m = MANAGERS.get(sl.dimension());
            if (m == null && ColossusSavedData.get(sl).all().isEmpty()) return;
            get(sl).tick();
        }
    }

    public static void onServerStopping(ServerStoppingEvent e) {
        for (ColossusManager m : MANAGERS.values()) {
            m.syncPosesToSaveData();
            if (m.physics != null) m.physics.close();
        }
        MANAGERS.clear();
    }

    // ------------------------------------------------------------------ lifecycle of one Colossus

    /** Create physics bodies and joints for a record (after assembly, or after a server restart). */
    public void spawn(ColossusRecord r) {
        Live l = new Live();
        live.put(r.id, l);
        // terrain first: the physics thread steps the new bodies at once, and the next game tick (which would refresh
        // the cache) can be late; a machine with no ground under it falls into the floor and is thrown out crooked
        cacheTerrainAround(r, level.getGameTime());
        List<BodyPlanner.SegmentInput> segs = segments(r);
        List<BodyDesc> bodies = new ArrayList<>();
        for (BodyPlanner.SegmentInput s : segs) bodies.add(BodyPlanner.body(s, r.id.hashCode() | 1));
        double g = ColossusConfig.GRAVITY.get();
        PhysicsWorld.Group group = physics().createGroup(bodies, handles -> {
            List<ConstraintDesc> out = new ArrayList<>();
            for (ColossusRecord.JointRecord j : r.joints) out.add(j.broken ? null : BodyPlanner.constraint(joint(j), segs, handles, g));
            return out;
        }).join();
        l.bodies.addAll(group.bodies());
        l.joints.addAll(group.constraints());
        l.strainSeconds = new double[r.joints.size()];
        if (ColossusConfig.JOINTS_HOLD_POSE.get()) {
            for (int i = 0; i < r.joints.size(); i++) {
                ConstraintHandle h = l.joints.get(i);
                BodyPlanner.JointInput ji = joint(r.joints.get(i));
                physics.enqueue(be -> BodyPlanner.hold(be, h, ji, ColossusData.SCALING));
            }
        }
        attachGyros(r, l);
        attachWalker(r, l);
        attachSeats(r);
        l.ready = true;
    }

    /**
     * One rideable seat entity per Pilot Seat block. A player already sitting on one before assembly keeps their seat:
     * that temporary seat is adopted instead of making a new one.
     */
    private void attachSeats(ColossusRecord r) {
        for (int si = 0; si < r.segments.size(); si++) {
            ColossusRecord.SegmentRecord s = r.segments.get(si);
            for (long c : s.cells) {
                if (!level.getBlockState(s.plotPos(c)).is(ColossusRegistries.PILOT_SEAT.get())) continue;
                BlockPos before = r.origin.offset(BlockPos.of(c));
                colossus.pilot.SeatEntity seat = level.getEntitiesOfClass(colossus.pilot.SeatEntity.class, new net.minecraft.world.phys.AABB(before))
                        .stream().filter(e -> e.colossus() == null).findFirst().orElse(null);
                if (seat == null) {
                    seat = colossus.pilot.SeatEntity.onBlock(level, before);
                    level.addFreshEntity(seat);
                }
                seat.link(r.id, si, c);
            }
        }
    }

    /**
     * Finds the machine's legs and, if it has at least two, starts a walker brain that drives their joints. Foot Pad
     * Segments count as feet even if they do not touch the ground at assembly; snapped joints are left out.
     */
    private void attachWalker(ColossusRecord r, Live l) {
        if (l.walker != null) { physics().removeController(l.walker); l.walker = null; }
        if (!ColossusConfig.WALKER_ENABLED.get()) return;
        List<BodyPlanner.SegmentInput> segs = segments(r);
        List<BodyPlanner.JointInput> joints = new ArrayList<>();
        for (ColossusRecord.JointRecord j : r.joints) joints.add(joint(j));
        int root = Math.max(0, r.segments.indexOf(r.root()));
        boolean[] footPad = new boolean[r.segments.size()];
        for (int si = 0; si < footPad.length; si++) {
            ColossusRecord.SegmentRecord s = r.segments.get(si);
            for (long c : s.cells) if (level.getBlockState(s.plotPos(c)).is(ColossusRegistries.FOOT_PAD.get())) { footPad[si] = true; break; }
        }
        List<Limb> limbs = LimbDiscovery.discover(segs, joints, root, si -> footPad[si], ji -> r.joints.get(ji).broken || l.joints.get(ji) == null);
        if (limbs.size() < 2) return;
        List<WalkerBrain.Leg> legs = new ArrayList<>();
        for (Limb limb : limbs) {
            WalkerBrain.Drive[] drives = new WalkerBrain.Drive[limb.joints().length];
            for (int k = 0; k < drives.length; k++) drives[k] = drive(limb.joints()[k], joints, segs, l);
            WalkerBrain.Drive ankle = limb.hasAnkle() ? drive(limb.ankle(), joints, segs, l) : null;
            legs.add(new WalkerBrain.Leg(limb, drives, ankle, l.bodies.get(limb.footSegment()), segs.get(limb.footSegment()).comRel()));
        }
        double mass = 0;
        for (BodyPlanner.SegmentInput seg : segs) mass += seg.mass();
        l.walker = new WalkerBrain(l.bodies.get(root), segs.get(root).comRel(), mass, legs, terrain, ColossusConfig.walkerParams());
        physics().addController(l.walker);
    }

    private WalkerBrain.Drive drive(int ji, List<BodyPlanner.JointInput> joints, List<BodyPlanner.SegmentInput> segs, Live l) {
        BodyPlanner.JointInput j = joints.get(ji);
        return new WalkerBrain.Drive(l.joints.get(ji), j.kind().type, BodyPlanner.holdLimit(j, ColossusData.SCALING),
                l.bodies.get(j.parent()), l.bodies.get(j.child()), j.anchorRel().sub(segs.get(j.parent()).comRel()), j.axisVector());
    }

    /**
     * Walking command for a legged Colossus, in its own frame: forward and left speed (m/s), turn rate (rad/s, positive
     * turns left). Returns false if it has no walker brain.
     */
    public boolean command(UUID id, double forward, double strafe, double yawRate) {
        Live l = live.get(id);
        if (l == null || l.walker == null) return false;
        l.walker.command(forward, strafe, yawRate);
        return true;
    }

    /** The walker brain of a Colossus, or null (read-only use: status, tests). */
    public WalkerBrain walker(UUID id) {
        Live l = live.get(id);
        return l == null ? null : l.walker;
    }

    /** Number of legs the walker brain drives (0 if none). */
    public int legCount(UUID id) {
        Live l = live.get(id);
        return l == null || l.walker == null ? 0 : l.walker.legs().size();
    }

    /** Finds Gyro Stabilizer blocks in each Segment's plot, merges N×N squares into rotors and starts their controller. */
    private void attachGyros(ColossusRecord r, Live l) {
        GyroStabilizer.Params params = ColossusConfig.gyroParams();
        Map<BodyHandle, GyroStabilizer> stabilizers = new HashMap<>();
        for (int si = 0; si < r.segments.size(); si++) {
            ColossusRecord.SegmentRecord s = r.segments.get(si);
            List<int[]> cells = new ArrayList<>();
            List<BlockPos> plot = new ArrayList<>();
            for (long c : s.cells) {
                BlockPos at = s.plotPos(c);
                if (!level.getBlockState(at).is(ColossusRegistries.GYRO_STABILIZER.get())) continue;
                BlockPos rel = BlockPos.of(c);
                cells.add(new int[]{rel.getX(), rel.getY(), rel.getZ()});
                plot.add(at);
            }
            if (cells.isEmpty()) continue;
            List<GyroStabilizer.Rotor> rotors = new ArrayList<>();
            for (GyroStabilizer.Unit u : GyroStabilizer.units(cells)) {
                GyroStabilizer.Rotor rotor = new GyroStabilizer.Rotor(u.footprint(), params);
                List<BlockPos> blocks = new ArrayList<>();
                double spin = 0;
                for (int i : u.cells()) {
                    blocks.add(plot.get(i));
                    if (level.getBlockEntity(plot.get(i)) instanceof GyroStabilizerBlockEntity be) spin += be.spin();
                }
                rotor.setSpinFraction(spin / u.cells().length); // spun up before assembly, or saved before a restart
                rotors.add(rotor);
                l.gyroUnits.add(new GyroBinding(si, rotor, blocks));
            }
            stabilizers.put(l.bodies.get(si), new GyroStabilizer(rotors, s.inertia, machineInertia(r, si), params));
        }
        if (stabilizers.isEmpty()) return;
        l.gyros = new GyroController(stabilizers);
        physics().addController(l.gyros);
        updateGyroThrottles(l);
    }

    /** Whole-machine inertia about one Segment's centre of mass, in the core frame (the assembled orientation). */
    static colossus.physics.math.Mat3 machineInertia(ColossusRecord r, int segment) {
        List<colossus.physics.math.Mat3> in = new ArrayList<>();
        List<Double> m = new ArrayList<>();
        List<Vec3> c = new ArrayList<>();
        for (ColossusRecord.SegmentRecord s : r.segments) { in.add(s.inertia); m.add(s.mass); c.add(s.comRel); }
        return GyroStabilizer.combinedInertia(in, m, c, r.segments.get(segment).comRel);
    }

    /** Redstone power on any block of a rotor sets its throttle (signal / 15); the rotor speed is written back to the blocks. */
    private void updateGyroThrottles(Live l) {
        for (GyroBinding b : l.gyroUnits) {
            int signal = 0;
            for (BlockPos p : b.blocks()) signal = Math.max(signal, level.getBestNeighborSignal(p));
            b.rotor().setThrottle(signal / 15.0);
        }
        storeGyroSpin(l);
    }

    private void storeGyroSpin(Live l) {
        for (GyroBinding b : l.gyroUnits) {
            for (BlockPos p : b.blocks()) if (level.getBlockEntity(p) instanceof GyroStabilizerBlockEntity be) be.setSpin(b.rotor().spinFraction());
        }
    }

    /** Gyro telemetry per Segment that has any (empty if the Colossus has none or is not running). */
    public List<GyroInfo> gyros(UUID id) {
        Live l = live.get(id);
        if (l == null || l.gyros == null) return List.of();
        List<GyroInfo> out = new ArrayList<>();
        for (int si = 0; si < l.bodies.size(); si++) {
            GyroStabilizer g = l.gyros.gyros().get(l.bodies.get(si));
            if (g != null) out.add(new GyroInfo(si, g.rotors(), g.torqueCapacity(), g.saturation()));
        }
        return out;
    }

    static List<BodyPlanner.SegmentInput> segments(ColossusRecord r) {
        List<BodyPlanner.SegmentInput> out = new ArrayList<>();
        for (ColossusRecord.SegmentRecord s : r.segments) {
            List<int[]> cells = new ArrayList<>(s.cells.length);
            for (long c : s.cells) { BlockPos p = BlockPos.of(c); cells.add(new int[]{p.getX(), p.getY(), p.getZ()}); }
            out.add(new BodyPlanner.SegmentInput(s.comRel, s.mass, s.inertia, cells, s.pose));
        }
        return out;
    }

    static BodyPlanner.JointInput joint(ColossusRecord.JointRecord j) {
        return new BodyPlanner.JointInput(JointKind.byId(j.kind), j.parent, j.child, j.anchorRel, j.axis, j.footprint);
    }

    public void despawn(UUID id) {
        Live l = live.remove(id);
        if (l == null || physics == null) return;
        if (l.walker != null) physics.removeController(l.walker);
        if (l.gyros != null) {
            physics.removeController(l.gyros);
            storeGyroSpin(l); // disassembly copies the block entities next, so the rotors keep their speed
        }
        for (ConstraintHandle h : l.joints) if (h != null) physics.removeConstraint(h);
        for (BodyHandle h : l.bodies) physics.removeBody(h);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        ColossusSavedData data = ColossusSavedData.get(level);
        // rebuild runtime state after a restart
        for (ColossusRecord r : data.all()) if (!live.containsKey(r.id)) spawn(r);
        if (live.isEmpty()) return;

        PhysicsWorld.Snapshot snap = physics().snapshot();
        long now = level.getGameTime();
        for (ColossusRecord r : data.all()) {
            Live l = live.get(r.id);
            if (l == null || !l.ready) continue;
            // 1. poses
            for (int i = 0; i < r.segments.size(); i++) {
                BodyState bs = snap.body(l.bodies.get(i));
                if (bs != BodyState.MISSING) r.segments.get(i).pose = bs.pose();
            }
            // 2. terrain around the machine
            cacheTerrainAround(r, now);
            // 3. gyro power from redstone on board
            if (l.gyros != null) updateGyroThrottles(l);
            // 4. strain: creak -> slip -> break
            // integrate over physics time actually simulated: ticks that run back-to-back (catch-up after lag, test
            // servers) see the same snapshot several times and must not count it again
            double dt = l.strainTime < 0 ? 0 : snap.time() - l.strainTime;
            l.strainTime = snap.time();
            if (ColossusConfig.JOINT_BREAKING.get() && dt > 0) trackStrain(r, l, snap, Math.min(dt, 0.25));
        }
        if (now % 200 == 0) {
            terrain.evictOlderThan(now, 1200);
            data.setDirty();
        }
        // 5. pose sync to clients (drawn by colossus.client.render.SegmentRenderer)
        for (ColossusRecord r : data.all()) {
            if (!live.containsKey(r.id)) continue;
            colossus.net.Send.toLevel(level, PoseSync.of(r));
        }
    }

    /** Refresh the physics terrain cache around every Segment of a Colossus (sections older than 2 s). */
    private void cacheTerrainAround(ColossusRecord r, long now) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (ColossusRecord.SegmentRecord s : r.segments) {
            Vec3 p = s.pose.position();
            double rad = Math.sqrt(s.cells.length) + 4;
            minX = Math.min(minX, p.x() - rad); minY = Math.min(minY, p.y() - rad); minZ = Math.min(minZ, p.z() - rad);
            maxX = Math.max(maxX, p.x() + rad); maxY = Math.max(maxY, p.y() + rad); maxZ = Math.max(maxZ, p.z() + rad);
        }
        if (minX < maxX) terrain.refresh(level, minX, minY - 4, minZ, maxX, maxY, maxZ, now, 40);
    }

    private void trackStrain(ColossusRecord r, Live l, PhysicsWorld.Snapshot snap, double dt) {
        for (int i = 0; i < r.joints.size(); i++) {
            ColossusRecord.JointRecord j = r.joints.get(i);
            ConstraintHandle h = l.joints.get(i);
            if (j.broken || h == null) continue;
            ConstraintState cs = snap.constraint(h);
            if (cs == ConstraintState.MISSING) continue;
            double ratio = BodyPlanner.strain(joint(j), cs, ColossusData.SCALING);
            if (ratio > 1) l.strainSeconds[i] += dt;
            else l.strainSeconds[i] = Math.max(0, l.strainSeconds[i] - dt * 0.5);
            if (l.strainSeconds[i] > ColossusConfig.STRAIN_BREAK_SECONDS.get()) {
                j.broken = true;
                physics.removeConstraint(h);
                l.joints.set(i, null);
                Colossus.LOG.info("Colossus {} joint {} ({}) snapped at {}% load", r.id, i, j.kind, Math.round(ratio * 100));
                if (l.walker != null) attachWalker(r, l); // re-discover the legs that are left
            }
        }
    }

    /** Joint load ratio for the inspector/commands (0 = idle, 1 = at rating). */
    public double strain(UUID id, int joint) {
        Live l = live.get(id);
        return l == null || l.strainSeconds == null ? 0 : l.strainSeconds[joint] / ColossusConfig.STRAIN_BREAK_SECONDS.get();
    }

    /** Blocks until the physics thread has applied queued commands (assembly/disassembly only). */
    public void flush() {
        if (physics != null) {
            try { physics.submit(b -> 0).get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
    }

    private void syncPosesToSaveData() {
        if (physics == null) return;
        PhysicsWorld.Snapshot snap = physics.snapshot();
        ColossusSavedData data = ColossusSavedData.get(level);
        for (ColossusRecord r : data.all()) {
            Live l = live.get(r.id);
            if (l == null) continue;
            for (int i = 0; i < r.segments.size() && i < l.bodies.size(); i++) {
                BodyState bs = snap.body(l.bodies.get(i));
                if (bs != BodyState.MISSING) r.segments.get(i).pose = bs.pose();
            }
        }
        data.setDirty();
    }

    /** Seconds of physics simulated in this dimension so far (the latest snapshot's time). */
    public double physicsTime() { return physics == null ? 0 : physics.snapshot().time(); }

    public double lastStepMillis() { return physics == null ? 0 : physics.lastStepMillis(); }

    public String backendId() { return physics == null ? "(not started)" : physics.backendForTests().id(); }

    /** Snapshot pose of a segment (falls back to the saved pose). */
    public Pose pose(ColossusRecord r, int segment) {
        Live l = live.get(r.id);
        if (l == null || physics == null || segment >= l.bodies.size()) return r.segments.get(segment).pose;
        BodyState bs = physics.snapshot().body(l.bodies.get(segment));
        return bs == BodyState.MISSING ? r.segments.get(segment).pose : bs.pose();
    }
}
