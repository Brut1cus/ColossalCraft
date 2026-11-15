package com.colossalcraft.client;

import com.colossalcraft.block.EngineMenu;
import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.tier.EngineTier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Furnace-like screen for engines: fuel slot on the left, energy bar and stats on the right. */
public class EngineScreen extends AbstractContainerScreen<EngineMenu> {
    public EngineScreen(EngineMenu m, Inventory inv, Component title) { super(m, inv, title); imageWidth = 176; imageHeight = 166; inventoryLabelY = 72; }

    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        g.fill(x, y, x + w - 1, y + 1, 0xFFFFFFFF); g.fill(x, y, x + 1, y + h - 1, 0xFFFFFFFF);
        g.fill(x + 1, y + h - 1, x + w, y + h, 0xFF555555); g.fill(x + w - 1, y + 1, x + w, y + h, 0xFF555555);
    }
    private static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737); g.fill(x, y, x + 17, y + 17, 0xFFFFFFFF); g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
    }

    @Override protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        int x = leftPos, y = topPos;
        panel(g, x, y, imageWidth, imageHeight);
        slot(g, x + 26, y + 36);
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) slot(g, x + 8 + c * 18, y + 84 + r * 18);
        for (int c = 0; c < 9; c++) slot(g, x + 8 + c * 18, y + 142);
        // flame under the slot, lit while there is fuel
        boolean lit = menu.slots.get(0).hasItem();
        for (int i = 0; i < 6; i++) g.fill(x + 30 + i * 2, y + 56 + Math.abs(3 - i), x + 31 + i * 2, y + 66, lit ? 0xFFFF8A1A : 0xFF6B6B6B);
        // energy bar
        EngineTier t = menu.tier;
        int bx = x + 54, by = y + 34, bw = 114, bh = 12;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xFF373737);
        g.fill(bx, by, bx + bw, by + bh, 0xFF1A1A1A);
        double frac = t == EngineTier.ZERO_POINT ? (menu.primed() ? 1 : 0) : t == null ? 0 : menu.energy() / (double) t.capacity;
        g.fill(bx, by, bx + (int) (bw * Math.min(1, frac)), by + bh, t == EngineTier.ZERO_POINT ? 0xFFB98CFF : 0xFF3AD7FF);
    }

    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        super.renderLabels(g, mx, my);
        EngineTier t = menu.tier; if (t == null) return;
        String stored = t == EngineTier.ZERO_POINT ? (menu.primed() ? "PRIMED - infinite" : "Not primed") : StructureScan.kilo(menu.energy()) + " / " + StructureScan.kilo(t.capacity) + " CJ";
        g.drawString(font, stored, 56, 24, 0xFF404040, false);
        g.drawString(font, "Output " + StructureScan.kilo(t.output) + " CJ/t", 56, 50, 0xFF404040, false);
        if (menu.multiblock) g.drawString(font, menu.formed() ? "Structure complete" : "Structure NOT complete", 56, 61, menu.formed() ? 0xFF2E7D32 : 0xFFB71C1C, false);
        g.drawString(font, "Fuel", 26, 26, 0xFF404040, false);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        renderTooltip(g, mx, my);
        if (menu.tier != null && mx >= leftPos + 26 && mx < leftPos + 42 && my >= topPos + 36 && my < topPos + 52 && !menu.slots.get(0).hasItem())
            g.renderTooltip(font, Component.literal("Fuel: " + menu.tier.fuelHint()), mx, my);
    }
}
