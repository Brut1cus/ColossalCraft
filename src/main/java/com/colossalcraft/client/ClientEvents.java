package com.colossalcraft.client;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.ModRegistry;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.network.Net;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    public static final String CAT = "key.categories.colossalcraft";
    public static final KeyMapping PILOT = new KeyMapping("key.colossalcraft.pilot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CAT);
    public static final KeyMapping DESCEND = new KeyMapping("key.colossalcraft.descend", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CAT);
    public static final KeyMapping HOVER = new KeyMapping("key.colossalcraft.hover", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CAT);
    public static final KeyMapping DISASSEMBLE = new KeyMapping("key.colossalcraft.disassemble", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CAT);

    @EventBusSubscriber(modid = ColossalCraft.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) { e.registerEntityRenderer(ModRegistry.COLOSSUS.get(), ColossusRenderer::new); }
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(PILOT); e.register(DESCEND); e.register(HOVER); e.register(DISASSEMBLE); }
        @SubscribeEvent public static void hud(RegisterGuiLayersEvent e) {
            e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(ColossalCraft.MODID, "hud"), (g, delta) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || !(mc.player.getVehicle() instanceof ColossusEntity c) || c.model == null || mc.options.hideGui) return;
                double speed = c.clientSpeed;
                String en = c.syncedInfinite() ? "INFINITE" : StructureScan.kilo(c.syncedEnergy()) + " CJ";
                String[] lines = {
                        "COLOSSUS  " + StructureScan.tons(c.model.mass) + "",
                        "ENERGY " + en + "   OUT " + StructureScan.kilo(c.model.output) + " CJ/t",
                        String.format("SPEED %.1f m/s   ALT %d", speed, (int) c.getY()),
                        "HOVER " + (c.hover() ? "ON" : "OFF") + "   [H] hover  [C] down  [K] land  [V] leave"};
                int y = 6;
                for (String s : lines) { g.fill(4, y - 2, 8 + mc.font.width(s), y + 9, 0x90000000); g.drawString(mc.font, s, 6, y, 0xFF7CFF9E, false); y += 11; }
            });
        }
    }

    @EventBusSubscriber(modid = ColossalCraft.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (PILOT.consumeClick()) PacketDistributor.sendToServer(new Net.PilotAction(0));
            while (DISASSEMBLE.consumeClick()) if (mc.player.getVehicle() instanceof ColossusEntity) PacketDistributor.sendToServer(new Net.PilotAction(1));
            while (HOVER.consumeClick()) if (mc.player.getVehicle() instanceof ColossusEntity) PacketDistributor.sendToServer(new Net.PilotAction(2));
            if (mc.player.getVehicle() instanceof ColossusEntity && mc.screen == null && !DevShots.ON) {
                var in = mc.player.input;
                PacketDistributor.sendToServer(new Net.PilotInput(in.forwardImpulse, in.leftImpulse, in.jumping, DESCEND.isDown()));
            }
        }

        /** Third person (F5 back and front) pulls the camera out to fit the whole Colossus. */
        @SubscribeEvent public static void camera(CalculateDetachedCameraDistanceEvent e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getVehicle() instanceof ColossusEntity c && c.model != null)
                e.setDistance((float) Math.max(e.getDistance(), c.model.radius * 1.6 + 4));
        }
    }
}
