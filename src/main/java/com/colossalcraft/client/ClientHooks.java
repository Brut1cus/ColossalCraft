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
    public static boolean tryClickColossus() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player; if (p == null || p.isPassenger()) return false;
        Vec3 eye = p.getEyePosition(), to = eye.add(p.getViewVector(1f).scale(p.blockInteractionRange()));
        double worldDist = mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS ? mc.hitResult.getLocation().distanceTo(eye) : Double.MAX_VALUE;
        for (ColossusEntity c : com.colossalcraft.colossus.ColossusCollision.all(true)) {
            if (c.level() != p.level() || c.model == null || !c.getBoundingBox().inflate(6).contains(eye)) continue;
            BlockPos hit = c.raycastBlock(eye, to);
            if (hit == null || c.blockWorldCenter(hit).distanceTo(eye) > worldDist + 0.7) continue;
            var b = c.blocks.get(hit).getBlock();
            if (b instanceof com.colossalcraft.block.HullCoreBlock) { net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ClickColossus(c.getId(), 0)); return true; }
            if (b instanceof com.colossalcraft.block.SimpleParts.LocomotionControllerBlock) { net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ClickColossus(c.getId(), 2)); return true; }
            if (b instanceof com.colossalcraft.block.SimpleParts.PilotSeatBlock) { net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.ClickColossus(c.getId(), 1)); return true; }
        }
        return false;
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
