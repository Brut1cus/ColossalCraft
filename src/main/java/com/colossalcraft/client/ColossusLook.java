package com.colossalcraft.client;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.colossus.ColossusEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/**
 * Looking at blocks on a built Colossus: the block gets the usual black outline (the world block behind it doesn't),
 * a small label names it and the mod it comes from, and middle-click picks it like any block.
 */
@EventBusSubscriber(modid = ColossalCraft.MODID, value = Dist.CLIENT)
public final class ColossusLook {
    private ColossusLook() {}
    /** What the player looks at this frame (worked out once per frame). */
    private static ClientHooks.Hit hit;
    /**
     * Right after the game finds what the crosshair points at: if a block on a Colossus is nearer, point at it instead,
     * as its real block in the dock (the client has those chunks). Then F3, block-info mods, mining, using and picking
     * it all go through the normal game code.
     */
    public static void pickOverride() {
        Minecraft mc = Minecraft.getInstance();
        hit = mc.player == null || mc.level == null || mc.player.getVehicle() instanceof ColossusEntity ? null : ClientHooks.look();
        if (hit == null || hit.c().syncedDock() < 0) return;
        BlockPos dp = com.colossalcraft.colossus.ColossusDock.origin(hit.c().syncedDock()).offset(hit.pos());
        if (mc.level.getBlockState(dp).isAir()) return; // its chunk hasn't arrived yet
        mc.hitResult = new net.minecraft.world.phys.BlockHitResult(Vec3.atLowerCornerOf(dp).add(hit.at()), hit.face(), dp, false);
        mc.crosshairPickEntity = null;
    }
    /** Is the crosshair on a Colossus block that the client sees as its dock block? */
    public static boolean onDockBlock() {
        Minecraft mc = Minecraft.getInstance();
        return hit != null && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b && com.colossalcraft.colossus.ColossusDock.isShipPos(b.getBlockPos());
    }
    public static ClientHooks.Hit current() { return hit; }

    /** No outline on the world block hidden behind a Colossus block. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void highlight(RenderHighlightEvent.Block e) { if (current() != null) e.setCanceled(true); }

    @SubscribeEvent
    public static void outline(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        ClientHooks.Hit h = current();
        if (h == null || Minecraft.getInstance().options.hideGui) return;
        ColossusEntity c = h.c();
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = e.getCamera().getPosition();
        Integer seg = c.model.segOf.get(h.pos());
        Vec3 pos = c.getPosition(pt);
        double yaw = Mth.lerp(pt, c.prevYaw, c.yaw), tl = Mth.lerp(pt, c.prevTilt, c.tilt);
        Matrix4d m = c.segWorld(seg == null ? 0 : seg, pos, yaw, tl, c.currentArt());
        PoseStack ps = e.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        ps.mulPose(new Matrix4f(m));
        AABB box = c.model.shapeBounds.getOrDefault(h.pos(), new AABB(0, 0, 0, 1, 1, 1)).move(h.pos()).inflate(0.002);
        var buf = Minecraft.getInstance().renderBuffers().bufferSource();
        LevelRenderer.renderLineBox(ps, buf.getBuffer(RenderType.lines()), box, 0f, 0f, 0f, 0.4f);
        buf.endBatch(RenderType.lines());
        ps.popPose();
    }

    /** Middle-click on a Colossus block: pick it, like a block in the world. */
    @SubscribeEvent
    public static void pick(InputEvent.InteractionKeyMappingTriggered e) {
        if (!e.isPickBlock()) return;
        ClientHooks.Hit h = current();
        if (h == null || onDockBlock()) return; // a dock block: the game picks it itself
        e.setCanceled(true);
        Minecraft mc = Minecraft.getInstance();
        BlockState st = h.c().blocks.get(h.pos());
        if (st == null || mc.player == null || mc.gameMode == null) return;
        ItemStack stack = new ItemStack(st.getBlock().asItem());
        if (stack.isEmpty()) return;
        var inv = mc.player.getInventory();
        if (mc.player.getAbilities().instabuild) {
            inv.setPickedItem(stack);
            mc.gameMode.handleCreativeModeItemAdd(mc.player.getItemInHand(InteractionHand.MAIN_HAND), 36 + inv.selected);
        } else {
            int slot = inv.findSlotMatchingItem(stack);
            if (slot == -1) return;
            if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) inv.selected = slot; else mc.gameMode.handlePickItem(slot);
        }
    }

    /** The name of the block looked at, and its mod, near the top of the screen. */
    public static void label(net.minecraft.client.gui.GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null) return;
        if (onDockBlock() && (ModList.get().isLoaded("jade") || ModList.get().isLoaded("wthit"))) return; // they show it already
        ClientHooks.Hit h = current();
        if (h == null) return;
        BlockState st = h.c().blocks.get(h.pos());
        if (st == null) return;
        String name = st.getBlock().getName().getString();
        String ns = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace();
        String mod = ModList.get().getModContainerById(ns).map(mc2 -> mc2.getModInfo().getDisplayName()).orElse(ns);
        String sub = "on a Colossus";
        int w = Math.max(mc.font.width(name), Math.max(mc.font.width(mod), mc.font.width(sub))) + 30;
        int x = (g.guiWidth() - w) / 2, y = 4, hh = 36;
        g.fill(x, y, x + w, y + hh, 0xC0100010);
        g.fill(x, y, x + w, y + 1, 0xFF5000A0); g.fill(x, y + hh - 1, x + w, y + hh, 0xFF28005A);
        g.renderItem(new ItemStack(st.getBlock().asItem()), x + 4, y + 10);
        g.drawString(mc.font, name, x + 24, y + 4, 0xFFFFFFFF, false);
        g.drawString(mc.font, sub, x + 24, y + 14, 0xFFAAAAAA, false);
        g.drawString(mc.font, "§9§o" + mod, x + 24, y + 24, 0xFF5555FF, false);
    }
}
