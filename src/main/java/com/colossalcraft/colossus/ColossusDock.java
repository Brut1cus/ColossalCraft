package com.colossalcraft.colossus;

import com.colossalcraft.ColossalCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.*;

/**
 * The dock: a quiet, empty dimension where every built Colossus keeps a real copy of its blocks, laid out as it was built.
 * Because they are real blocks in a real world, everything on a Colossus works like it does on the ground: doors, chests,
 * furnaces, redstone, crops, Create machines. The Colossus entity shows and moves them; changes go both ways:
 * edits on the Colossus are written into the dock, and anything that changes in the dock (a door opens, a machine places a
 * block, a chest is filled) comes back to the Colossus. Sounds, effects and dropped items in the dock happen at the Colossus.
 */
public final class ColossusDock {
    private ColossusDock() {}
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ColossalCraft.MODID, "dock"));
    /** Each Colossus gets a square slot this wide; slots start far from the origin, so dock places never look like overworld places. */
    static final int BASE = 10_000_000, SPACING = 2048, PER_ROW = 128, OY = 64;
    /** Set when the Colossus sets its blocks in the dock itself: those changes are not sent back. */
    private static int writing;
    private static final Map<Integer, ColossusEntity> LIVE = new HashMap<>();
    private static final Map<Integer, Set<BlockPos>> PENDING = new HashMap<>(), PENDING_BE = new HashMap<>();
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    public static boolean isDock(Level l) { return l != null && !l.isClientSide && l.dimension() == KEY; }
    public static ServerLevel level(MinecraftServer s) { return s == null ? null : s.getLevel(KEY); }
    public static BlockPos origin(int slot) { return new BlockPos(BASE + (slot % PER_ROW) * SPACING, OY, BASE + (slot / PER_ROW) * SPACING); }
    public static int slotOf(BlockPos p) {
        if (p.getX() < BASE - SPACING / 2 || p.getZ() < BASE - SPACING / 2) return -1;
        int sx = Math.floorDiv(p.getX() - BASE + SPACING / 2, SPACING), sz = Math.floorDiv(p.getZ() - BASE + SPACING / 2, SPACING);
        return sx >= PER_ROW ? -1 : sz * PER_ROW + sx;
    }
    public static BlockPos toDock(ColossusEntity c, BlockPos local) { return origin(c.dockSlot).offset(local); }
    /** The Colossus whose slot holds this dock place, or null. */
    public static ColossusEntity owner(BlockPos dockPos) { int s = slotOf(dockPos); return s < 0 ? null : LIVE.get(s); }

    // ------------------------------------------------------------ slots (saved with the dock)
    public static final class Slots extends SavedData {
        final Map<Integer, UUID> owners = new HashMap<>();
        static Slots load(CompoundTag t, HolderLookup.Provider r) {
            Slots s = new Slots();
            for (Tag x : t.getList("S", Tag.TAG_COMPOUND)) { CompoundTag c = (CompoundTag) x; s.owners.put(c.getInt("I"), c.getUUID("U")); }
            return s;
        }
        @Override public CompoundTag save(CompoundTag t, HolderLookup.Provider r) {
            ListTag l = new ListTag();
            for (var e : owners.entrySet()) { CompoundTag c = new CompoundTag(); c.putInt("I", e.getKey()); c.putUUID("U", e.getValue()); l.add(c); }
            t.put("S", l); return t;
        }
        int allocate(UUID u) { int i = 0; while (owners.containsKey(i)) i++; owners.put(i, u); setDirty(); return i; }
        void free(int i) { if (owners.remove(i) != null) setDirty(); }
    }
    static Slots slots(ServerLevel d) { return d.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Slots::new, Slots::load, null), "colossalcraft_dock_slots"); }

    // ------------------------------------------------------------ attach / detach / release
    /** Give a Colossus its dock place and make sure its blocks are there (kept as they are if they already match). */
    public static boolean attach(ColossusEntity c) {
        ServerLevel d = level(c.level().getServer());
        if (d == null || c.blocks.isEmpty()) return false;
        Slots s = slots(d);
        if (c.dockSlot < 0 || !c.getUUID().equals(s.owners.get(c.dockSlot))) {
            if (c.dockSlot >= 0 && s.owners.containsKey(c.dockSlot)) c.dockSlot = -1; // someone else has that place now
            c.dockSlot = s.allocate(c.getUUID());
        }
        ColossusEntity was = LIVE.get(c.dockSlot);
        if (was != null && was != c && !was.isRemoved()) { c.dockSlot = s.allocate(c.getUUID()); }
        LIVE.put(c.dockSlot, c);
        forceChunks(d, c, true);
        BlockPos o = origin(c.dockSlot);
        writing++;
        try {
            for (var e : c.blocks.entrySet()) {
                BlockPos dp = o.offset(e.getKey());
                if (!d.isInWorldBounds(dp)) continue;
                BlockState cur = d.getBlockState(dp);
                boolean fresh = cur != e.getValue();
                if (fresh) { d.removeBlockEntity(dp); d.setBlock(dp, e.getValue(), FLAGS); }
                CompoundTag tag = c.bes.get(e.getKey());
                BlockEntity be = d.getBlockEntity(dp);
                if (be != null && tag != null && fresh) loadBe(d, be, dp, tag);
            }
        } finally { writing--; }
        return true;
    }
    /** The Colossus went out of the loaded world (or the server stops): let its dock place sleep, keep the blocks. */
    public static void detach(ColossusEntity c) {
        if (c.dockSlot < 0 || LIVE.get(c.dockSlot) != c) return;
        ServerLevel d = level(c.level().getServer());
        if (d != null) forceChunks(d, c, false);
        LIVE.remove(c.dockSlot);
    }
    /** The Colossus is gone for good (parked, broken up, killed): clear its dock place. */
    public static void release(ColossusEntity c) {
        if (c.dockSlot < 0) return;
        ServerLevel d = level(c.level().getServer());
        if (d != null && (LIVE.get(c.dockSlot) == c || LIVE.get(c.dockSlot) == null)) {
            BlockPos o = origin(c.dockSlot);
            writing++;
            try {
                for (BlockPos p : c.blocks.keySet()) { BlockPos dp = o.offset(p); d.removeBlockEntity(dp); d.setBlock(dp, Blocks.AIR.defaultBlockState(), FLAGS); }
            } finally { writing--; }
            forceChunks(d, c, false);
            slots(d).free(c.dockSlot);
        }
        LIVE.remove(c.dockSlot);
        PENDING.remove(c.dockSlot); PENDING_BE.remove(c.dockSlot);
        c.dockSlot = -1;
    }
    private static void forceChunks(ServerLevel d, ColossusEntity c, boolean on) {
        BlockPos o = origin(c.dockSlot);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : c.blocks.keySet()) { x0 = Math.min(x0, p.getX()); x1 = Math.max(x1, p.getX()); z0 = Math.min(z0, p.getZ()); z1 = Math.max(z1, p.getZ()); }
        if (x0 > x1) return;
        int cx0 = (o.getX() + x0 - 2) >> 4, cx1 = (o.getX() + x1 + 2) >> 4, cz0 = (o.getZ() + z0 - 2) >> 4, cz1 = (o.getZ() + z1 + 2) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) d.setChunkForced(cx, cz, on);
        c.dockChunks = new int[]{cx0, cz0, cx1, cz1};
    }
    /** Keep the forced chunks covering the blocks after the Colossus grew. */
    static void growChunks(ServerLevel d, ColossusEntity c, Collection<BlockPos> added) {
        if (c.dockChunks == null) return;
        BlockPos o = origin(c.dockSlot);
        for (BlockPos p : added) {
            int cx = (o.getX() + p.getX()) >> 4, cz = (o.getZ() + p.getZ()) >> 4;
            if (cx < c.dockChunks[0] || cx > c.dockChunks[2] || cz < c.dockChunks[1] || cz > c.dockChunks[3]) { d.setChunkForced(cx, cz, true); }
        }
    }
    private static void loadBe(ServerLevel d, BlockEntity be, BlockPos dp, CompoundTag tag) {
        CompoundTag t = tag.copy(); t.putInt("x", dp.getX()); t.putInt("y", dp.getY()); t.putInt("z", dp.getZ());
        try { be.loadWithComponents(t, d.registryAccess()); be.setChanged(); } catch (Exception ex) { ColossalCraft.LOG.warn("dock: couldn't load block entity at {}: {}", dp, ex.toString()); }
    }

    // ------------------------------------------------------------ Colossus -> dock
    /** Blocks were set or removed on the Colossus: do the same in its dock place. */
    public static void write(ColossusEntity c, Map<BlockPos, BlockState> set, Map<BlockPos, CompoundTag> setBes, Collection<BlockPos> del) {
        ServerLevel d = level(c.level().getServer());
        if (d == null || c.dockSlot < 0 || LIVE.get(c.dockSlot) != c) return;
        BlockPos o = origin(c.dockSlot);
        writing++;
        try {
            for (BlockPos p : del) { BlockPos dp = o.offset(p); d.removeBlockEntity(dp); d.setBlock(dp, Blocks.AIR.defaultBlockState(), FLAGS); }
            for (var e : set.entrySet()) {
                BlockPos dp = o.offset(e.getKey());
                if (!d.isInWorldBounds(dp)) continue;
                if (d.getBlockState(dp) != e.getValue()) d.setBlock(dp, e.getValue(), FLAGS);
                CompoundTag tag = setBes.get(e.getKey());
                BlockEntity be = d.getBlockEntity(dp);
                if (be != null && tag != null) loadBe(d, be, dp, tag);
            }
        } finally { writing--; }
        growChunks(d, c, set.keySet());
    }
    /** The live block entity of a Colossus block (the real one in the dock). */
    public static BlockEntity be(ColossusEntity c, BlockPos local) {
        ServerLevel d = level(c.level().getServer());
        return d == null || c.dockSlot < 0 ? null : d.getBlockEntity(toDock(c, local));
    }

    // ------------------------------------------------------------ dock -> Colossus
    /** Called for every block change in the dock (from a mixin on Level.setBlock). */
    public static void changed(BlockPos dockPos) {
        if (writing > 0) return;
        int s = slotOf(dockPos);
        if (s >= 0 && LIVE.containsKey(s)) PENDING.computeIfAbsent(s, k -> new HashSet<>()).add(dockPos.immutable());
    }
    /** A block entity in the dock changed (its contents, its machine state). */
    public static void beChanged(BlockPos dockPos) {
        if (writing > 0) return;
        int s = slotOf(dockPos);
        if (s >= 0 && LIVE.containsKey(s)) PENDING_BE.computeIfAbsent(s, k -> new HashSet<>()).add(dockPos.immutable());
    }
    /** Bring what changed in the dock back to the Colossus (every tick; block entity data every half second). */
    public static void flush(ColossusEntity c, boolean bes) {
        if (c.dockSlot < 0 || LIVE.get(c.dockSlot) != c) return;
        ServerLevel d = level(c.level().getServer());
        if (d == null) return;
        BlockPos o = origin(c.dockSlot);
        Set<BlockPos> ch = PENDING.remove(c.dockSlot);
        Map<BlockPos, BlockState> set = new HashMap<>(); Map<BlockPos, CompoundTag> setBes = new HashMap<>(); List<BlockPos> del = new ArrayList<>();
        if (ch != null) for (BlockPos dp : ch) {
            BlockPos p = dp.subtract(o);
            BlockState s = d.getBlockState(dp), was = c.blocks.get(p);
            if (s == was) continue;
            if (s.isAir()) { if (was != null) del.add(p); continue; }
            if (was == null && s.getBlock() instanceof LiquidBlock && !s.getFluidState().isSource()) continue; // running water doesn't become part of it
            if (was == null && !touches(c, p)) continue;
            set.put(p, s);
            BlockEntity be = d.getBlockEntity(dp);
            if (be != null) setBes.put(p, be.saveWithFullMetadata(d.registryAccess()));
        }
        Set<BlockPos> bch = bes ? PENDING_BE.remove(c.dockSlot) : null;
        if (bch != null) for (BlockPos dp : bch) {
            BlockPos p = dp.subtract(o);
            if (set.containsKey(p) || !c.blocks.containsKey(p)) continue;
            BlockEntity be = d.getBlockEntity(dp);
            if (be == null) continue;
            CompoundTag tag = be.saveWithFullMetadata(d.registryAccess());
            if (tag.equals(c.bes.get(p))) continue;
            set.put(p, c.blocks.get(p)); setBes.put(p, tag);
        }
        if (!set.isEmpty() || !del.isEmpty()) c.editFromDock(set, setBes, del);
    }
    private static boolean touches(ColossusEntity c, BlockPos p) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) if (c.blocks.containsKey(p.relative(dir))) return true;
        return false;
    }

    // ------------------------------------------------------------ the dock's sounds, effects and items happen at the Colossus
    /** World place of a point in the dock (block-space coordinates), and the Colossus it belongs to. */
    static Vec3 toWorld(ColossusEntity c, double x, double y, double z) {
        BlockPos o = origin(c.dockSlot);
        double lx = x - o.getX(), ly = y - o.getY(), lz = z - o.getZ();
        Integer seg = c.model == null ? null : c.model.segOf.get(BlockPos.containing(lx, ly, lz));
        Vector3d w = c.segWorld(seg == null ? 0 : seg).transformPosition(new Vector3d(lx, ly, lz));
        return new Vec3(w.x, w.y, w.z);
    }
    public static boolean relaySound(double x, double y, double z, Holder<SoundEvent> snd, SoundSource src, float vol, float pitch, long seed) {
        ColossusEntity c = owner(BlockPos.containing(x, y, z));
        if (c == null || c.isRemoved()) return true;
        Vec3 w = toWorld(c, x, y, z);
        c.level().playSeededSound(null, w.x, w.y, w.z, snd, src, vol, pitch, seed);
        return true;
    }
    public static boolean relayLevelEvent(int type, BlockPos pos, int data) {
        ColossusEntity c = owner(pos);
        if (c == null || c.isRemoved()) return true;
        c.level().levelEvent(null, type, BlockPos.containing(toWorld(c, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5)), data);
        return true;
    }
    /** An entity appeared in the dock (dropped item, arrow from a dispenser, xp): send it out at the Colossus instead. */
    public static boolean relayEntity(Entity e) {
        if (e instanceof Player) return false;
        ColossusEntity c = owner(e.blockPosition());
        if (c == null || c.isRemoved() || !(c.level() instanceof ServerLevel target)) return true;
        Entity copy = e.getType().create(target);
        if (copy == null) return true;
        copy.restoreFrom(e);
        Vec3 w = toWorld(c, e.getX(), e.getY(), e.getZ());
        Vec3 v = e.getDeltaMovement(); double cs = Math.cos(c.yaw), sn = Math.sin(c.yaw);
        copy.moveTo(w.x, w.y, w.z, e.getYRot() + (float) Math.toDegrees(c.yaw), e.getXRot());
        copy.setDeltaMovement(v.x * cs - v.z * sn, v.y, v.x * sn + v.z * cs);
        target.addFreshEntity(copy);
        return true;
    }
    /** A player may use a dock block if the Colossus block it stands for is within their reach. */
    public static boolean reachable(Player p, BlockPos dockPos, double extra) {
        if (p.level().isClientSide) return false;
        ColossusEntity c = owner(dockPos);
        if (c == null || c.isRemoved() || c.level() != p.level()) return false;
        BlockPos local = dockPos.subtract(origin(c.dockSlot));
        if (!c.blocks.containsKey(local)) return false;
        return p.getEyePosition().distanceTo(c.blockWorldCenter(local)) <= p.blockInteractionRange() + extra + 1.5;
    }
}
