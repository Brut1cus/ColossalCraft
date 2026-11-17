package com.colossalcraft.colossus;

import com.colossalcraft.network.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Step Controller screen data: a small top-down map of the ground around the walker (height in half blocks, and what kind of
 * ground it is), where its body and feet are, and where each foot will step next. Sent on request (the screen asks every
 * half second), so it costs nothing when nobody is looking.
 */
public final class StepPlanner {
    private StepPlanner() {}
    /** Map half-size in blocks: (2R+1)^2 columns. */
    public static final int R = 12;
    public static final byte NONE = 127, WALL = 126;

    public static void handle(ServerPlayer sp, Net.StepRequest r) {
        if (r.entity() >= 0) {
            if (sp.level().getEntity(r.entity()) instanceof ColossusEntity c && !c.isRemoved() && c.distanceToSqr(sp) < 96 * 96) sendBuilt(sp, c);
        } else sendParked(sp, BlockPos.of(r.pos()));
    }

    /** A built walker: live feet and planned steps. */
    public static void sendBuilt(ServerPlayer sp, ColossusEntity c) {
        StructureModel m = c.model;
        WalkerController w = c.walker;
        double refY = Double.NaN, sum = 0; int k = 0;
        for (int i = 0; i < w.foot.length; i++) if (w.state[i] == WalkerController.State.STANCE) { sum += w.foot[i].y; k++; }
        refY = k > 0 ? sum / k : c.getY() + (m.localBounds.minY - m.com.y);
        List<Vec3> body = new ArrayList<>();
        int step = Math.max(1, m.blocks.size() / 3000);
        int n = 0;
        for (StructureModel.Seg sg : m.segs) {
            if (m.legSegs.contains(sg.id)) continue;
            for (BlockPos p : sg.blocks) if (n++ % step == 0) body.add(c.blockWorldCenter(p));
        }
        ListTag feet = new ListTag(), next = new ListTag();
        for (int i = 0; i < w.foot.length; i++) {
            Vec3 f = w.foot[i]; if (f == null) continue;
            CompoundTag t = new CompoundTag(); t.putFloat("X", (float) f.x); t.putFloat("Z", (float) f.z);
            t.putByte("S", (byte) (w.state[i] == WalkerController.State.STANCE ? 0 : w.state[i] == WalkerController.State.SWING ? 1 : 2));
            feet.add(t);
            if (i < w.next.length && w.next[i] != null) {
                CompoundTag q = new CompoundTag(); q.putFloat("X", (float) w.next[i].x); q.putFloat("Z", (float) w.next[i].z); q.putBoolean("Ok", w.nextOk[i]);
                next.add(q);
            }
        }
        Vec3 fl = Vec3.atLowerCornerOf(m.forward.getNormal());
        double cs = Math.cos(c.yaw), sn = Math.sin(c.yaw);
        CompoundTag t = map(c.level(), c.position(), refY, body);
        t.put("Feet", feet); t.put("Next", next);
        t.putFloat("FX", (float) (fl.x * cs - fl.z * sn)); t.putFloat("FZ", (float) (fl.x * sn + fl.z * cs));
        t.putBoolean("Built", true); t.putInt("E", c.getId());
        t.putBoolean("Smart", m.smartFooting); t.putBoolean("Pw", w.planner); t.putInt("Legs", m.legChains.size());
        t.putString("Status", w.status);
        PacketDistributor.sendToPlayer(sp, new Net.StepData(t));
    }

    /** Not built yet: the ground around it, its body and where its feet stand. */
    public static void sendParked(ServerPlayer sp, BlockPos controller) {
        BlockPos heart = Net.findHeart(sp.level(), controller);
        if (heart == null) return;
        StructureScan.Result r = StructureScan.scan(sp.level(), heart);
        StructureModel m = r.model;
        List<Vec3> body = new ArrayList<>();
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : r.blocks.keySet()) minY = Math.min(minY, p.getY());
        int step = Math.max(1, r.blocks.size() / 3000), n = 0;
        for (StructureModel.Seg sg : m.segs) {
            if (m.legSegs.contains(sg.id)) continue;
            for (BlockPos p : sg.blocks) if (n++ % step == 0) body.add(Vec3.atCenterOf(heart.offset(p)));
        }
        ListTag feet = new ListTag();
        for (StructureModel.LegChain L : m.legChains) {
            CompoundTag t = new CompoundTag(); t.putFloat("X", (float) (heart.getX() + L.foot().x)); t.putFloat("Z", (float) (heart.getZ() + L.foot().z)); t.putByte("S", (byte) 0);
            feet.add(t);
        }
        Vec3 f = Vec3.atLowerCornerOf(m.forward.getNormal());
        CompoundTag t = map(sp.level(), Vec3.atLowerCornerOf(heart).add(m.com), heart.getY() + minY, body);
        t.put("Feet", feet); t.put("Next", new ListTag());
        t.putFloat("FX", (float) f.x); t.putFloat("FZ", (float) f.z);
        t.putBoolean("Built", false); t.putLong("Pos", controller.asLong());
        t.putBoolean("Smart", m.smartFooting); t.putBoolean("Pw", false); t.putInt("Legs", m.legChains.size());
        t.putString("Status", "");
        PacketDistributor.sendToPlayer(sp, new Net.StepData(t));
    }

    /** The ground map: per column the surface height relative to the feet (half blocks) and its kind. */
    static CompoundTag map(Level lv, Vec3 center, double refY, List<Vec3> body) {
        int cx = (int) Math.floor(center.x), cz = (int) Math.floor(center.z), size = 2 * R + 1;
        byte[] h = new byte[size * size], kind = new byte[size * size];
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int top = (int) Math.floor(refY) + 6;
        for (int dz = -R; dz <= R; dz++) for (int dx = -R; dx <= R; dx++) {
            int idx = (dz + R) * size + (dx + R), x = cx + dx, z = cz + dz;
            double gy = WalkerController.groundFrom(lv, x + .5, top + 1, z + .5, 13);
            if (Double.isNaN(gy)) {
                mp.set(x, top, z);
                h[idx] = lv.getBlockState(mp).getCollisionShape(lv, mp).isEmpty() ? NONE : WALL;
                continue;
            }
            h[idx] = (byte) Math.max(-60, Math.min(60, Math.round((gy - refY) * 2)));
            mp.set(x, (int) Math.floor(gy - 0.05), z);
            BlockState under = lv.getBlockState(mp);
            mp.set(x, (int) Math.floor(gy + 0.05), z);
            BlockState at = lv.getBlockState(mp);
            if (WalkerController.hazard(under) || WalkerController.hazard(at) || lv.getFluidState(mp).is(FluidTags.LAVA)) kind[idx] = 2;
            else if (!lv.getFluidState(mp).isEmpty()) kind[idx] = 1;
            else if (under.getBlock().getFriction() > 0.8f) kind[idx] = 3;
        }
        Set<Integer> cells = new LinkedHashSet<>();
        for (Vec3 b : body) {
            int dx = (int) Math.floor(b.x) - cx, dz = (int) Math.floor(b.z) - cz;
            if (Math.abs(dx) <= R && Math.abs(dz) <= R) cells.add((dz + R) * size + (dx + R));
        }
        CompoundTag t = new CompoundTag();
        t.putInt("X", cx); t.putInt("Z", cz); t.putInt("R", R);
        t.putByteArray("H", h); t.putByteArray("K", kind);
        t.putIntArray("Body", cells.stream().mapToInt(Integer::intValue).toArray());
        return t;
    }
}
