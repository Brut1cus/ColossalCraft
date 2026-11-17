package com.colossalcraft.client;

import com.colossalcraft.colossus.ColossusEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class ClientHooks {
    public static void hullData(BlockPos pos, CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (data.getBoolean("Closed")) { if (mc.screen instanceof HullCoreScreen) mc.setScreen(null); return; }
        if (mc.screen instanceof HullCoreScreen s && s.matches(pos, data)) s.setData(data);
        else mc.setScreen(new HullCoreScreen(pos, data));
    }

    public static void colossusConfig(int id, CompoundTag cfg) {
        var lvl = Minecraft.getInstance().level; if (lvl == null) return;
        if (lvl.getEntity(id) instanceof ColossusEntity c) c.setJointCfg(cfg);
    }

    /** Right-click on a built Colossus: Hull Core opens its screen, Pilot Throne seats you. Returns true if handled. */
    public static boolean tryClickColossus() { return tryColossus(false); }

    /** Right- or left-click on a built Colossus: special blocks (Hull Core, controller, throne) open/seat; anything else is sent to the server
     *  to use, mine or build against. Returns true if a Colossus block was in front of the world. */
    public static boolean tryColossus(boolean attack) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player; if (p == null || p.isPassenger()) return false;
        Hit h = look(); if (h == null) return false;
        ColossusEntity c = h.c(); BlockPos hit = h.pos(); net.minecraft.core.Direction face = h.face();
        var b = c.blocks.get(hit).getBlock();
        if (!attack && !p.isSecondaryUseActive()) {
            if (b instanceof com.colossalcraft.block.HullCoreBlock) { net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ClickColossus(c.getId(), 0)); return true; }
            if (b instanceof com.colossalcraft.block.SimpleParts.LocomotionControllerBlock) { net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ClickColossus(c.getId(), 2)); return true; }
            if (b instanceof com.colossalcraft.block.SimpleParts.PilotSeatBlock) { net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ClickColossus(c.getId(), 1)); return true; }
        }
        // where on the block face it was clicked (sixteenths), for slabs, stairs, buttons and the like
        int hx = clamp16(h.at().x), hy = clamp16(h.at().y), hz = clamp16(h.at().z);
        int packed = face.get3DDataValue() | 8 | (hx << 4) | (hy << 9) | (hz << 14);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ColossusBlock(c.getId(), hit.asLong(), packed, attack ? 1 : 0));
        return true;
    }
    private static int clamp16(double v) { return (int) Math.max(0, Math.min(16, Math.round(v * 16))); }

    /** The Colossus block the player looks at (nearest of all Colossi, and nearer than the world block behind it), or null. */
    public record Hit(ColossusEntity c, BlockPos pos, net.minecraft.core.Direction face, Vec3 at, double dist) {}
    private static Hit lastHit; private static long lastHitTick = -1; private static float lastHitPt = -1;
    public static Hit look() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player; if (p == null || mc.level == null) return null;
        Vec3 eye = p.getEyePosition(), to = eye.add(p.getViewVector(1f).scale(p.blockInteractionRange()));
        double worldDist = mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? mc.hitResult.getLocation().distanceTo(eye) : Double.MAX_VALUE;
        Hit best = null;
        for (ColossusEntity c : com.colossalcraft.colossus.ColossusCollision.all(true)) {
            if (c.isRemoved() || c.level() != p.level() || c.model == null || c.blocks.isEmpty() || !c.getBoundingBox().inflate(p.blockInteractionRange() + 1).contains(eye)) continue;
            Object[] h = c.raycastHit(eye, to);
            if (h == null) continue;
            BlockPos hit = (BlockPos) h[0];
            double d = c.blockWorldCenter(hit).distanceTo(eye) - 0.5;
            if (d > worldDist + 0.3) continue;
            if (best == null || d < best.dist()) best = new Hit(c, hit, (net.minecraft.core.Direction) h[1], (Vec3) h[2], d);
        }
        return best;
    }

    /** Step Controller map arrived: open the screen, or refresh it if it's open. */
    public static void stepData(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof HullCoreScreen s) s.footingMap(data); // the Locomotion Controller's Footing tab
    }

    public static void colossusBlocks(int id, CompoundTag data) {
        var lvl = Minecraft.getInstance().level; if (lvl == null) return;
        if (lvl.getEntity(id) instanceof ColossusEntity c) c.applyEdit(data);
    }

    public static void structureRemoved(int id, CompoundTag data) {
        var lvl = Minecraft.getInstance().level; if (lvl == null) return;
        Entity e = lvl.getEntity(id);
        if (e instanceof ColossusEntity c) {
            List<BlockPos> rem = new ArrayList<>(); for (long l : data.getLongArray("P")) rem.add(BlockPos.of(l));
            c.removeBlocks(rem);
        }
    }

    /** Client side: the local player is moved with the deck it stands on, and turned with the Colossus it pilots. */
    public static void carryLocalPlayer(ColossusEntity c, org.joml.Matrix4d[] before, org.joml.Matrix4d[] after, double dyaw) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.level() != c.level()) return;
        if (p.getVehicle() == c) { p.setYRot(p.getYRot() + (float) Math.toDegrees(dyaw)); p.yRotO = p.getYRot(); return; }
        if (p.isPassenger() || !c.getBoundingBox().inflate(1, 3, 1).intersects(p.getBoundingBox())) return;
        org.joml.Matrix4d[] inv = ColossusEntity.invert(before);
        int seg = c.standingSeg(p, inv);
        if (seg >= 0) c.carryOne(p, seg, inv, after, dyaw);
        else c.unstick(p);
    }
}
