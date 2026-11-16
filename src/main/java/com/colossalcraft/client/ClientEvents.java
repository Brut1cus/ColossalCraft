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
    public static final KeyMapping SCREEN = new KeyMapping("key.colossalcraft.screen", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CAT);
    public static final KeyMapping LOCO = new KeyMapping("key.colossalcraft.loco", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CAT);
    public static final KeyMapping JUMP_DRIVE = new KeyMapping("key.colossalcraft.jump", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CAT);
    /** Actions the Locomotion Controller can bind: 0 forward, 1 back, 2 left, 3 right, 4 raise, 5 lower, 6 sprint, 7 jump drive. */
    public static final String[] ACTIONS = {"Forward", "Back", "Turn left", "Turn right", "Raise / up", "Lower / down", "Sprint", "Jump Drive"};
    public static KeyMapping defaultKey(int action) {
        var o = Minecraft.getInstance().options;
        return switch (action) { case 0 -> o.keyUp; case 1 -> o.keyDown; case 2 -> o.keyLeft; case 3 -> o.keyRight; case 4 -> o.keyJump; case 5 -> o.keyShift; case 6 -> o.keySprint; default -> JUMP_DRIVE; };
    }
    /** Is the key bound to this action (in the Colossus' Locomotion settings) held down? */
    public static boolean actionDown(ColossusEntity c, int action) {
        int code = c.jointCfg.getCompound(com.colossalcraft.colossus.StructureModel.LOCO).contains("K" + action) ? c.jointCfg.getCompound(com.colossalcraft.colossus.StructureModel.LOCO).getInt("K" + action) : -1;
        if (code < 0) return defaultKey(action).isDown();
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);
    }
    public static final KeyMapping DISASSEMBLE = new KeyMapping("key.colossalcraft.disassemble", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CAT);

    @EventBusSubscriber(modid = ColossalCraft.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) { e.registerEntityRenderer(ModRegistry.COLOSSUS.get(), ColossusRenderer::new); e.registerEntityRenderer(ModRegistry.SEAT.get(), net.minecraft.client.renderer.entity.NoopRenderer::new); }
        @SubscribeEvent public static void menus(RegisterMenuScreensEvent e) { e.register(ModRegistry.ENGINE_MENU.get(), EngineScreen::new); }
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(PILOT); e.register(DESCEND); e.register(HOVER); e.register(DISASSEMBLE); e.register(SCREEN); e.register(LOCO); e.register(JUMP_DRIVE); }
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
                        "HOVER " + (c.hover() ? "ON" : "OFF") + "   [G] Hull Core  [L] Locomotion  [H] hover  [K] park  [V] leave",
                        c.walkerStatus().isEmpty() ? "" : "FEET  " + c.walkerStatus(),
                        c.jumpLevel() < 0 ? "" : String.format("JUMP DRIVE %.0f%%%s", c.jumpLevel() * 100, c.jumpLevel() >= 0.2 ? "  [" + defaultKey(7).getTranslatedKeyMessage().getString() + "] ready" : "")};
                int y = 6;
                for (String s : lines) { if (s.isEmpty()) continue; g.fill(4, y - 2, 8 + mc.font.width(s), y + 9, 0x90000000); g.drawString(mc.font, s, 6, y, 0xFF7CFF9E, false); y += 11; }
            });
        }
    }

    @EventBusSubscriber(modid = ColossalCraft.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        private static boolean wasRiding;
        @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            boolean riding = mc.player.getVehicle() instanceof ColossusEntity;
            if (riding && !wasRiding) mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("Press " + PILOT.getTranslatedKeyMessage().getString() + " to leave"), false); // Shift lowers, it doesn't dismount
            wasRiding = riding;
            while (PILOT.consumeClick()) PacketDistributor.sendToServer(new Net.PilotAction(0));
            while (DISASSEMBLE.consumeClick()) if (mc.player.getVehicle() instanceof ColossusEntity) PacketDistributor.sendToServer(new Net.PilotAction(1));
            while (SCREEN.consumeClick()) if (mc.player.getVehicle() instanceof ColossusEntity) PacketDistributor.sendToServer(new Net.PilotAction(3));
            while (LOCO.consumeClick()) if (mc.player.getVehicle() instanceof ColossusEntity) PacketDistributor.sendToServer(new Net.PilotAction(4));
            while (HOVER.consumeClick()) if (mc.player.getVehicle() instanceof ColossusEntity) PacketDistributor.sendToServer(new Net.PilotAction(2));
            if (mc.player.getVehicle() instanceof ColossusEntity c && mc.screen == null && !DevShots.ON) {
                float fwd = (actionDown(c, 0) ? 1 : 0) - (actionDown(c, 1) ? 1 : 0), turn = (actionDown(c, 2) ? 1 : 0) - (actionDown(c, 3) ? 1 : 0);
                PacketDistributor.sendToServer(new Net.PilotInput(fwd, turn, actionDown(c, 4), actionDown(c, 5) || DESCEND.isDown(), actionDown(c, 6), actionDown(c, 7)));
            }
        }

        /** Right-clicking a Hull Core or Pilot Throne on a built Colossus. */
        @SubscribeEvent public static void click(InputEvent.InteractionKeyMappingTriggered e) {
            if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
            if ((e.isUseItem() && ClientHooks.tryColossus(false)) || (e.isAttack() && ClientHooks.tryColossus(true))) { e.setCanceled(true); e.setSwingHand(true); }
        }

        /** Third person (F5 back and front) pulls the camera out to fit the whole Colossus. */
        @SubscribeEvent public static void camera(CalculateDetachedCameraDistanceEvent e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getVehicle() instanceof ColossusEntity c && c.model != null)
                e.setDistance((float) Math.max(e.getDistance(), c.model.radius * 1.6 + 4));
        }
    }
}
