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
        if (mc.screen instanceof HullCoreScreen s && s.pos.equals(pos)) s.setData(data);
        else mc.setScreen(new HullCoreScreen(pos, data));
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
    public static void carryLocalPlayer(ColossusEntity c, Vec3 prevPos, double prevYaw) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.level() != c.level()) return;
        double dyaw = c.yaw - prevYaw;
        if (p.getVehicle() == c) { p.setYRot(p.getYRot() + (float) Math.toDegrees(dyaw)); p.yRotO = p.getYRot(); return; }
        if (p.isPassenger()) return;
        Vec3 dp = c.position().subtract(prevPos);
        if (dp.lengthSqr() < 1e-10 && Math.abs(dyaw) < 1e-8) return;
        if (!c.getBoundingBox().inflate(1, 3, 1).intersects(p.getBoundingBox())) return;
        if (!c.entityStandsOn(p, prevPos, prevYaw)) return;
        Vec3 l = c.toLocal(p.position(), prevPos, prevYaw);
        Vec3 n = c.toWorld(l, c.position(), c.yaw);
        p.setPos(n.x, n.y, n.z);
        p.setYRot(p.getYRot() + (float) Math.toDegrees(dyaw));
    }
}
