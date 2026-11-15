package com.colosalcraft.colossus;

import com.colosalcraft.ColosalCraft;
import com.colosalcraft.Config;
import com.colosalcraft.ModRegistry;
import com.colosalcraft.block.HullCoreBlockEntity;
import com.colosalcraft.block.PowerBlock;
import com.colosalcraft.network.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.*;

/**
 * A built Colossus. Position = world position of its centre of mass. Rotation = yaw (radians, clockwise from above).
 * Local frame = the world frame at assembly time, Hull Core at local 0,0,0.
 */
public class ColossusEntity extends Entity implements IEntityWithComplexSpawn {
    public static final ResourceKey<DamageType> CRUSHED = ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(ColosalCraft.MODID, "crushed"));
    private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> WALK = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BLEND = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPIN = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ENERGY = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> HOVER = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> INFINITE = SynchedEntityData.defineId(ColossusEntity.class, EntityDataSerializers.BOOLEAN);

    public StructureModel model;
    public Map<BlockPos, BlockState> blocks = new HashMap<>();
    public Map<BlockPos, CompoundTag> bes = new HashMap<>();
    public CompoundTag jointCfg = new CompoundTag();

    public double yaw, prevYaw, yawRate;
    public double walkPhase, prevWalkPhase, walkBlend, prevWalkBlend, spin, prevSpin, spinRate;
    public Vec3 vel = Vec3.ZERO;
    /** Client only: live block entities used for rendering. */
    public Map<BlockPos, BlockEntity> clientBes;
    public long energy, energyStart;
    public boolean onGroundC;
    private Matrix4d[] art = new Matrix4d[0];
    private int[] overload = new int[0];
    private Vec3 clientLastPos;
    public double clientSpeed;
    // pilot input
    public float inFwd, inTurn; public boolean inUp, inDown; private int inputAge = 100;

    public ColossusEntity(EntityType<?> type, Level level) { super(type, level); noPhysics = true; }

    // ------------------------------------------------------------ setup
    public void setup(StructureScan.Result r, BlockPos heartWorld) {
        blocks = new HashMap<>(r.blocks); bes = new HashMap<>(r.bes); jointCfg = r.jointCfg.copy();
        entityData.set(HOVER, r.hover);
        rebuild();
        energy = energyStart = model.energy;
        Vec3 p = Vec3.atLowerCornerOf(heartWorld).add(model.com);
        setPos(p.x, p.y, p.z); xo = p.x; yo = p.y; zo = p.z;
    }

    private void rebuild() {
        model = new StructureModel(blocks, bes, jointCfg);
        overload = new int[model.joints.size()];
        art = model.transforms(walkPhase, walkBlend, spin, tickCount);
        entityData.set(INFINITE, model.infinite);
        setBoundingBox(makeBoundingBox());
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(YAW, 0f).define(WALK, 0f).define(BLEND, 0f).define(SPIN, 0f).define(ENERGY, 0f).define(HOVER, true).define(INFINITE, false);
    }
    public boolean hover() { return entityData.get(HOVER); }
    public float syncedEnergy() { return entityData.get(ENERGY); }
    public boolean syncedInfinite() { return entityData.get(INFINITE); }

    // ------------------------------------------------------------ transforms
    public Vec3 toWorld(Vec3 local, Vec3 pos, double yw) {
        Vec3 c = model.com; double dx = local.x - c.x, dy = local.y - c.y, dz = local.z - c.z;
        double cs = Math.cos(yw), sn = Math.sin(yw);
        return new Vec3(pos.x + dx * cs - dz * sn, pos.y + dy, pos.z + dx * sn + dz * cs);
    }
    public Vec3 toLocal(Vec3 world, Vec3 pos, double yw) {
        double dx = world.x - pos.x, dy = world.y - pos.y, dz = world.z - pos.z;
        double cs = Math.cos(yw), sn = Math.sin(yw);
        return new Vec3(model.com.x + dx * cs + dz * sn, model.com.y + dy, model.com.z - dx * sn + dz * cs);
    }
    public Matrix4d segWorld(int seg) {
        Matrix4d m = new Matrix4d().translate(getX(), getY(), getZ()).rotateY(-yaw).translate(-model.com.x, -model.com.y, -model.com.z);
        if (seg < art.length && art[seg] != null) m.mul(art[seg]);
        return m;
    }
    public Vec3 blockWorldCenter(BlockPos p) {
        Integer s = model.segOf.get(p);
        Vector3d v = segWorld(s == null ? 0 : s).transformPosition(new Vector3d(p.getX() + .5, p.getY() + .5, p.getZ() + .5));
        return new Vec3(v.x, v.y, v.z);
    }

    @Override protected AABB makeBoundingBox() {
        if (model == null || blocks == null || blocks.isEmpty()) return super.makeBoundingBox();
        double r = model.radius + 2; double h0 = model.localBounds.minY - model.com.y, h1 = model.localBounds.maxY - model.com.y;
        double reach = 0; for (StructureModel.Joint j : model.joints) if (j.mode != StructureModel.LOCKED) reach = Math.max(reach, 3);
        return new AABB(getX() - r, getY() + h0 - 1 - reach, getZ() - r, getX() + r, getY() + h1 + 1 + reach, getZ() + r);
    }

    /** World AABBs of this Colossus' solid blocks near a query box (articulation aware). */
    public List<AABB> boxesNear(AABB q) {
        List<AABB> out = new ArrayList<>();
        if (model == null) return out;
        for (StructureModel.Seg s : model.segs) {
            Matrix4d w = segWorld(s.id); Matrix4d inv = new Matrix4d(w).invert();
            double x0 = 1e9, y0 = 1e9, z0 = 1e9, x1 = -1e9, y1 = -1e9, z1 = -1e9;
            for (int i = 0; i < 8; i++) {
                Vector3d c = inv.transformPosition(new Vector3d((i & 1) == 0 ? q.minX : q.maxX, (i & 2) == 0 ? q.minY : q.maxY, (i & 4) == 0 ? q.minZ : q.maxZ));
                x0 = Math.min(x0, c.x); y0 = Math.min(y0, c.y); z0 = Math.min(z0, c.z); x1 = Math.max(x1, c.x); y1 = Math.max(y1, c.y); z1 = Math.max(z1, c.z);
            }
            int ix0 = (int) Math.floor(x0) - 1, iy0 = (int) Math.floor(y0) - 1, iz0 = (int) Math.floor(z0) - 1;
            int ix1 = (int) Math.floor(x1) + 1, iy1 = (int) Math.floor(y1) + 1, iz1 = (int) Math.floor(z1) + 1;
            if ((long) (ix1 - ix0) * (iy1 - iy0) * (iz1 - iz0) > 40000) continue;
            boolean aligned = Math.abs(w.m00()) > 0.97 || Math.abs(w.m02()) > 0.97;
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int x = ix0; x <= ix1; x++) for (int y = iy0; y <= iy1; y++) for (int z = iz0; z <= iz1; z++) {
                mp.set(x, y, z);
                if (!model.solid.contains(mp)) continue;
                Integer so = model.segOf.get(mp); if (so == null || so != s.id) continue;
                AABB sb = model.shapeBounds.get(mp);
                Vector3d c = w.transformPosition(new Vector3d(x + (sb.minX + sb.maxX) / 2, y + (sb.minY + sb.maxY) / 2, z + (sb.minZ + sb.maxZ) / 2));
                double hx = sb.getXsize() / 2, hy = sb.getYsize() / 2, hz = sb.getZsize() / 2;
                double ex = Math.abs(w.m00()) * hx + Math.abs(w.m10()) * hy + Math.abs(w.m20()) * hz;
                double ey = Math.abs(w.m01()) * hx + Math.abs(w.m11()) * hy + Math.abs(w.m21()) * hz;
                double ez = Math.abs(w.m02()) * hx + Math.abs(w.m12()) * hy + Math.abs(w.m22()) * hz;
                if (!aligned) { ex *= 0.75; ez *= 0.75; }
                AABB b = new AABB(c.x - ex, c.y - ey, c.z - ez, c.x + ex, c.y + ey, c.z + ez);
                if (b.intersects(q)) out.add(b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ tick
    @Override public void tick() {
        super.tick();
        if (model == null || blocks.isEmpty()) { if (!level().isClientSide) discard(); return; }
        prevYaw = yaw; prevWalkPhase = walkPhase; prevWalkBlend = walkBlend; prevSpin = spin;
        if (level().isClientSide) {
            yaw = entityData.get(YAW); walkPhase = entityData.get(WALK); walkBlend = entityData.get(BLEND); spin = entityData.get(SPIN);
            if (tickCount < 3) { prevYaw = yaw; prevWalkPhase = walkPhase; prevSpin = spin; }
            art = model.transforms(walkPhase, walkBlend, spin, tickCount);
            Vec3 last = clientLastPos == null ? position() : clientLastPos; clientLastPos = position();
            clientSpeed = clientSpeed * 0.7 + position().distanceTo(last) * 20 * 0.3;
            com.colosalcraft.client.ClientHooks.carryLocalPlayer(this, last, prevYaw);
            return;
        }
        Vec3 prevPos = position();
        Vec3 v0 = vel;
        physics();
        Vec3 accel = vel.subtract(v0).scale(20);
        art = model.transforms(walkPhase, walkBlend, spin, tickCount);
        setBoundingBox(makeBoundingBox());
        carry(prevPos, prevYaw);
        crush();
        checkJoints(accel);
        entityData.set(YAW, (float) yaw); entityData.set(WALK, (float) walkPhase); entityData.set(BLEND, (float) walkBlend);
        entityData.set(SPIN, (float) spin); entityData.set(ENERGY, (float) energy);
    }

    private Player pilot() { return getFirstPassenger() instanceof Player p ? p : null; }

    public void setInput(float f, float t, boolean up, boolean down) { inFwd = f; inTurn = t; inUp = up; inDown = down; inputAge = 0; }

    private void physics() {
        double dt = 0.05, M = model.mass, g = StructureModel.G, scale = Config.MASS_SCALE.get();
        Player pilot = pilot();
        if (pilot == null || ++inputAge > 10) { inFwd = 0; inTurn = 0; inUp = false; inDown = false; }

        // buoyancy
        double vw = 0, vl = 0; int n = 0, step = Math.max(1, blocks.size() / 1500);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (BlockPos p : blocks.keySet()) {
            if (n++ % step != 0) continue;
            Vec3 c = toWorld(new Vec3(p.getX() + .5, p.getY() + .5, p.getZ() + .5), position(), yaw);
            mp.set(c.x, c.y, c.z);
            FluidState fs = level().getFluidState(mp);
            if (fs.isEmpty()) continue;
            double vol = Density.volume(blocks.get(p)) * step;
            if (fs.is(FluidTags.WATER)) vw += vol; else if (fs.is(FluidTags.LAVA)) vl += vol;
        }
        double buoy = (1000 * vw + 3100 * vl) * scale * g;
        double subFrac = Math.min(1, (vw + vl) / Math.max(1, blocks.size()));

        double fy = -M * g + buoy;
        double up = model.thrustCap[Direction.UP.ordinal()], down = model.thrustCap[Direction.DOWN.ordinal()];
        double vT = 0, dT = 0;
        if (pilot != null && inUp) vT = 1;
        else if (pilot != null && inDown) dT = 1;
        else if (hover() && up > 0) vT = Math.max(0, Math.min(1, (M * g - buoy - M * vel.y * 2) / up));
        double draw = vT * model.thrustPow[Direction.UP.ordinal()] + dT * model.thrustPow[Direction.DOWN.ordinal()];

        Direction fwd = model.forward; Direction dir = inFwd >= 0 ? fwd : fwd.getOpposite();
        double hT = Math.abs(inFwd), hCap = model.thrustCap[dir.ordinal()];
        draw += hT * model.thrustPow[dir.ordinal()];
        boolean walkers = model.hasWalkers();
        double stability = model.gyroCap <= 0 ? 0.25 : Math.min(1, model.gyroCap / M);
        double ground = onGroundC ? (walkers ? model.walkDrive() * Math.max(0.25, stability) : 0) + model.driveForce : 0;
        if (subFrac > 0.05 && !onGroundC) ground += model.driveForce * 0.5; // motors as propellers in water
        draw += hT * (model.drivePower + (walkers ? model.walkPower() : 0));

        double lat = 0, latPow = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (d.getAxis() != fwd.getAxis()) { lat += model.thrustCap[d.ordinal()]; latPow += model.thrustPow[d.ordinal()]; }
        double r = Math.max(1, model.radius);
        double turnTorque = model.gyroCap * 0.6 + lat * r * 0.5 + (onGroundC ? (model.driveForce + model.walkDrive()) * r * 0.3 : 0);
        draw += Math.abs(inTurn) * (model.gyroPower + latPow * 0.5);
        if (model.joints.stream().anyMatch(j -> j.mode == StructureModel.SPIN && j.active())) draw += model.walkPower() * 0.2;

        double avail = model.infinite ? model.output : (energy > 0 ? model.output : 0);
        double ratio = draw <= 0 ? 1 : Math.min(1, avail / draw);
        if (!model.infinite && draw > 0) { ratio = Math.min(ratio, energy / draw); energy = Math.max(0, energy - (long) Math.ceil(draw * ratio)); }

        Vec3 fw = Vec3.atLowerCornerOf(fwd.getNormal());
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        Vec3 fwW = new Vec3(fw.x * cs - fw.z * sn, 0, fw.x * sn + fw.z * cs);
        double fh = Math.signum(inFwd) * (hT * hCap + hT * ground) * ratio;
        double vh = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double lift = model.sails * 0.5 * 1.225 * vh * vh * Config.LIFT_SCALE.get();
        fy += (vT * up - dT * down) * ratio + lift;
        Vec3 F = new Vec3(fwW.x * fh, fy, fwW.z * fh);
        vel = vel.add(F.scale(dt / M));

        double I = M * (r * r + 1) / 3;
        yawRate += -inTurn * turnTorque * ratio / I * dt;
        yawRate = Math.max(-1.5, Math.min(1.5, yawRate)) * (inTurn == 0 ? 0.8 : 0.95);

        double hd = onGroundC ? 0.85 : subFrac > 0.05 ? 0.93 : 0.985;
        vel = new Vec3(vel.x * hd, vel.y * (subFrac > 0.05 ? 0.9 : 0.995), vel.z * hd);
        double max = Config.MAX_SPEED.get();
        if (vel.length() > max) vel = vel.normalize().scale(max);

        // animation state
        if (onGroundC && walkers && hT > 0 && ratio > 0) { walkPhase += (0.12 + vh * 0.03) * Math.signum(inFwd); walkBlend = Math.min(1, walkBlend + 0.1); }
        else walkBlend = Math.max(0, walkBlend - 0.08);
        double targetSpin = pilot != null && ratio > 0 && energy + (model.infinite ? 1 : 0) > 0 ? 0.15 + 0.6 * Math.max(hT, vT) : 0;
        spinRate += (targetSpin - spinRate) * 0.05; spin += spinRate;

        moveWithCollision(vel.scale(dt));
        rotateWithCollision(yaw + yawRate * dt);
    }

    // ------------------------------------------------------------ world collision
    private Map<Long, List<AABB>> gatherWorld(List<Vec3> centers, double reach) {
        Map<Long, List<AABB>> map = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        int rr = (int) Math.ceil(reach) + 1;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        Level lv = level();
        for (Vec3 c : centers) {
            int cx = (int) Math.floor(c.x), cy = (int) Math.floor(c.y), cz = (int) Math.floor(c.z);
            for (int x = cx - rr; x <= cx + rr; x++) for (int y = cy - rr; y <= cy + rr; y++) for (int z = cz - rr; z <= cz + rr; z++) {
                long key = BlockPos.asLong(x, y, z);
                if (!seen.add(key)) continue;
                mp.set(x, y, z);
                List<AABB> list;
                if (!lv.isLoaded(mp)) list = List.of(new AABB(x, y, z, x + 1, y + 1, z + 1));
                else {
                    BlockState s = lv.getBlockState(mp);
                    if (s.isAir()) continue;
                    VoxelShape sh = s.getCollisionShape(lv, mp);
                    if (sh.isEmpty()) continue;
                    list = new ArrayList<>(); for (AABB a : sh.toAabbs()) list.add(a.move(x, y, z));
                }
                map.put(key, list);
            }
        }
        // heavier Colossi count as solid ground for lighter ones (nesting)
        for (ColossusEntity o : ColossusCollision.LOADED) {
            if (o == this || o.level() != level() || o.model == null || o.isRemoved() || o.model.mass <= model.mass) continue;
            if (!o.getBoundingBox().intersects(getBoundingBox().inflate(reach + 1))) continue;
            for (AABB b : o.boxesNear(getBoundingBox().inflate(reach + 1))) {
                map.computeIfAbsent(BlockPos.asLong((int) Math.floor((b.minX + b.maxX) / 2), (int) Math.floor((b.minY + b.maxY) / 2), (int) Math.floor((b.minZ + b.maxZ) / 2)), k -> new ArrayList<>()).add(b);
            }
        }
        return map;
    }

    private List<AABB> surfaceBoxes(Vec3 pos, double yw) {
        List<AABB> out = new ArrayList<>(model.surface.size());
        double e = 0.499;
        for (BlockPos p : model.surface) {
            Vec3 c = toWorld(new Vec3(p.getX() + .5, p.getY() + .5, p.getZ() + .5), pos, yw);
            out.add(new AABB(c.x - e, c.y - e, c.z - e, c.x + e, c.y + e, c.z + e));
        }
        return out;
    }

    private static double clip(int axis, AABB b, AABB w, double d) {
        if (axis != 0 && (w.maxX <= b.minX || w.minX >= b.maxX)) return d;
        if (axis != 1 && (w.maxY <= b.minY || w.minY >= b.maxY)) return d;
        if (axis != 2 && (w.maxZ <= b.minZ || w.minZ >= b.maxZ)) return d;
        double bmin = axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ, bmax = axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ;
        double wmin = axis == 0 ? w.minX : axis == 1 ? w.minY : w.minZ, wmax = axis == 0 ? w.maxX : axis == 1 ? w.maxY : w.maxZ;
        if (d > 0 && wmin >= bmax - 1e-7) d = Math.min(d, wmin - bmax);
        else if (d < 0 && wmax <= bmin + 1e-7) d = Math.max(d, wmax - bmin);
        return d;
    }

    private double sweep(int axis, List<AABB> boxes, Map<Long, List<AABB>> world, double d) {
        if (d == 0) return 0;
        for (AABB b : boxes) {
            int cx = (int) Math.floor((b.minX + b.maxX) / 2), cy = (int) Math.floor((b.minY + b.maxY) / 2), cz = (int) Math.floor((b.minZ + b.maxZ) / 2);
            int ext = (int) Math.ceil(Math.abs(d)) + 1;
            int ax0 = axis == 0 ? (d > 0 ? 0 : -ext) : -1, ax1 = axis == 0 ? (d > 0 ? ext : 0) : 1;
            int ay0 = axis == 1 ? (d > 0 ? 0 : -ext) : -1, ay1 = axis == 1 ? (d > 0 ? ext : 0) : 1;
            int az0 = axis == 2 ? (d > 0 ? 0 : -ext) : -1, az1 = axis == 2 ? (d > 0 ? ext : 0) : 1;
            for (int x = ax0; x <= ax1; x++) for (int y = ay0 - (axis == 1 ? 0 : 1); y <= ay1; y++) for (int z = az0; z <= az1; z++) {
                List<AABB> l = world.get(BlockPos.asLong(cx + x, cy + y, cz + z));
                if (l != null) for (AABB w : l) d = clip(axis, b, w, d);
            }
            if (d == 0) return 0;
        }
        return d;
    }

    private static List<AABB> shift(List<AABB> boxes, double x, double y, double z) {
        List<AABB> o = new ArrayList<>(boxes.size()); for (AABB b : boxes) o.add(b.move(x, y, z)); return o;
    }

    private void moveWithCollision(Vec3 d) {
        List<AABB> boxes = surfaceBoxes(position(), yaw);
        List<Vec3> centers = new ArrayList<>(boxes.size());
        for (AABB b : boxes) centers.add(b.getCenter());
        Map<Long, List<AABB>> world = gatherWorld(centers, d.length() + 1.2);
        int steps = Math.max(1, (int) Math.ceil(d.length() / 0.45));
        Vec3 sd = d.scale(1.0 / steps);
        double tx = 0, ty = 0, tz = 0; boolean hitY = false, hitDown = false, hitX = false, hitZ = false;
        for (int i = 0; i < steps; i++) {
            List<AABB> cur = shift(boxes, tx, ty, tz);
            double my = sweep(1, cur, world, sd.y); if (my != sd.y) { hitY = true; if (sd.y < 0) hitDown = true; } ty += my; cur = shift(cur, 0, my, 0);
            double mx = sweep(0, cur, world, sd.x);
            double mz;
            if (mx != sd.x || true) {
                List<AABB> curX = shift(cur, mx, 0, 0);
                mz = sweep(2, curX, world, sd.z);
                boolean blocked = (Math.abs(mx - sd.x) > 1e-6) || (Math.abs(mz - sd.z) > 1e-6);
                if (blocked && (onGroundC || hitDown)) { // try stepping up one block (walkers, cars)
                    double upd = sweep(1, cur, world, 1.05);
                    if (upd > 0.5) {
                        List<AABB> up = shift(cur, 0, upd, 0);
                        double mx2 = sweep(0, up, world, sd.x); double mz2 = sweep(2, shift(up, mx2, 0, 0), world, sd.z);
                        if (Math.abs(mx2) + Math.abs(mz2) > Math.abs(mx) + Math.abs(mz) + 1e-4) {
                            List<AABB> moved = shift(up, mx2, 0, mz2);
                            double dn = sweep(1, moved, world, -upd);
                            ty += upd + dn; mx = mx2; mz = mz2;
                        }
                    }
                }
                if (Math.abs(mx - sd.x) > 1e-6) hitX = true;
                if (Math.abs(mz - sd.z) > 1e-6) hitZ = true;
            }
            tx += mx; tz += mz;
        }
        double impactVy = vel.y;
        if (hitY) vel = new Vec3(vel.x, 0, vel.z);
        if (hitX) vel = new Vec3(0, vel.y, vel.z);
        if (hitZ) vel = new Vec3(vel.x, vel.y, 0);
        if (!hitDown) {
            // still on ground if something is right below
            double probe = sweep(1, shift(boxes, tx, ty, tz), world, -0.05);
            onGroundC = probe > -0.05;
        } else onGroundC = true;
        setPos(getX() + tx, getY() + ty, getZ() + tz);
        if (hitDown && impactVy < -12) impact(-impactVy);
    }

    private void rotateWithCollision(double newYaw) {
        if (Math.abs(newYaw - yaw) < 1e-6) return;
        List<AABB> boxes = surfaceBoxes(position(), newYaw);
        List<Vec3> centers = new ArrayList<>(); for (AABB b : boxes) centers.add(b.getCenter());
        Map<Long, List<AABB>> world = gatherWorld(centers, 1);
        for (AABB b : boxes) {
            AABB s = b.deflate(0.02);
            int cx = (int) Math.floor(s.getCenter().x), cy = (int) Math.floor(s.getCenter().y), cz = (int) Math.floor(s.getCenter().z);
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                List<AABB> l = world.get(BlockPos.asLong(cx + x, cy + y, cz + z));
                if (l != null) for (AABB w : l) if (w.intersects(s)) { yawRate = 0; return; }
            }
        }
        yaw = newYaw;
    }

    private void impact(double speed) {
        if (!Config.GROUND_CRUSHING.get() || !(level() instanceof ServerLevel sl)) return;
        double energyJ = 0.5 * model.mass * speed * speed;
        int broken = 0;
        List<BlockPos> under = new ArrayList<>();
        for (BlockPos p : model.surface) if (!model.solid.contains(p.below())) under.add(p);
        if (under.isEmpty()) return;
        double perBlock = energyJ / under.size();
        double hardLimit = perBlock / 40_000.0; // J needed per hardness point
        for (BlockPos p : under) {
            Vec3 c = toWorld(new Vec3(p.getX() + .5, p.getY() - .5, p.getZ() + .5), position(), yaw);
            BlockPos w = BlockPos.containing(c);
            BlockState s = sl.getBlockState(w);
            float h = s.getDestroySpeed(sl, w);
            if (!s.isAir() && h >= 0 && h < hardLimit) { sl.destroyBlock(w, true); if (++broken > 96) break; }
        }
        if (broken > 0) sl.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.5f, 0.6f);
    }

    // ------------------------------------------------------------ riding entities
    private boolean standsOn(Vec3 feetWorld, Vec3 pos, double yw) {
        Vec3 l = toLocal(feetWorld, pos, yw);
        int bx = (int) Math.floor(l.x), bz = (int) Math.floor(l.z);
        for (int by = (int) Math.floor(l.y - 0.3); by >= (int) Math.floor(l.y - 1.2); by--) {
            BlockPos p = new BlockPos(bx, by, bz);
            if (model.solid.contains(p) && model.segOf.getOrDefault(p, 0) == 0) return l.y - (by + model.shapeBounds.get(p).maxY) <= 0.6 && l.y - (by + model.shapeBounds.get(p).maxY) >= -0.6;
        }
        return false;
    }
    public boolean entityStandsOn(Entity e, Vec3 pos, double yw) {
        AABB b = e.getBoundingBox();
        double y = b.minY;
        return standsOn(new Vec3(b.getCenter().x, y, b.getCenter().z), pos, yw)
                || standsOn(new Vec3(b.minX + .05, y, b.minZ + .05), pos, yw) || standsOn(new Vec3(b.maxX - .05, y, b.maxZ - .05), pos, yw)
                || standsOn(new Vec3(b.minX + .05, y, b.maxZ - .05), pos, yw) || standsOn(new Vec3(b.maxX - .05, y, b.minZ + .05), pos, yw);
    }
    private boolean colossusRestsOn(ColossusEntity o, Vec3 pos, double yw) {
        int n = 0;
        for (BlockPos p : o.model.surface) {
            if (o.model.solid.contains(p.below())) continue;
            Vec3 w = o.toWorld(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5), o.position(), o.yaw);
            if (standsOn(w, pos, yw)) return true;
            if (++n > 256) break;
        }
        return false;
    }

    /** Move entities (and lighter Colossi) standing on us along with our motion. */
    private void carry(Vec3 prevPos, double prevYw) {
        double dyaw = yaw - prevYw;
        Vec3 dp = position().subtract(prevPos);
        if (dp.lengthSqr() < 1e-10 && Math.abs(dyaw) < 1e-8) return;
        for (Entity e : level().getEntities(this, getBoundingBox().inflate(1, 3, 1))) {
            if (e.isPassenger() || e instanceof Player || e.noPhysics && !(e instanceof ColossusEntity)) continue;
            boolean on = e instanceof ColossusEntity o ? (o.model != null && o.model.mass < model.mass && colossusRestsOn(o, prevPos, prevYw)) : entityStandsOn(e, prevPos, prevYw);
            if (!on) continue;
            Vec3 l = toLocal(e.position(), prevPos, prevYw);
            Vec3 nw = toWorld(l, position(), yaw);
            e.setPos(nw.x, nw.y, nw.z);
            e.setYRot(e.getYRot() + (float) Math.toDegrees(dyaw));
            if (e instanceof ColossusEntity o) { o.yaw += dyaw; o.prevYaw += dyaw; }
        }
    }

    private void crush() {
        if (!Config.ENTITY_CRUSHING.get() || tickCount % 5 != 0) return;
        double speed = vel.length() + Math.abs(yawRate) * model.radius;
        if (speed < 3) return;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox())) {
            if (e.isPassenger() || entityStandsOn(e, position(), yaw)) continue;
            if (boxesNear(e.getBoundingBox()).isEmpty()) continue;
            float dmg = (float) Math.max(1, Math.min(40, model.mass / 1000.0 * speed * 0.05));
            e.hurt(level().damageSources().source(CRUSHED, this), dmg);
            Vec3 push = e.position().subtract(position()).multiply(1, 0, 1).normalize().scale(0.6).add(vel.scale(0.05));
            e.push(push.x, 0.2, push.z);
        }
    }

    // ------------------------------------------------------------ joints
    private void checkJoints(Vec3 accel) {
        if (!Config.JOINT_BREAKING.get()) return;
        Vec3 geff = accel.add(0, StructureModel.G, 0);
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        for (StructureModel.Joint j : model.joints) {
            if (j.subMass <= 0 || j.childSegs.isEmpty()) continue;
            Vec3 rl = j.subCom.subtract(j.pivot);
            Vec3 r = new Vec3(rl.x * cs - rl.z * sn, rl.y, rl.x * sn + rl.z * cs);
            Vec3 F = geff.scale(j.subMass);
            double ratio = Math.max(F.length() / j.forceCap, r.cross(F).length() / j.momentCap);
            if (ratio > 1) {
                overload[j.id]++;
                if (overload[j.id] % 10 == 1 && level() instanceof ServerLevel sl) {
                    Vec3 c = blockWorldCenter(j.blocks.get(0));
                    sl.sendParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 12, .4, .4, .4, .1);
                    sl.playSound(null, BlockPos.containing(c), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.6f, 0.5f);
                }
                if (overload[j.id] >= 30) { breakJoint(j.id); return; }
            } else overload[j.id] = Math.max(0, overload[j.id] - 1);
        }
    }

    private void breakJoint(int id) {
        Set<BlockPos> rem = model.subtreeBlocks(id);
        Vec3 jc = blockWorldCenter(model.joints.get(id).blocks.get(0));
        int spawned = 0;
        for (BlockPos p : rem) {
            BlockState s = blocks.get(p);
            if (s != null && spawned < 1500) {
                Vec3 w = blockWorldCenter(p);
                FallingBlockEntity fb = new FallingBlockEntity(level(), w.x, w.y - 0.5, w.z, s);
                fb.time = 1; fb.blockData = bes.get(p);
                fb.setDeltaMovement(vel.scale(0.05));
                level().addFreshEntity(fb); spawned++;
            }
        }
        level().playSound(null, BlockPos.containing(jc), SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
        removeBlocks(rem);
        LongArrayTag arr = new LongArrayTag(rem.stream().mapToLong(BlockPos::asLong).toArray());
        CompoundTag t = new CompoundTag(); t.put("P", arr);
        PacketDistributor.sendToPlayersTrackingEntity(this, new Net.StructureRemoved(getId(), t));
    }

    /** Remove local blocks and keep the rest in the same world place. */
    public void removeBlocks(Collection<BlockPos> rem) {
        Vec3 oldCom = model.com;
        Vec3 anchor = toWorld(Vec3.ZERO, position(), yaw); // world point of local origin
        rem.forEach(p -> { blocks.remove(p); bes.remove(p); if (clientBes != null) clientBes.remove(p); });
        if (blocks.isEmpty()) { discard(); return; }
        rebuild();
        // new position so local origin stays where it was
        Vec3 d = model.com; double cs = Math.cos(yaw), sn = Math.sin(yaw);
        setPos(anchor.x + d.x * cs - d.z * sn, anchor.y + d.y, anchor.z + d.x * sn + d.z * cs);
    }

    // ------------------------------------------------------------ riders
    @Override protected boolean canAddPassenger(Entity e) { return getPassengers().isEmpty() && model != null && model.seat != null; }
    @Override public boolean shouldRiderSit() { return true; }

    public Vec3 seatWorld() {
        if (model == null || model.seat == null) return position();
        BlockPos s = model.seat; Integer seg = model.segOf.get(s);
        Vector3d v = segWorld(seg == null ? 0 : seg).transformPosition(new Vector3d(s.getX() + .5, s.getY() + 0.55, s.getZ() + .5));
        return new Vec3(v.x, v.y, v.z);
    }

    @Override protected void positionRider(Entity p, Entity.MoveFunction f) {
        if (!hasPassenger(p)) return;
        Vec3 s = seatWorld(); f.accept(p, s.x, s.y, s.z);
    }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity p) { return seatWorld().add(0, 1.0, 0); }

    // ------------------------------------------------------------ disassembly
    public boolean disassemble(Player who) {
        int k = Math.floorMod((int) Math.round(yaw / (Math.PI / 2)), 4);
        Rotation rot = new Rotation[]{Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90}[k];
        double snapped = Math.round(yaw / (Math.PI / 2)) * (Math.PI / 2);
        Vec3 heartC = toWorld(new Vec3(.5, .5, .5), position(), snapped);
        BlockPos hw = BlockPos.containing(heartC);
        Level lv = level();
        for (BlockPos p : blocks.keySet()) {
            BlockPos t = hw.offset(p.rotate(rot));
            if (!lv.getBlockState(t).canBeReplaced() || !lv.isInWorldBounds(t)) {
                if (who != null) who.displayClientMessage(Component.literal("Can't land here: blocked at " + t.getX() + " " + t.getY() + " " + t.getZ()), true);
                return false;
            }
        }
        ejectPassengers();
        double ratio = energyStart > 0 ? (double) energy / energyStart : 1;
        List<BlockPos> placed = new ArrayList<>();
        for (var e : blocks.entrySet()) {
            BlockPos t = hw.offset(e.getKey().rotate(rot));
            lv.setBlock(t, e.getValue().rotate(lv, t, rot), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed.add(t);
        }
        for (var e : bes.entrySet()) {
            BlockPos t = hw.offset(e.getKey().rotate(rot));
            BlockEntity be = lv.getBlockEntity(t);
            if (be == null) continue;
            CompoundTag tag = e.getValue().copy();
            Block blk = blocks.get(e.getKey()).getBlock();
            if ((blk instanceof PowerBlock || blk instanceof com.colosalcraft.block.ColossalBattery) && tag.contains("Energy")) tag.putLong("Energy", (long) (tag.getLong("Energy") * ratio));
            if (be instanceof HullCoreBlockEntity) tag.put("Joints", jointCfg.copy());
            tag.putInt("x", t.getX()); tag.putInt("y", t.getY()); tag.putInt("z", t.getZ());
            try { be.loadWithComponents(tag, lv.registryAccess()); be.setChanged(); } catch (Exception ignored) {}
            com.colosalcraft.compat.Compat.onLand(lv, t, be);
        }
        for (BlockPos t : placed) lv.blockUpdated(t, lv.getBlockState(t).getBlock());
        discard();
        return true;
    }

    /** Assemble the structure at a Hull Core. */
    public static boolean assemble(Level level, BlockPos heart, Player who) {
        StructureScan.Result r = StructureScan.scan(level, heart);
        if (!r.ok()) { if (who != null) who.displayClientMessage(Component.literal("Can't assemble: " + r.errors.get(0)), false); return false; }
        ColossusEntity c = new ColossusEntity(ModRegistry.COLOSSUS.get(), level);
        c.setup(r, heart);
        // lift blocks out of the world without drops
        List<BlockPos> world = new ArrayList<>();
        for (BlockPos p : r.blocks.keySet()) world.add(heart.offset(p));
        world.sort(Comparator.comparingInt((BlockPos b) -> b.getY()).reversed());
        for (BlockPos p : world) level.removeBlockEntity(p);
        for (BlockPos p : world) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        for (BlockPos p : world) level.blockUpdated(p, net.minecraft.world.level.block.Blocks.AIR);
        level.addFreshEntity(c);
        if (who != null) who.displayClientMessage(Component.literal("Colossus assembled: " + r.blocks.size() + " blocks, " + StructureScan.tons(c.model.mass) + ". Press V near the Pilot Throne."), false);
        return true;
    }

    public void toggleHover() { entityData.set(HOVER, !hover()); }

    // ------------------------------------------------------------ misc entity
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean shouldBeSaved() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double d) { return d < 512 * 512; }
    @Override public void onAddedToLevel() { super.onAddedToLevel(); ColossusCollision.LOADED.add(this); }
    @Override public void onRemovedFromLevel() { super.onRemovedFromLevel(); ColossusCollision.LOADED.remove(this); }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource s, float a) { return false; }

    // ------------------------------------------------------------ save / sync
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var e : blocks.entrySet()) {
            CompoundTag b = new CompoundTag(); b.putLong("P", e.getKey().asLong()); b.put("S", NbtUtils.writeBlockState(e.getValue()));
            CompoundTag be = bes.get(e.getKey()); if (be != null) b.put("E", be);
            list.add(b);
        }
        tag.put("Blocks", list); tag.put("JointCfg", jointCfg);
        tag.putDouble("Yaw", yaw); tag.putDouble("YawRate", yawRate);
        tag.putDouble("VX", vel.x); tag.putDouble("VY", vel.y); tag.putDouble("VZ", vel.z);
        tag.putLong("Energy", energy); tag.putLong("EnergyStart", energyStart); tag.putBoolean("Hover", hover());
        tag.putDouble("Walk", walkPhase); tag.putDouble("Spin", spin);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        HolderGetter<Block> hg = level().holderLookup(Registries.BLOCK);
        blocks = new HashMap<>(); bes = new HashMap<>();
        for (Tag t : tag.getList("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) t; BlockPos p = BlockPos.of(b.getLong("P"));
            blocks.put(p, NbtUtils.readBlockState(hg, b.getCompound("S")));
            if (b.contains("E")) bes.put(p, b.getCompound("E"));
        }
        jointCfg = tag.getCompound("JointCfg");
        yaw = prevYaw = tag.getDouble("Yaw"); yawRate = tag.getDouble("YawRate");
        vel = new Vec3(tag.getDouble("VX"), tag.getDouble("VY"), tag.getDouble("VZ"));
        energy = tag.getLong("Energy"); energyStart = tag.getLong("EnergyStart");
        entityData.set(HOVER, tag.getBoolean("Hover")); walkPhase = tag.getDouble("Walk"); spin = tag.getDouble("Spin");
        entityData.set(YAW, (float) yaw);
        if (!blocks.isEmpty()) rebuild();
    }

    @Override public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(blocks.size());
        for (var e : blocks.entrySet()) { buf.writeLong(e.getKey().asLong()); buf.writeVarInt(Block.getId(e.getValue())); }
        buf.writeNbt(jointCfg); buf.writeDouble(yaw);
        // block-entity data for client-side rendering (chests, machines, modded parts); skip huge ones
        List<Map.Entry<BlockPos, CompoundTag>> send = new ArrayList<>(); int total = 0;
        for (var e : bes.entrySet()) { int sz = e.getValue().sizeInBytes(); if (sz < 32_768 && total + sz < 1_500_000) { send.add(e); total += sz; } }
        buf.writeVarInt(send.size());
        for (var e : send) { buf.writeLong(e.getKey().asLong()); buf.writeNbt(e.getValue()); }
    }
    @Override public void readSpawnData(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt(); blocks = new HashMap<>();
        for (int i = 0; i < n; i++) { long p = buf.readLong(); blocks.put(BlockPos.of(p), Block.stateById(buf.readVarInt())); }
        CompoundTag c = buf.readNbt(); jointCfg = c == null ? new CompoundTag() : c;
        yaw = prevYaw = buf.readDouble();
        int nb = buf.readVarInt(); bes = new HashMap<>();
        for (int i = 0; i < nb; i++) { long p = buf.readLong(); CompoundTag t = buf.readNbt(); if (t != null) bes.put(BlockPos.of(p), t); }
        rebuild();
        clientBes = null;
    }
}
