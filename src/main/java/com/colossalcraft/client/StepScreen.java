package com.colossalcraft.client;

import com.colossalcraft.network.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Step Controller: a top-down map of the ground around the walker, its body, its feet and where each foot steps next.
 * Kept cheap on purpose: flat squares in a few shades of green, one accent colour, refreshed twice a second.
 */
public class StepScreen extends Screen {
    private static final int CELL = 8, MAP = 25 * CELL, W = MAP + 112, H = MAP + 30;
    private static final int BG = 0xFF06140A, FRAME = 0xFF2CFF7A, TXT = 0xFF9DFFBF, DIM = 0xFF4E8A63, ACC = 0xFFFFD45C, BAD = 0xFFE04848;
    private static final int[] GROUND = {0xFF0A2012, 0xFF10331C, 0xFF184A28, 0xFF226436, 0xFF2E8046}; // lower .. higher
    private static final int WATER = 0xFF0E2C3A, HAZARD = 0xFF5A1010, SLIPPERY = 0xFF27443F, NONE = 0xFF000000, WALL = 0xFF56645A;
    private CompoundTag d;
    private int age;

    public StepScreen(CompoundTag data) { super(Component.literal("Step Controller")); d = data; }
    public void update(CompoundTag data) { d = data; }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (++age % 10 != 0) return;
        PacketDistributor.sendToServer(d.getBoolean("Built") ? new Net.StepRequest(d.getInt("E"), 0) : new Net.StepRequest(-1, d.getLong("Pos")));
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        int l = (width - W) / 2, t = (height - H) / 2;
        g.fill(l - 2, t - 2, l + W + 2, t + H + 2, FRAME); g.fill(l, t, l + W, t + H, BG);
        g.drawString(font, "STEP CONTROLLER", l + 6, t + 5, TXT, false);
        int R = d.getInt("R"), size = 2 * R + 1, ox = l + 6, oy = t + 18;
        byte[] h = d.getByteArray("H"), k = d.getByteArray("K");
        if (h.length != size * size) return;
        // ground: one flat square per column
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int i = z * size + x, col;
            byte hv = h[i];
            if (hv == 127) col = NONE; else if (hv == 126) col = WALL;
            else if (k[i] == 2) col = HAZARD; else if (k[i] == 1) col = WATER; else if (k[i] == 3) col = SLIPPERY;
            else col = GROUND[Math.max(0, Math.min(4, 2 + (int) Math.floor(hv / 2.0)))]; // one shade per block of height
            g.fill(ox + x * CELL, oy + z * CELL, ox + x * CELL + CELL - (CELL > 4 ? 1 : 0), oy + z * CELL + CELL - 1, col);
        }
        // body: outlined squares
        for (int i : d.getIntArray("Body")) { int x = i % size, z = i / size; box(g, ox + x * CELL, oy + z * CELL, CELL - 1, DIM); }
        int cx = d.getInt("X") - R, cz = d.getInt("Z") - R;
        // feet: solid when planted, hollow while stepping
        for (Tag tg : d.getList("Feet", Tag.TAG_COMPOUND)) {
            CompoundTag f = (CompoundTag) tg;
            int sx = ox + (int) Math.round((f.getFloat("X") - cx) * CELL), sy = oy + (int) Math.round((f.getFloat("Z") - cz) * CELL);
            if (f.getByte("S") == 0) g.fill(sx - 3, sy - 3, sx + 3, sy + 3, TXT); else box(g, sx - 3, sy - 3, 6, TXT);
        }
        // next steps
        for (Tag tg : d.getList("Next", Tag.TAG_COMPOUND)) {
            CompoundTag f = (CompoundTag) tg;
            int sx = ox + (int) Math.round((f.getFloat("X") - cx) * CELL), sy = oy + (int) Math.round((f.getFloat("Z") - cz) * CELL);
            boolean ok = f.getBoolean("Ok");
            box(g, sx - 4, sy - 4, 8, ok ? ACC : BAD);
            if (!ok) { for (int q = -3; q <= 3; q++) { g.fill(sx + q, sy + q, sx + q + 1, sy + q + 1, BAD); g.fill(sx + q, sy - q, sx + q + 1, sy - q + 1, BAD); } }
        }
        // heading
        int mcx = ox + R * CELL + CELL / 2, mcy = oy + R * CELL + CELL / 2;
        float fx = d.getFloat("FX"), fz = d.getFloat("FZ");
        for (int s = 0; s < 3 * CELL; s++) g.fill(mcx + Math.round(fx * s), mcy + Math.round(fz * s), mcx + Math.round(fx * s) + 1, mcy + Math.round(fz * s) + 1, ACC);
        // side panel
        int px = ox + MAP + 8, py = oy;
        boolean built = d.getBoolean("Built"), pw = d.getBoolean("Pw");
        int ctrls = d.getInt("Ctrls");
        g.drawString(font, "N ↑", px, py, DIM, false); py += 14;
        String st = !built ? "Not built" : pw ? "PLANNING" : ctrls > 0 ? "NO POWER" : "NO CONTROLLER";
        g.drawString(font, st, px, py, !built ? DIM : pw ? TXT : BAD, false); py += 11;
        if (built && pw) { g.drawString(font, ctrls * 25 + " CJ/t", px, py, DIM, false); py += 11; }
        g.drawString(font, "Legs: " + d.getInt("Legs"), px, py, DIM, false); py += 16;
        py = legend(g, px, py, GROUND[3], "higher");
        py = legend(g, px, py, GROUND[1], "lower");
        py = legend(g, px, py, WATER, "water");
        py = legend(g, px, py, SLIPPERY, "slippery");
        py = legend(g, px, py, HAZARD, "danger");
        py = legend(g, px, py, WALL, "wall");
        py += 4;
        g.fill(px, py + 1, px + 6, py + 7, TXT); g.drawString(font, "foot down", px + 9, py, TXT, false); py += 11;
        box(g, px, py + 1, 6, ACC); g.drawString(font, "next step", px + 9, py, TXT, false); py += 11;
        box(g, px, py + 1, 6, BAD); g.drawString(font, "no footing", px + 9, py, TXT, false); py += 14;
        String status = d.getString("Status");
        if (!status.isEmpty()) for (var line : font.split(Component.literal(status), W - MAP - 18)) { g.drawString(font, line, px, py, DIM, false); py += 10; }
        super.render(g, mx, my, pt);
    }

    private int legend(GuiGraphics g, int x, int y, int col, String name) {
        g.fill(x, y + 1, x + 6, y + 7, col); g.drawString(font, name, x + 9, y, DIM, false); return y + 10;
    }
    private static void box(GuiGraphics g, int x, int y, int s, int col) {
        g.fill(x, y, x + s, y + 1, col); g.fill(x, y + s - 1, x + s, y + s, col); g.fill(x, y, x + 1, y + s, col); g.fill(x + s - 1, y, x + s, y + s, col);
    }
}
