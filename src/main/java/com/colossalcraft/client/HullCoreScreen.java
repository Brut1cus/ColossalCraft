package com.colossalcraft.client;

import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.network.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.Arrays;

/**
 * HULL-OS: the Hull Core's computer screen.
 * Overview (setup checklist + gauges), Joints (tree + detail panel), Power, Help. One big BUILD / PARK button.
 * Works on a Hull Core block (not built yet) and on a built Colossus (opened by right-clicking its Hull Core, or G while piloting).
 */
public class HullCoreScreen extends Screen {
    private static final int NAV = 84;
    private int W = 420, H = 246;
    private static final int BG = 0xF2060D08, PANEL = 0xFF0B1A10, FRAME = 0xFF2CFF7A, TXT = 0xFF8CFFAE, DIM = 0xFF3C8F55,
            HEAD = 0xFFE2FFEA, WARN = 0xFFFFD34A, ERR = 0xFFFF5A5A, OK = 0xFF46FF7E;
    private static final String[] MODE_NAMES = {"Locked", "Leg", "Leg", "Spin", "Sway", "Program"};
    private static final String[] MODE_HELP = {
            "Doesn't move. Use for rigid parts.",
            "Leg joint (hip or knee): the foot plants, IK bends the leg, ground grip pushes the Hull. Swing = joint limit.",
            "Hip of a leg (old setting).",
            "Turns with the Spin keys (Keys tab) like a wheel; with blades it is a rotor.",
            "Slow idle motion: tails, antennae, heads.",
            "Follows the keyframes you set in the Animate tab."};
    private static final String[] HULL_TABS = {"Overview", "Power", "Help"}, LOCO_TABS = {"Joints", "Gait", "Keys", "Footing"};
    private boolean loco() { return data.getBoolean("Loco"); }
    private String[] tabs() { return loco() ? LOCO_TABS : HULL_TABS; }
    private String cur() { return tabs()[Math.max(0, Math.min(tab, tabs().length - 1))]; }
    /** Keys tab: the action waiting for a key press, or -1. */
    private int binding = -1;
    private static final String[] VIEWS = {"ViewX", "ViewZ", "ViewTop"}, VIEW_NAMES = {"Side X", "Side Z", "Top"};
    private static final int CORE_COL = 0xFF2CFF7A, JOINT_COL = 0xFFFFD34A, BAD_COL = 0xFFFF4A4A, SEL_COL = 0xFFFFFFFF;
    private static final int[] PART_COLS = {0xFF3AA0FF, 0xFF2A7FD9, 0xFF5BC0FF, 0xFF1E66B3, 0xFF4C8DFF, 0xFF77B8FF};
    private int view = 0;
    /** 3D view camera: degrees, zoom multiplier, pan in pixels. */
    private double camYaw = 35, camPitch = 25, camZoom = 1, panX, panY;
    private int[] ptsCache; private int ptsHash;
    private int cvX, cvY, cvW, cvH;
    private final List<int[]> markers = new ArrayList<>(); // joint id, screen x, screen y

    public final BlockPos pos;
    private CompoundTag data;
    private int tab = 0, scroll = 0, selected = -1;
    private record Row(String text, int color, int joint, int indent) {}
    private final List<Row> treeRows = new ArrayList<>();
    private record Tip(int x0, int y0, int x1, int y1, String text) {}
    private final List<Tip> tips = new ArrayList<>();

    public HullCoreScreen(BlockPos pos, CompoundTag data) { super(Component.literal("Hull Core")); this.pos = pos; this.data = data; }

    public boolean matches(BlockPos p, CompoundTag d) { return d.getBoolean("Loco") == loco() && (built() ? d.getInt("Entity") == entity() : p.equals(pos) && !d.getBoolean("Built")); }
    public void setData(CompoundTag d) { data = d; rebuild(); }
    public void setTab(int t) { tab = t; scroll = 0; rebuild(); }
    public void setView(int v) { view = v; preset(v); rebuild(); }
    /** Select the lowest-numbered joint that has a foot below it (dev screenshots). */
    public void selectLegJoint() { for (Tag t : list("Joints")) if (((CompoundTag) t).getDouble("Share") > 0 && !((CompoundTag) t).getBoolean("Ball")) { selected = ((CompoundTag) t).getInt("Id"); rebuild(); return; } }
    public void turnView(double dyaw, double dpitch, double zoom) { camYaw += dyaw; camPitch = Math.max(-90, Math.min(90, camPitch + dpitch)); camZoom *= zoom; }
    private void preset(int v) { camYaw = v == 1 ? 90 : 0; camPitch = v == 2 ? 90 : 0; camZoom = 1; panX = panY = 0; }
    private boolean built() { return data.getBoolean("Built"); }
    private int entity() { return built() ? data.getInt("Entity") : -1; }
    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }
    private void send(int action, int joint, int value) { PacketDistributor.sendToServer(new Net.HullAction(pos, entity(), action, joint, value)); }
    private ListTag list(String k) { return data.getList(k, Tag.TAG_COMPOUND); }
    private ListTag strings(String k) { return data.getList(k, Tag.TAG_STRING); }

    @Override protected void init() { W = Math.min(420, width - 6); H = Math.min(246, height - 6); rebuild(); }

    // ------------------------------------------------------------------ widgets
    private void rebuild() {
        clearWidgets(); buildTree();
        int l = left(), t = top();
        String[] TABS = tabs();
        for (int i = 0; i < TABS.length; i++) {
            int ti = i;
            Button b = Button.builder(Component.literal(TABS[i]), x -> { tab = ti; scroll = 0; rebuild(); }).bounds(l + 6, t + 28 + i * 24, NAV - 10, 20).build();
            b.active = tab != i; addRenderableWidget(b);
        }
        if (!loco() && data.getInt("Thrusters") > 0) { // hover needs thrusters; the Locomotion Controller only sets up legs
            Button hover = Button.builder(Component.literal("Hover: " + (data.getBoolean("Hover") ? "ON" : "OFF")), x -> send(3, -1, 0)).bounds(l + 6, t + H - 76, NAV - 10, 18).build();
            hover.setTooltip(Tooltip.create(Component.literal("Hover assist: thrusters hold height on their own when you don't press up/down.")));
            addRenderableWidget(hover);
        }
        if (!built()) {
            Button rescan = Button.builder(Component.literal("Rescan"), x -> send(0, -1, 0)).bounds(l + 6, t + H - 54, NAV - 10, 18).build();
            rescan.setTooltip(Tooltip.create(Component.literal("Look at the blocks again after you change the build.")));
            addRenderableWidget(rescan);
        }
        // big action button
        boolean ok = strings("Errors").isEmpty();
        Button act;
        if (built()) {
            act = Button.builder(Component.literal("PARK  (back to blocks)"), x -> send(5, -1, 0)).bounds(l + W - 156, t + H - 28, 150, 22).build();
            act.setTooltip(Tooltip.create(Component.literal("Turns the Colossus back into normal blocks where it stands (lined up to the grid). Your joint setup is kept in the Hull Core.")));
        } else {
            act = Button.builder(Component.literal(ok ? "BUILD COLOSSUS" : "CAN'T BUILD YET"), x -> send(4, -1, 0)).bounds(l + W - 156, t + H - 28, 150, 22).build();
            act.active = ok;
            act.setTooltip(Tooltip.create(Component.literal(ok ? "Turn these blocks into one moving Colossus. Scaffolding stays behind." : strings("Errors").getString(0))));
        }
        if (!loco()) addRenderableWidget(act);

        if (cur().equals("Gait")) gaitWidgets(l + NAV + 8, t + 30);
        if (cur().equals("Keys")) keyWidgets(l + NAV + 8, t + 30);
        if (cur().equals("Footing")) {
            boolean on = data.getBoolean("Smart");
            Button fb = Button.builder(Component.literal("Smart footing: " + (on ? "ON" : "OFF")), z -> send(17, -1, 0)).bounds(l + W - 158, t + 28, 150, 16).build();
            fb.setTooltip(Tooltip.create(Component.literal("ON: before every step the legs look at the ground and put each foot on safe, level footing (uses " + (int) com.colossalcraft.colossus.StructureModel.SMART_FOOTING_POWER + " CJ/t). OFF: feet land wherever the gait says.")));
            addRenderableWidget(fb);
        }
        if (cur().equals("Joints")) {
            jointWidgets(l + NAV + 8 + 168, t + 30);
        }
    }

    private static final int[] MODE_CYCLE = {0, 1, 3, 4};
    /** Where the range-of-motion value is drawn next to its arrows (set by jointWidgets). */
    private int romX = -1, romY, romW; private String romText = "", romTipText = ""; private boolean romWarn;

    /** Only two small controls: what the joint does, and how far it may turn. */
    private void jointWidgets(int x, int y) {
        romX = -1;
        CompoundTag j = joint(selected);
        if (j == null) return;
        int mode = j.getInt("Mode"); if (mode == 2) mode = 1; if (mode == 5) mode = 0;
        boolean poweredBall = j.getBoolean("Ball") && j.getBoolean("Powered");
        int[] cyc = poweredBall ? new int[]{0, 1, 4} : MODE_CYCLE; // powered ball: ankle (Locked), hip (Leg), shoulder (Sway)
        int idx = 0; for (int i = 0; i < cyc.length; i++) if (cyc[i] == mode) idx = i;
        int next = cyc[(idx + 1) % cyc.length];
        int by = top() + H - 52, w = (W - NAV - 8 - 174) / 2 - 2;
        boolean ball = j.getBoolean("Ball") && !poweredBall;
        Button mb = Button.builder(Component.literal(ball ? "Mode: auto" : "Mode: " + MODE_NAMES[mode]), z -> send(1, selected, next)).bounds(x, by, w, 14).build();
        if (poweredBall) mb = Button.builder(Component.literal("Mode: " + (mode == 1 ? "Hip (Leg)" : mode == 4 ? "Shoulder (Sway)" : "Ankle (Locked)")), z -> send(1, selected, next)).bounds(x, by, w, 14).build();
        mb.setTooltip(Tooltip.create(Component.literal(poweredBall ? "Powered ball joint. Ankle (Locked): keeps the foot flat and tilts it to slopes. Hip (Leg): the leg swings forward/back and sideways under IK. Shoulder (Sway): swings an arm in time with the steps."
                : ball ? "Ball joints level the part below them on their own: no mode to set."
                : MODE_HELP[mode] + "  (click for " + MODE_NAMES[next] + ")\n\nLocked: rigid, never moves.\nLeg: hips and knees of walking legs.\nSpin: wheels and rotors, turned by the Spin keys.\nSway: gentle idle motion.")));
        mb.active = !ball;
        addRenderableWidget(mb);
        // range of motion: the value is shown in the panel; up/down arrows change it (shift = bigger steps), never past the joint's cap
        int amp = j.getInt("Amp"), capAmp = Math.max(1, j.getInt("RomCap") / 2);
        int fold = j.contains("Fold") ? j.getInt("Fold") : 180;
        String romTip = "Range of motion: how far this joint may turn either way (legs bend within this). Shift-click for steps of 10\u00B0."
                + (fold < 180 ? " Its limb hits the rest of the Colossus after \u00B1" + fold + "\u00B0, so it never folds further than that (yellow = set past that)." : " It can fold all the way round without hitting anything.")
                + (j.getBoolean("Ball") ? " This ball joint's shape allows up to \u00B1" + capAmp * 2 + "\u00B0 (a cube turns \u00B190\u00B0, a flat 2x1x2 \u00B145\u00B0)." : "");
        Button up = Button.builder(Component.literal("\u25B2"), z -> send(2, selected, Math.min(capAmp, amp + (hasShiftDown() ? 5 : 1)))).bounds(x + w + 4 + w - 28, by, 13, 14).build();
        Button dn = Button.builder(Component.literal("\u25BC"), z -> send(2, selected, Math.max(1, amp - (hasShiftDown() ? 5 : 1)))).bounds(x + w + 4 + w - 14, by, 13, 14).build();
        up.active = amp < capAmp; dn.active = amp > 1;
        up.setTooltip(Tooltip.create(Component.literal(romTip))); dn.setTooltip(Tooltip.create(Component.literal(romTip)));
        addRenderableWidget(up); addRenderableWidget(dn);
        romX = x + w + 4; romY = by; romW = w - 30; romText = "\u00B1" + amp * 2 + "\u00B0"; romWarn = amp * 2 > fold; romTipText = romTip;
        if (data.getInt("GaitMode") == 4 && j.getBoolean("LegHip")) {
            int grp = j.getInt("Group");
            Button gb = Button.builder(Component.literal("Step group " + (grp + 1)), z -> send(15, selected, grp + 1)).bounds(x, by - 16, w * 2 + 4, 14).build();
            gb.setTooltip(Tooltip.create(Component.literal("Custom gait: legs in the same group step together. Groups take turns 1, 2, 3, 4.")));
            addRenderableWidget(gb);
        }
    }

    // ------------------------------------------------------------------ Gait & Keys (Locomotion Controller)
    private void gaitWidgets(int x, int y) {
        if (data.getInt("LegCount") == 0) return; // no IK legs: nothing to set (wheels and spinners use the Spin keys)
        int ik = data.getInt("IKMode"), gm = data.getInt("GaitMode");
        Button b1 = Button.builder(Component.literal("IK: " + com.colossalcraft.colossus.StructureModel.IK_MODES[ik]), z -> send(11, -1, ik + 1)).bounds(x, y + 10, 156, 15).build();
        b1.setTooltip(Tooltip.create(Component.literal("Auto: picks per leg (2 joints: Analytic 2-link, 3 joints: Analytic 3-link, more: FABRIK, mixed axes: CCD). Analytic 2-link: exact hip+knee. Analytic 3-link: hip, knee and ankle with the foot kept flat. FABRIK: any number of joints in the leg's plane. CCD: any joint axes.")));
        addRenderableWidget(b1);
        Button b2 = Button.builder(Component.literal("Gait: " + com.colossalcraft.colossus.StructureModel.GAIT_MODES[gm]), z -> send(12, -1, gm + 1)).bounds(x + 160, y + 10, 156, 15).build();
        b2.setTooltip(Tooltip.create(Component.literal("Biped: left/right. Digitigrade: knees bend backwards like a bird. Quadruped: diagonal pairs step together. Spider: every other leg round the body. Custom: set each leg's step group in Joints. Triped: three legs (or groups of three) step in turn round the body. Wave: one leg at a time, slow and steady.")));
        addRenderableWidget(b2);
        int gs = (int) Math.round(data.getDouble("GaitSpeed") * 100), st = (int) Math.round(data.getDouble("GaitStride") * 100),
                lf = (int) Math.round(data.getDouble("GaitLift") * 100), du = (int) Math.round(data.getDouble("GaitDuty") * 100);
        boolean auto = data.getBoolean("GaitDutyAuto");
        // label | - | + in a 2x2 grid
        stepper(x, y + 30, z -> send(9, -1, gs - (gs > 50 ? 10 : 5)), z -> send(9, -1, gs + (gs >= 50 ? 10 : 5)), "Step cycles per second. Faster steps = faster walking.");
        stepper(x + 160, y + 30, z -> send(16, 0, st - 5), z -> send(16, 0, st + 5), "Stride: how far each foot travels per step, as a share of the leg's reach. Longer = faster, but needs more balance.");
        stepper(x, y + 46, z -> send(16, 1, lf - 5), z -> send(16, 1, lf + 5), "Step height: how high a foot lifts, as a share of the leg's length. Higher clears steps and rough ground.");
        stepper(x + 160, y + 46, z -> send(16, 2, auto ? du - 5 : du - 5 < 50 ? 0 : du - 5), z -> send(16, 2, Math.max(50, du) + 5),
                "Stance: share of each step a foot stays down. More = steadier, slower. Below 50% goes back to the gait type's default.");
    }
    private void stepper(int x, int y, Button.OnPress minus, Button.OnPress plus, String tip) {
        Button a = Button.builder(Component.literal("-"), minus).bounds(x + 120, y, 16, 13).build(); a.setTooltip(Tooltip.create(Component.literal(tip)));
        Button b = Button.builder(Component.literal("+"), plus).bounds(x + 138, y, 16, 13).build(); b.setTooltip(Tooltip.create(Component.literal(tip)));
        addRenderableWidget(a); addRenderableWidget(b);
    }

    // blueprint colours
    // same greens and yellows as the Footing map
    private static final int BP_BG = 0xFF0A1C10, BP_GRID = 0xFF12301C, BP_LINE = 0xFF8CFFAE, BP_DIM = 0xFF3C8F55, BP_ACC = 0xFFFFD45C;

    /** Gait tab: settings, the hull's shape from above with a step chart, and a side view of every leg walking (blueprint style). */
    private void gait(GuiGraphics g, int x, int y) {
        g.drawString(font, "GAIT", x, y, DIM, false);
        if (data.getInt("LegCount") == 0) {
            int ly = y + 14;
            String msg = data.getInt("Wheels") > 0
                    ? "Gait is off: this Colossus has no walking legs. Its " + data.getInt("Wheels") + " Spin joint" + (data.getInt("Wheels") == 1 ? "" : "s") + " work like wheels: they turn with the Spin keys (see Keys) and drive it along the ground. Brake stops them."
                    : "Gait is off: no walking legs. Set hip and knee joints to Leg in Joints (they need Servo Motors), and the gait settings appear here.";
            for (var ln : font.split(Component.literal(msg), W - NAV - 20)) { g.drawString(font, ln, x, ly, TXT, false); ly += 10; }
            return;
        }
        double speed = data.getDouble("GaitSpeed"), strideF = data.getDouble("GaitStride"), liftF = data.getDouble("GaitLift"), duty = data.getDouble("GaitDuty");
        CompoundTag geo = data.getCompound("LegGeo");
        ListTag legs = geo.getList("Legs", Tag.TAG_COMPOUND);
        // the same numbers the walker uses
        double reachH = Double.MAX_VALUE, legLen = 1;
        for (Tag tg : legs) { CompoundTag lt = (CompoundTag) tg; reachH = Math.min(reachH, lt.getFloat("Len") * Math.sin(Math.min(lt.getFloat("Lim"), Math.toRadians(60)))); legLen = Math.max(legLen, lt.getFloat("Len")); }
        if (legs.isEmpty()) reachH = 1;
        double cap = data.contains("PowerCap") ? data.getDouble("PowerCap") : 99, setSpeed = speed;
        speed = Math.min(speed, cap); // the engines decide how fast the legs can really step
        double stride = Math.min(1.6 * reachH, strideF * 1.2 * reachH), cycle = 1 / Math.max(0.05, speed), v = stride / (duty * cycle);
        g.drawString(font, setSpeed > cap + 1e-6 ? String.format("Speed %.2f/s (power %.2f)", setSpeed, cap) : String.format("Speed %.2f /s", speed), x, y + 33, setSpeed > cap + 1e-6 ? WARN : TXT, false);
        if (setSpeed > cap + 1e-6) tips.add(new Tip(x, y + 32, x + 118, y + 42, "The engines can only feed the leg motors for " + String.format("%.2f", cap) + " steps a second. Add engines (or fewer gyros/controllers) to step faster."));
        g.drawString(font, String.format("Stride %.0f%% (%.1f b)", strideF * 100, stride), x + 160, y + 33, TXT, false);
        g.drawString(font, String.format("Lift %.0f%% (%.1f b)", liftF * 100, liftF * legLen), x, y + 49, TXT, false);
        g.drawString(font, String.format("Stance %.0f%%%s", duty * 100, data.getBoolean("GaitDutyAuto") ? " auto" : ""), x + 160, y + 49, TXT, false);

        int py = y + 64, ph = top() + H - 40 - py, fullW = W - NAV - 16;
        int lw = 104, rx = x + lw + 4, rw = fullW - lw - 4;
        if (legs.isEmpty()) { bp(g, x, py, fullW, ph); g.drawString(font, "No legs: set hips/knees to Leg.", x + 6, py + 6, BP_LINE, false); return; }
        int groups = 1; for (Tag tg : legs) groups = Math.max(groups, ((CompoundTag) tg).getInt("Group") + 1);
        double phase = (System.currentTimeMillis() / 1000.0 * speed) % 1.0;
        List<CompoundTag> order = new ArrayList<>(); for (Tag tg : legs) order.add((CompoundTag) tg);
        // where each foot is in its step right now (forward offset, lift) — the same motion the walker makes
        double[][] fm = new double[order.size()][];
        for (int i = 0; i < order.size(); i++) {
            double p2 = (phase + (double) order.get(i).getInt("Group") / groups) % 1.0;
            if (p2 < duty) fm[i] = new double[]{stride / 2 - stride * (p2 / duty), 0, 1};
            else { double u = (p2 - duty) / (1 - duty); fm[i] = new double[]{-stride / 2 + stride * u, liftF * legLen * Math.sin(Math.PI * u), 0}; }
        }

        // ---- left: the hull from above (forward is up), hips and feet; step chart below
        int th = Math.max(40, ph * 3 / 5);
        bp(g, x, py, lw, th);
        g.drawString(font, "HULL", x + 3, py + 2, BP_DIM, false);
        int[] topC = geo.getIntArray("Top");
        double f0 = 1e9, f1 = -1e9, s0 = 1e9, s1 = -1e9;
        for (int c : topC) { double a = ua(c), b = ub(c); f0 = Math.min(f0, a); f1 = Math.max(f1, a); s0 = Math.min(s0, b); s1 = Math.max(s1, b); }
        for (CompoundTag lt : order) { CompoundTag foot = lt.getList("P", Tag.TAG_COMPOUND).getCompound(lt.getList("P", Tag.TAG_COMPOUND).size() - 1);
            f0 = Math.min(f0, foot.getFloat("F") - stride / 2); f1 = Math.max(f1, foot.getFloat("F") + stride / 2); s0 = Math.min(s0, foot.getFloat("S")); s1 = Math.max(s1, foot.getFloat("S")); }
        double tsc = Math.min((lw - 10) / Math.max(1, s1 - s0 + 1), (th - 16) / Math.max(1, f1 - f0 + 1));
        int tcx = x + lw / 2, tcy = py + 10 + (th - 10) / 2;
        double midF = (f0 + f1) / 2, midS = (s0 + s1) / 2;
        int cell = Math.max(1, (int) Math.floor(tsc * 0.5));
        g.enableScissor(x, py, x + lw, py + th);
        for (int c : topC) { int sx = tcx + (int) Math.round((ub(c) - midS) * tsc), sy = tcy - (int) Math.round((ua(c) - midF) * tsc); g.fill(sx - cell, sy - cell, sx + cell, sy + cell, BP_GRID2); }
        for (int i = 0; i < order.size(); i++) {
            ListTag pl = order.get(i).getList("P", Tag.TAG_COMPOUND);
            CompoundTag hip = pl.getCompound(0), foot = pl.getCompound(pl.size() - 1);
            int hx = tcx + (int) Math.round((hip.getFloat("S") - midS) * tsc), hy = tcy - (int) Math.round((hip.getFloat("F") - midF) * tsc);
            int fx = tcx + (int) Math.round((foot.getFloat("S") - midS) * tsc), fy = tcy - (int) Math.round((foot.getFloat("F") + fm[i][0] - midF) * tsc);
            line(g, hx, hy, fx, fy, BP_DIM);
            rect(g, hx - 1, hy - 1, hx + 2, hy + 2, BP_LINE);
            if (fm[i][2] > 0) g.fill(fx - 2, fy - 2, fx + 2, fy + 2, BP_ACC); else rect(g, fx - 2, fy - 2, fx + 2, fy + 2, BP_LINE);
        }
        g.disableScissor();
        // step chart: one row per leg, bar = foot down, cursor = now
        int cy0 = py + th + 3, chH = ph - th - 3;
        bp(g, x, cy0, lw, chH);
        int rowH = Math.max(4, Math.min(10, (chH - 4) / Math.max(1, order.size()))), bx0 = x + 18, bw = lw - 22;
        for (int i = 0; i < order.size() && 2 + (i + 1) * rowH <= chH; i++) {
            CompoundTag lt = order.get(i);
            int ry = cy0 + 2 + i * rowH;
            g.drawString(font, legName(lt), x + 2, ry + rowH / 2 - 4, BP_LINE, false);
            double off = (double) lt.getInt("Group") / groups;
            int runStart = -1;
            for (int sx = 0; sx <= bw; sx++) { // draw each "foot down" run as one bar
                boolean down = sx < bw && ((double) sx / bw + off) % 1.0 < duty;
                if (down && runStart < 0) runStart = sx;
                if (!down && runStart >= 0) { g.fill(bx0 + runStart, ry + 1, bx0 + sx, ry + rowH - 1, BP_LINE); runStart = -1; }
            }
        }
        int cx = bx0 + (int) Math.round(phase * bw);
        g.fill(cx, cy0 + 1, cx + 1, cy0 + chH - 1, BP_ACC);

        // ---- right: every leg from the side, walking (up to 6)
        int n = Math.min(6, order.size()), cols = n <= 2 ? n : n <= 4 ? 2 : 3, rows = (n + cols - 1) / cols;
        int pw = (rw - (cols - 1) * 3) / cols, phh = (ph - (rows - 1) * 3) / rows;
        // one scale for all panels: a leg plus a stride of travel either side
        double maxSpan = 1;
        for (CompoundTag lt : order) { ListTag pl = lt.getList("P", Tag.TAG_COMPOUND); double hu = pl.getCompound(0).getFloat("U"), fu = pl.getCompound(pl.size() - 1).getFloat("U"); maxSpan = Math.max(maxSpan, hu - fu + 2.5); }
        double sc = Math.min(phh / (maxSpan + liftF * legLen), pw / (stride + legLen + 3));
        int[] sideC = geo.getIntArray("Side");
        for (int i = 0; i < n; i++) {
            CompoundTag lt = order.get(i);
            int qx = rx + (i % cols) * (pw + 3), qy = py + (i / cols) * (phh + 3);
            bp(g, qx, qy, pw, phh);
            ListTag pl = lt.getList("P", Tag.TAG_COMPOUND);
            int k = pl.size(); if (k < 3) continue;
            double[][] p = new double[k][2];
            for (int j = 0; j < k; j++) { CompoundTag pt = pl.getCompound(j); p[j][0] = pt.getFloat("F"); p[j][1] = pt.getFloat("U"); }
            // panel frame: the hip near the top middle, the ground line at the bottom
            double ox = qx + pw / 2.0 - p[0][0] * sc, groundU = p[k - 1][1], oy = qy + phh - 6 + groundU * sc;
            g.enableScissor(qx, qy, qx + pw, qy + phh);
            int gy = (int) Math.round(oy - groundU * sc);
            g.fill(qx + 1, gy, qx + pw - 1, gy + 1, BP_LINE);
            double travel = (System.currentTimeMillis() / 1000.0 * v) % 1.0;
            for (double tx = -travel - 20; tx < 20; tx += 1) { int sx = (int) Math.round(qx + pw / 2.0 + tx * sc); if (sx > qx && sx < qx + pw) g.fill(sx, gy + 1, sx + 1, gy + 3, BP_DIM); }
            int hc = Math.max(1, (int) Math.floor(sc * 0.5));
            for (int c : sideC) { int sx = (int) Math.round(ox + ua(c) * sc), sy = (int) Math.round(oy - ub(c) * sc); if (sx > qx - hc && sx < qx + pw + hc && sy > qy - hc && sy < qy + phh + hc) g.fill(sx - hc, sy - hc, sx + hc, sy + hc, BP_GRID2); }
            // solve the leg in 2D for this moment of its step
            double[] tgt = {p[k - 2][0] + fm[i][0], p[k - 2][1] + fm[i][1]};
            int links = k - 2; // joints in the chain; chain[links] is the ankle/foot point
            double[][] chain = solveLeg(i, p, k, tgt, data.getInt("GaitMode") == com.colossalcraft.colossus.StructureModel.GAIT_DIGI ? -1 : 1, lt.getIntArray("LimLo"), lt.getIntArray("LimHi"));
            double[] delta = new double[links];
            for (int j = 0; j < links; j++) delta[j] = Math.atan2(chain[j + 1][1] - chain[j][1], chain[j + 1][0] - chain[j][0]) - Math.atan2(p[j + 1][1] - p[j][1], p[j + 1][0] - p[j][0]);
            boolean near = pl.getCompound(0).getFloat("S") >= 0;
            // the leg's blocks: each turns with its joint; under a ball ankle the foot only moves (stays level)
            for (int c : lt.getIntArray("B")) {
                int tag = ut(c); double bf = ua(c), bu = ub(c), nf, nu, rot;
                if (tag < 0 || links == 0) { nf = bf + chain[links][0] - p[links][0]; nu = bu + chain[links][1] - p[links][1]; rot = 0; }
                else { int j = Math.min(tag, links - 1); double cs = Math.cos(delta[j]), sn = Math.sin(delta[j]), rf = bf - p[j][0], ru = bu - p[j][1];
                       nf = chain[j][0] + rf * cs - ru * sn; nu = chain[j][1] + rf * sn + ru * cs; rot = delta[j]; }
                square(g, ox + nf * sc, oy - nu * sc, sc * 0.5, -rot, BP_LINE);
            }
            for (int j = 0; j < links; j++) { int jx = (int) Math.round(ox + chain[j][0] * sc), jy = (int) Math.round(oy - chain[j][1] * sc); g.fill(jx - 2, jy - 2, jx + 2, jy + 2, BP_ACC); }
            if (lt.getBoolean("Ankle")) { int jx = (int) Math.round(ox + chain[links][0] * sc), jy = (int) Math.round(oy - chain[links][1] * sc); rect(g, jx - 2, jy - 2, jx + 3, jy + 3, BP_ACC); }
            g.disableScissor();
            g.drawString(font, legName(lt) + (near ? "" : " (far)"), qx + 3, qy + 2, fm[i][2] > 0 ? BP_ACC : BP_DIM, false);
        }
        if (order.size() > n) g.drawString(font, "+" + (order.size() - n) + " more legs", rx + 2, py + ph - 9, BP_DIM, false);
        g.drawString(font, String.format("%.1f m/s", v), x + lw - font.width(String.format("%.1f m/s", v)) - 3, py + 2, BP_ACC, false);
    }
    private static final int BP_GRID2 = 0xFF1E4A2A;
    private static double ua(int c) { return ((c & 511) - 256) / 2.0; }
    private static double ub(int c) { return (((c >> 9) & 511) - 256) / 2.0; }
    private static int ut(int c) { return (c >> 18) - 2; }
    private static String legName(CompoundTag lt) {
        CompoundTag h = lt.getList("P", Tag.TAG_COMPOUND).getCompound(0);
        return (h.getFloat("S") < 0 ? "L" : "R") + (h.getFloat("F") >= 0 ? "f" : "b");
    }
    /** Plan panel: dark green with a faint grid and a thin frame. */
    private static void bp(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BP_BG);
        for (int gx = x + 10; gx < x + w; gx += 10) g.fill(gx, y, gx + 1, y + h, BP_GRID);
        for (int gy = y + 10; gy < y + h; gy += 10) g.fill(x, gy, x + w, gy + 1, BP_GRID);
        rect(g, x, y, x + w, y + h, BP_DIM);
    }
    /** An outlined square turned by an angle: drawn with the pose stack, so it's 4 quads no matter how it's turned. */
    private static void square(GuiGraphics g, double cx, double cy, double half, double angle, int col) {
        var ps = g.pose(); ps.pushPose();
        ps.translate(cx, cy, 0);
        ps.mulPose(com.mojang.math.Axis.ZP.rotation((float) angle));
        float hw = (float) half; int hi = Math.max(1, Math.round(hw));
        g.fill(-hi, -hi, hi, -hi + 1, col); g.fill(-hi, hi - 1, hi, hi, col); g.fill(-hi, -hi, -hi + 1, hi, col); g.fill(hi - 1, -hi, hi, hi, col);
        ps.popPose();
    }

    private static void rect(GuiGraphics g, int x0, int y0, int x1, int y1, int col) {
        if (x1 < x0) { int t = x0; x0 = x1; x1 = t; } if (y1 < y0) { int t = y0; y0 = y1; y1 = t; }
        g.fill(x0, y0, x1, y0 + 1, col); g.fill(x0, y1 - 1, x1, y1, col); g.fill(x0, y0, x0 + 1, y1, col); g.fill(x1 - 1, y0, x1, y1, col);
    }
    /** A 1-pixel line made of small steps (cheap: no textures, no 3D). */
    private static void line(GuiGraphics g, double x0, double y0, double x1, double y1, int col) {
        int n = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int i = 0; i <= n; i++) { double t = n == 0 ? 0 : (double) i / n; int x = (int) Math.round(x0 + (x1 - x0) * t), y = (int) Math.round(y0 + (y1 - y0) * t); g.fill(x, y, x + 1, y + 1, col); }
    }
    /** Last solved pose of each leg in the preview: the next frame starts from it, so knees never jump to the other side. */
    private final Map<Integer, double[][]> legPose = new HashMap<>();
    /**
     * One leg of the preview, solved the way the walker solves it: reach for the foot target starting from last frame's pose,
     * knees bent to the gait's side, then every joint held inside its real limit (range of motion, no folding into the Colossus).
     * p = rest points (hip ... ankle, foot); returns the joint points hip ... ankle.
     */
    private double[][] solveLeg(int leg, double[][] p, int k, double[] tgt, int kneeDir, int[] loTenths, int[] hiTenths) {
        int n = k - 1, links = n - 1;
        double[][] c = legPose.get(leg);
        if (c == null || c.length != n || Math.abs(c[0][0] - p[0][0]) > 1e-6 || Math.abs(c[0][1] - p[0][1]) > 1e-6) {
            c = new double[n][]; for (int j = 0; j < n; j++) c[j] = p[j].clone();
            for (int j = 1; j < n - 1; j++) c[j][0] += kneeDir * 0.12 * Math.hypot(p[j][0] - p[j - 1][0], p[j][1] - p[j - 1][1]); // start with the knees bent the right way
        } else { double[][] cc = new double[n][]; for (int j = 0; j < n; j++) cc[j] = c[j].clone(); c = cc; }
        fabrik2(c, tgt);
        if (n == 3) { // hip, knee, ankle: keep the knee on its side of the hip-ankle line (a straight leg may pass through, never flip)
            double ex = c[2][0] - c[0][0], ey = c[2][1] - c[0][1], kx = c[1][0] - c[0][0], ky = c[1][1] - c[0][1];
            double cr = ex * ky - ey * kx, el = Math.max(1e-9, ex * ex + ey * ey);
            if (cr * kneeDir < -1e-4 * el) { double t = (kx * ex + ky * ey) / el, fx = c[0][0] + ex * t, fy = c[0][1] + ey * t; c[1] = new double[]{2 * fx - c[1][0], 2 * fy - c[1][1]}; }
        }
        // joint angles against the rest pose, each held inside its limit; then the pose is rebuilt from the hip down
        double[] restA = new double[links], len = new double[links];
        for (int j = 0; j < links; j++) { restA[j] = Math.atan2(p[j + 1][1] - p[j][1], p[j + 1][0] - p[j][0]); len[j] = Math.hypot(p[j + 1][0] - p[j][0], p[j + 1][1] - p[j][1]); }
        double prevDelta = 0;
        double[][] out = new double[n][]; out[0] = p[0].clone();
        for (int j = 0; j < links; j++) {
            double d = wrap(Math.atan2(c[j + 1][1] - c[j][1], c[j + 1][0] - c[j][0]) - restA[j]);
            double rel = wrap(d - prevDelta);
            double lo = j < loTenths.length ? Math.toRadians(loTenths[j] / 10.0) : Math.PI, hi = j < hiTenths.length ? Math.toRadians(hiTenths[j] / 10.0) : Math.PI;
            rel = Math.max(-lo, Math.min(hi, rel));
            prevDelta += rel;
            double a = restA[j] + prevDelta;
            out[j + 1] = new double[]{out[j][0] + Math.cos(a) * len[j], out[j][1] + Math.sin(a) * len[j]};
        }
        legPose.put(leg, out);
        return out;
    }
    private static double wrap(double a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }
    /** 2D FABRIK: chain[0] stays fixed, the last point reaches for the target (starting from the pose it is given). */
    private static void fabrik2(double[][] c, double[] t) {
        int k = c.length; if (k < 2) return;
        double[] len = new double[k - 1]; double total = 0;
        for (int i = 0; i < k - 1; i++) { len[i] = Math.hypot(c[i + 1][0] - c[i][0], c[i + 1][1] - c[i][1]); total += len[i]; }
        double[] base = c[0].clone();
        double dd = Math.hypot(t[0] - base[0], t[1] - base[1]);
        if (dd > total) t = new double[]{base[0] + (t[0] - base[0]) / dd * total * 0.999, base[1] + (t[1] - base[1]) / dd * total * 0.999};
        for (int it = 0; it < 10; it++) {
            c[k - 1] = t.clone();
            for (int i = k - 2; i >= 0; i--) { double dx = c[i][0] - c[i + 1][0], dy = c[i][1] - c[i + 1][1], d = Math.max(1e-9, Math.hypot(dx, dy)); c[i] = new double[]{c[i + 1][0] + dx / d * len[i], c[i + 1][1] + dy / d * len[i]}; }
            c[0] = base.clone();
            for (int i = 0; i < k - 1; i++) { double dx = c[i + 1][0] - c[i][0], dy = c[i + 1][1] - c[i][1], d = Math.max(1e-9, Math.hypot(dx, dy)); c[i + 1] = new double[]{c[i][0] + dx / d * len[i], c[i][1] + dy / d * len[i]}; }
        }
    }

    // ------------------------------------------------------------------ Footing tab (smart footing + a top-down map of what the feet see)
    private CompoundTag foot = new CompoundTag();
    private int footAge;
    public void footingMap(CompoundTag d) { foot = d; }
    @Override public void tick() {
        super.tick();
        if (!loco() || !cur().equals("Footing") || footAge++ % 20 != 0) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(built() ? new com.colossalcraft.network.Net.StepRequest(entity(), 0) : new com.colossalcraft.network.Net.StepRequest(-1, pos.asLong()));
    }
    /** Footing map colours: few, flat, from the same green/yellow palette. */
    private static final int F_LOW = 0xFF0E2A16, F_LEVEL = 0xFF1C4A28, F_HIGH = 0xFF2E7040, F_DANGER = 0xFF7A1C1C, F_WATER = 0xFF0E2C3A, F_WALL = 0xFF55604F, F_NONE = 0xFF000000, F_BODY = 0xFF3C8F55;
    private static int footCol(byte hv, byte k) {
        if (hv == 127) return F_NONE; if (hv == 126) return F_WALL;
        if (k == 2) return F_DANGER; if (k == 1) return F_WATER;
        return hv <= -2 ? F_LOW : hv >= 2 ? F_HIGH : F_LEVEL;
    }
    private void footing(GuiGraphics g, int x, int y) {
        g.drawString(font, "FOOTING", x, y, DIM, false);
        int R = foot.getInt("R"), size = 2 * R + 1, ox = x, oy = y + 12;
        byte[] h = foot.getByteArray("H"), k = foot.getByteArray("K");
        int cell = Math.max(4, Math.min(9, (top() + H - 40 - oy) / Math.max(1, size)));
        if (h.length != size * size || size <= 1) { g.drawString(font, "Looking at the ground...", ox, oy + 4, DIM, false); return; }
        boolean[] body = new boolean[size * size];
        for (int i : foot.getIntArray("Body")) if (i >= 0 && i < body.length) body[i] = true;
        for (int z = 0; z < size; z++) { // one fill per run of same-coloured cells in a row
            int run = 0, col = footCol(h[z * size], k[z * size]); if (body[z * size]) col = F_BODY;
            for (int xx = 1; xx <= size; xx++) {
                int c = xx < size ? (body[z * size + xx] ? F_BODY : footCol(h[z * size + xx], k[z * size + xx])) : 0;
                if (xx == size || c != col) { g.fill(ox + run * cell, oy + z * cell, ox + xx * cell, oy + z * cell + cell, col); run = xx; col = c; }
            }
        }
        int cx0 = foot.getInt("X") - R, cz0 = foot.getInt("Z") - R;
        for (Tag tg : foot.getList("Feet", Tag.TAG_COMPOUND)) { CompoundTag f = (CompoundTag) tg;
            int sx = ox + (int) Math.round((f.getFloat("X") - cx0) * cell), sy = oy + (int) Math.round((f.getFloat("Z") - cz0) * cell);
            g.fill(sx - 2, sy - 2, sx + 3, sy + 3, f.getByte("S") == 0 ? TXT : DIM); }
        for (Tag tg : foot.getList("Next", Tag.TAG_COMPOUND)) { CompoundTag f = (CompoundTag) tg;
            int sx = ox + (int) Math.round((f.getFloat("X") - cx0) * cell), sy = oy + (int) Math.round((f.getFloat("Z") - cz0) * cell);
            rect(g, sx - 3, sy - 3, sx + 4, sy + 4, f.getBoolean("Ok") ? WARN : ERR); }
        int mc = ox + R * cell + cell / 2; float fx = foot.getFloat("FX"), fz = foot.getFloat("FZ");
        line(g, mc, oy + R * cell + cell / 2, mc + fx * 2.5 * cell, oy + R * cell + cell / 2 + fz * 2.5 * cell, WARN);
        // legend + status
        int lx = ox + size * cell + 8, ly = y + 40, lw = W - NAV - 16 - size * cell - 8;
        boolean on = data.getBoolean("Smart"), pw = foot.getBoolean("Pw");
        g.drawString(font, !built() ? (on ? "Plans steps once built" : "Off") : !on ? "Off: feet land where the gait says" : pw ? "Planning each step" : "No power for it", lx, ly, !on ? DIM : pw || !built() ? OK : WARN, false); ly += 12;
        for (var hl : font.split(Component.literal("Each foot is put on safe, level ground: no lava, fire or edges. No good spot: a short step, or wait."), lw)) { g.drawString(font, hl, lx, ly, DIM, false); ly += 10; }
        ly += 4;
        int[] lc = {F_LOW, F_LEVEL, F_HIGH, F_WATER, F_DANGER, F_WALL, F_BODY};
        String[] ln = {"lower", "level", "higher", "water", "danger", "wall", "Colossus"};
        for (int i = 0; i < ln.length; i++) { g.fill(lx, ly + 1, lx + 7, ly + 8, lc[i]); g.drawString(font, ln[i], lx + 10, ly, DIM, false); ly += 10; }
        ly += 2;
        g.fill(lx + 1, ly + 2, lx + 6, ly + 7, TXT); g.drawString(font, "foot down", lx + 10, ly, TXT, false); ly += 10;
        rect(g, lx, ly + 1, lx + 7, ly + 8, WARN); g.drawString(font, "next step", lx + 10, ly, TXT, false); ly += 10;
        rect(g, lx, ly + 1, lx + 7, ly + 8, ERR); g.drawString(font, "no safe spot", lx + 10, ly, TXT, false);
    }

    private static String keyName(int code) { return InputConstantsName.of(code); }

    /** Keys shown: the spin keys only when some joint is set to Spin. */
    private int keyCount() { return data.getInt("Wheels") > 0 ? ClientEvents.ACTIONS.length : ClientEvents.SPIN_ACTIONS; }
    private void keyWidgets(int x, int y) {
        CompoundTag lc = data.getCompound("LocoCfg");
        int rowH = keyCount() > 9 ? 14 : 17;
        for (int i = 0; i < keyCount(); i++) {
            int ai = i, code = lc.contains("K" + i) ? lc.getInt("K" + i) : -1;
            String label = binding == i ? "> press a key <" : code < 0 ? "Default (" + ClientEvents.defaultKey(i).getTranslatedKeyMessage().getString() + ")" : keyName(code);
            addRenderableWidget(Button.builder(Component.literal(label), z -> { binding = ai; rebuild(); }).bounds(x + 100, y + 10 + i * rowH, 130, rowH - 2).build());
            Button r = Button.builder(Component.literal("Reset"), z -> send(13, ai, -1)).bounds(x + 234, y + 10 + i * rowH, 40, rowH - 2).build();
            r.active = code >= 0; addRenderableWidget(r);
        }
    }
    private void keys(GuiGraphics g, int x, int y) {
        g.drawString(font, "KEYS WHILE PILOTING", x, y, DIM, false);
        int rowH = keyCount() > 9 ? 14 : 17;
        for (int i = 0; i < keyCount(); i++) g.drawString(font, ClientEvents.ACTIONS[i], x, y + 13 + i * rowH + (rowH - 14) / 2, i >= ClientEvents.SPIN_ACTIONS ? WARN : TXT, false);
        g.drawString(font, keyCount() > ClientEvents.SPIN_ACTIONS ? "Yellow: Spin joints (wheels). Clockwise drives forward." : "Click a key, then press the new one (Esc = default).", x, y + 12 + keyCount() * rowH, DIM, false);
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (binding >= 0) {
            send(13, binding, key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE ? -1 : key);
            binding = -1; rebuild(); return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private CompoundTag joint(int id) {
        if (id < 0) return null;
        for (Tag t : list("Joints")) if (((CompoundTag) t).getInt("Id") == id) return (CompoundTag) t;
        return null;
    }

    private void buildTree() {
        treeRows.clear();
        Map<Integer, List<CompoundTag>> bySeg = new HashMap<>();
        for (Tag t : list("Joints")) { CompoundTag j = (CompoundTag) t; bySeg.computeIfAbsent(j.getInt("Parent"), x -> new ArrayList<>()).add(j); }
        Map<Integer, CompoundTag> segs = new HashMap<>();
        for (Tag t : list("Segs")) segs.put(((CompoundTag) t).getInt("Id"), (CompoundTag) t);
        if (!segs.isEmpty()) addSeg(0, segs, bySeg, 0);
        if (selected < 0 || joint(selected) == null) selected = list("Joints").isEmpty() ? -1 : ((CompoundTag) list("Joints").get(0)).getInt("Id");
    }

    private void addSeg(int id, Map<Integer, CompoundTag> segs, Map<Integer, List<CompoundTag>> bySeg, int depth) {
        CompoundTag s = segs.get(id); if (s == null || depth > 40) return;
        treeRows.add(new Row((id == 0 ? "CORE  " : "Part " + id + "  ") + StructureScan.tons(s.getDouble("Mass")), id == 0 ? HEAD : TXT, -1, depth));
        for (CompoundTag j : bySeg.getOrDefault(id, List.of())) {
            double r = j.getDouble("Ratio");
            treeRows.add(new Row("J" + j.getInt("Id") + " " + j.getString("Tier") + " " + (int) (r * 100) + "% @" + j.getInt("X") + "," + j.getInt("Y") + "," + j.getInt("Z"),
                    r > 1 ? ERR : j.getBoolean("Stalled") ? WARN : TXT, j.getInt("Id"), depth + 1));
            for (Tag c : j.getList("Children", Tag.TAG_COMPOUND)) addSeg(((CompoundTag) c).getInt("S"), segs, bySeg, depth + 2);
        }
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------------ input
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (cur().equals("Joints") && mx >= cvX && mx < cvX + cvW && my >= cvY && my < cvY + cvH) {
            int best = -1; double bd = 100;
            for (int[] mk : markers) { double d = Math.pow(mk[1] - mx, 2) + Math.pow(mk[2] - my, 2); if (d < bd) { bd = d; best = mk[0]; } }
            if (best >= 0) { selected = best; rebuild(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (cur().equals("Joints") && mx >= cvX && mx < cvX + cvW && my >= cvY && my < cvY + cvH) {
            if (button == 0) { camYaw += dx * 0.8; camPitch = Math.max(-90, Math.min(90, camPitch - dy * 0.8)); }
            else { panX += dx; panY += dy; }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (cur().equals("Joints") && x >= cvX && x < cvX + cvW && y >= cvY && y < cvY + cvH) { camZoom = Math.max(0.25, Math.min(8, camZoom * (dy > 0 ? 1.15 : 1 / 1.15))); return true; }
        scroll = Math.max(0, Math.min(Math.max(0, treeRows.size() - 15), scroll - (int) Math.signum(dy)));
        return true;
    }

    // ------------------------------------------------------------------ drawing
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        int l = left(), t = top();
        g.fill(l - 2, t - 2, l + W + 2, t + H + 2, FRAME);
        g.fill(l, t, l + W, t + H, BG);
        for (int y = t; y < t + H; y += 2) g.fill(l, y, l + W, y + 1, 0x14000000);
        g.fill(l, t + 22, l + W, t + 23, DIM);
        g.fill(l + NAV, t + 23, l + NAV + 1, t + H - 34, DIM);
        g.fill(l, t + H - 34, l + W, t + H - 33, DIM);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        tips.clear();
        int l = left(), t = top();
        g.drawString(font, loco() ? "LOCOMOTION CONTROLLER" : "HULL CORE", l + 8, t + 7, HEAD, false);
        String chip = built() ? "● BUILT - flying/driving" : "● PARKED - just blocks";
        g.drawString(font, chip, l + W - 8 - font.width(chip), t + 7, built() ? OK : WARN, false);
        int cx = l + NAV + 8, cy = t + 30;
        switch (cur()) {
            case "Overview" -> overview(g, cx, cy);
            case "Joints" -> joints(g, cx, cy, mx, my);
            case "Gait" -> gait(g, cx, cy);
            case "Keys" -> keys(g, cx, cy);
            case "Footing" -> footing(g, cx, cy);
            case "Power" -> power(g, cx, cy);
            case "Help" -> help(g, cx, cy);
            default -> {}
        }
        // footer status
        ListTag er = strings("Errors");
        String status = !er.isEmpty() ? er.getString(0) : loco() ? (built() ? "Press L while piloting to open this screen." : "Build the Colossus from its Hull Core.")
                : built() ? "Press G while piloting to open this screen." : "Ready. Press BUILD COLOSSUS.";
        g.drawString(font, font.plainSubstrByWidth(status, W - 175), l + 8, t + H - 21, !er.isEmpty() ? ERR : DIM, false);
        for (Tip tp : tips) if (mx >= tp.x0 && mx < tp.x1 && my >= tp.y0 && my < tp.y1) { g.renderTooltip(font, font.split(Component.literal(tp.text), 220), mx, my); break; }
    }

    private void bar(GuiGraphics g, int x, int y, int w, double frac, int color, String label, String tip) {
        g.fill(x, y, x + w, y + 9, 0xFF14281A);
        g.fill(x, y, x + (int) (w * Math.max(0, Math.min(1, frac))), y + 9, color);
        g.fill(x + w - 1, y - 1, x + w, y + 10, DIM);
        g.drawString(font, label, x + 2, y + 1, 0xFF041008, false);
        if (tip != null) tips.add(new Tip(x, y, x + w, y + 9, tip));
    }

    private void check(GuiGraphics g, int x, int y, int state, String text, String tip) {
        String mark = state == 0 ? "✔" : state == 1 ? "!" : "✖";
        int col = state == 0 ? OK : state == 1 ? WARN : ERR;
        g.drawString(font, mark, x, y, col, false);
        g.drawString(font, text, x + 12, y, state == 0 ? TXT : col, false);
        tips.add(new Tip(x, y - 1, x + 150, y + 9, tip));
    }

    private void overview(GuiGraphics g, int x, int y) {
        double mass = data.getDouble("Mass"), up = data.getDouble("T_up"), weight = mass * 9.81;
        boolean walkers = false, overloaded = false, stalled = false;
        for (Tag t : list("Joints")) { CompoundTag j = (CompoundTag) t; int m = j.getInt("Mode");
            if ((m == 1 || m == 2 || m == 5) && !j.getBoolean("Stalled")) walkers = true; // legs or programs
            if (j.getDouble("Ratio") > 1) overloaded = true; if (m != 0 && j.getBoolean("Stalled")) stalled = true; }
        double thrustAny = 0; for (String d : new String[]{"up", "down", "north", "south", "east", "west"}) thrustAny += data.getDouble("T_" + d);
        boolean canMove = thrustAny > 0 || walkers || data.getInt("DriveMotors") > 0;
        boolean twoCores = false; for (Tag s : strings("Errors")) twoCores |= s.getAsString().contains("ONE Hull Core");

        g.drawString(font, "SETUP CHECKLIST", x, y, DIM, false);
        int cy = y + 13;
        check(g, x, cy, twoCores ? 2 : 0, "One Hull Core", "Each Colossus has exactly one Hull Core. To put a small Colossus inside a big one, build the small one first."); cy += 12;
        check(g, x, cy, data.getString("Seat").equals("none") ? 1 : 0, "Pilot Throne", "Place a Pilot Throne facing forward. Right-click it (or press V) to sit and steer."); cy += 12;
        check(g, x, cy, data.getDouble("Output") > 0 ? 0 : 2, "Power source", "Add an engine (Ember Boiler first), a Flux Accumulator, or a Kinetic Dynamo (Create)."); cy += 12;
        check(g, x, cy, data.getLong("Energy") > 0 || data.getBoolean("Infinite") ? 0 : 2, "Energy stored", "Right-click engines with fuel. Batteries charge from FE or Create rotation."); cy += 12;
        check(g, x, cy, canMove ? 0 : 1, "Can move itself", "Thrusters push. Joints set to Leg walk it. Servo Motors that touch no joint drive it along the ground."); cy += 12;
        check(g, x, cy, overloaded ? 2 : 0, "Joints hold the weight", "A joint above 100% load will snap. Use a stronger material or a bigger joint (more joint blocks side by side)."); cy += 12;
        check(g, x, cy, stalled ? 1 : 0, "Motors strong enough", "A moving joint needs a Servo Motor touching it with enough torque to lift its limb."); cy += 12;
        if (data.getBoolean("Legged")) {
            check(g, x, cy, data.getDouble("CoreGyro") > 0 ? 0 : 2, "Gyro in the core part", "Walkers balance from the Hull Core's part. Put a Pocket Gyro (or a gyro structure) in it. It needs power."); cy += 12;
            StringBuilder ft = new StringBuilder("Balance is passed up from the feet: every joint from each foot to the Hull Core needs a strong enough Servo Motor.");
            boolean legsOk = true;
            for (Tag f : list("Feet")) { CompoundTag fc = (CompoundTag) f; legsOk &= fc.getBoolean("Ok"); ft.append("\nFoot part ").append(fc.getInt("Seg")).append(": ").append(fc.getBoolean("Ok") ? "OK" : fc.getString("Problem")); }
            check(g, x, cy, legsOk ? 0 : 2, "Motors up every leg", ft.toString()); cy += 12;
            check(g, x, cy, data.getBoolean("Balanced") ? 0 : 2, "Weight over the feet", String.format("The centre of mass must sit over the feet (the gyro widens that area a little). Now %.1f blocks outside. See the Top view in Joints.", data.getDouble("BalanceOff"))); cy += 12;
        }
        if (walkers) { check(g, x, cy, data.getDouble("Gyro") >= mass ? 0 : 1, "Gyros strong enough", "Walkers need gyro capacity at least equal to their mass to walk at full speed."); cy += 12; }

        int gx = x + 170, gw = W - NAV - 8 - 176;
        g.drawString(font, "STATUS", gx, y, DIM, false);
        g.drawString(font, "Mass " + StructureScan.tons(mass) + "   Blocks " + data.getInt("Blocks"), gx, y + 13, TXT, false);
        g.drawString(font, "Parts " + list("Segs").size() + "   Joints " + list("Joints").size(), gx, y + 24, TXT, false);
        g.drawString(font, "Lift vs weight", gx, y + 38, DIM, false);
        bar(g, gx, y + 48, gw, weight <= 0 ? 0 : up / weight, up >= weight ? OK : WARN, String.format("%.0f%%", 100 * up / Math.max(1, weight)),
                "Upward thrust compared with weight. Over 100% means it can fly. Hover assist only uses what it needs.");
        double cap = 0; for (Tag e : list("Engines")) cap += ((CompoundTag) e).getLong("Cap");
        boolean inf = data.getBoolean("Infinite");
        g.drawString(font, "Energy", gx, y + 62, DIM, false);
        bar(g, gx, y + 72, gw, inf ? 1 : cap <= 0 ? 0 : data.getLong("Energy") / cap, 0xFF3AD7FF, inf ? "INFINITE" : StructureScan.kilo(data.getLong("Energy")) + " CJ",
                "Stored energy across all engines and batteries.");
        g.drawString(font, "Stability", gx, y + 86, DIM, false);
        double st = data.getDouble("Gyro") / Math.max(1, mass);
        bar(g, gx, y + 96, gw, st, st >= 1 ? OK : WARN, String.format("%.0f%%", Math.min(999, st * 100)), "Gyro capacity compared with mass. Gyros make turning stronger and let walkers walk at full speed.");
        g.drawString(font, String.format("Thrust  up %s  down %s", StructureScan.kilo(up), StructureScan.kilo(data.getDouble("T_down"))) , gx, y + 112, TXT, false);
        g.drawString(font, String.format("N %s  S %s  E %s  W %s", StructureScan.kilo(data.getDouble("T_north")), StructureScan.kilo(data.getDouble("T_south")),
                StructureScan.kilo(data.getDouble("T_east")), StructureScan.kilo(data.getDouble("T_west"))), gx, y + 123, TXT, false);
        g.drawString(font, "Forward: " + data.getString("Seat"), gx, y + 134, DIM, false);

        int py = Math.max(cy + 6, y + 150);
        for (Tag s : strings("Errors")) { if (py > top() + H - 46) break; for (var ln : font.split(Component.literal("X " + s.getAsString()), W - NAV - 16)) { g.drawString(font, ln, x, py, ERR, false); py += 10; } }
        for (Tag s : strings("Warnings")) { if (py > top() + H - 46) break; g.drawString(font, font.plainSubstrByWidth("! " + s.getAsString(), W - NAV - 16), x, py, WARN, false); py += 10; }
    }

    private double[] toScreen(CompoundTag v, double lx, double ly, double lz, int ox, int oy, int cell) {
        int ua = v.getInt("UA"), va = v.getInt("VA");
        double cu = ua == 0 ? lx : ua == 1 ? ly : lz, cv = va == 0 ? lx : va == 1 ? ly : lz;
        double su = (cu - v.getInt("MinU")) / v.getInt("Scale"), sv = (cv - v.getInt("MinV")) / v.getInt("Scale");
        if (v.getBoolean("FlipV")) sv = v.getInt("H") - sv;
        return new double[]{ox + su * cell, oy + sv * cell};
    }

    /** Turn a local offset into screen space: yaw around Y, then pitch. Returns {x, y (down), depth}. */
    private static double[] proj(double x, double y, double z, double cyw, double syw, double cp, double sp) {
        double x1 = x * cyw - z * syw, z1 = x * syw + z * cyw;
        double y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
        return new double[]{x1, -y2, z2};
    }

    /** 2D schematic: core part green, other parts blue, bearings yellow, problems red. Click a joint marker to select it. */
    private void joints(GuiGraphics g, int x, int y, int mx, int my) {
        cvX = x - 2; cvY = y - 4; cvW = 164; cvH = top() + H - 50 - cvY;
        g.fill(cvX, cvY, cvX + cvW, cvY + cvH, 0xFF04110A);
        for (int gx = cvX; gx < cvX + cvW; gx += 8) g.fill(gx, cvY, gx + 1, cvY + cvH, 0x1A2CFF7A);
        for (int gy = cvY; gy < cvY + cvH; gy += 8) g.fill(cvX, gy, cvX + cvW, gy + 1, 0x1A2CFF7A);
        markers.clear();
        CompoundTag pts = data.getCompound("Points");
        int[] P = pts.getIntArray("P");
        if (P.length >= 2) {
            double x0 = pts.getInt("X0"), y0 = pts.getInt("Y0"), z0 = pts.getInt("Z0");
            int n = P.length / 2;
            double[] bx = new double[n], by = new double[n], bz = new double[n];
            double mnx = 1e9, mny = 1e9, mnz = 1e9, mxx = -1e9, mxy = -1e9, mxz = -1e9;
            for (int i = 0; i < n; i++) {
                int pk = P[i * 2];
                bx[i] = x0 + (pk >> 20 & 1023) + .5; by[i] = y0 + (pk >> 10 & 1023) + .5; bz[i] = z0 + (pk & 1023) + .5;
                mnx = Math.min(mnx, bx[i]); mny = Math.min(mny, by[i]); mnz = Math.min(mnz, bz[i]); mxx = Math.max(mxx, bx[i]); mxy = Math.max(mxy, by[i]); mxz = Math.max(mxz, bz[i]);
            }
            double ccx = (mnx + mxx) / 2, ccy = (mny + mxy) / 2, ccz = (mnz + mxz) / 2;
            double ext = Math.max(2, Math.sqrt(Math.pow(mxx - mnx + 1, 2) + Math.pow(mxy - mny + 1, 2) + Math.pow(mxz - mnz + 1, 2)));
            double sc = Math.min(cvW, cvH) / ext * camZoom;
            double cyw = Math.cos(Math.toRadians(camYaw)), syw = Math.sin(Math.toRadians(camYaw)), cp = Math.cos(Math.toRadians(camPitch)), spt = Math.sin(Math.toRadians(camPitch));
            double ocx = cvX + cvW / 2.0 + panX, ocy = cvY + cvH / 2.0 + panY;
            double[] sx = new double[n], sy = new double[n], dep = new double[n];
            for (int i = 0; i < n; i++) {
                double[] q = proj(bx[i] - ccx, by[i] - ccy, bz[i] - ccz, cyw, syw, cp, spt);
                sx[i] = ocx + q[0] * sc; sy[i] = ocy + q[1] * sc; dep[i] = q[2];
            }
            Integer[] order = new Integer[n]; for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (i1, i2) -> Double.compare(dep[i2], dep[i1]));
            double dmin = Arrays.stream(dep).min().orElse(0), dmax = Arrays.stream(dep).max().orElse(1);
            Set<Integer> hiSegs = new HashSet<>();
            CompoundTag sj = joint(selected);
            if (sj != null) for (Tag c : sj.getList("Children", Tag.TAG_COMPOUND)) hiSegs.add(((CompoundTag) c).getInt("S"));
            int sz = (int) Math.max(1, Math.ceil(sc));
            g.enableScissor(cvX, cvY, cvX + cvW, cvY + cvH);
            String hover = null;
            for (int oi : order) {
                int val = P[oi * 2 + 1], col;
                if (val >= 1000) { CompoundTag jj = joint(val - 1000); col = jj != null && (jj.getDouble("Ratio") > 1 || jj.getBoolean("Stalled")) ? BAD_COL : val - 1000 == selected ? SEL_COL : JOINT_COL; }
                else { int seg = val - 1; col = seg == 0 ? CORE_COL : PART_COLS[seg % PART_COLS.length]; if (hiSegs.contains(seg)) col = 0xFFB8E6FF; }
                double shadeF = 1 - 0.45 * (dmax - dmin < 1e-6 ? 0 : (dep[oi] - dmin) / (dmax - dmin));
                int c2 = 0xFF000000 | ((int) (((col >> 16) & 255) * shadeF) << 16) | ((int) (((col >> 8) & 255) * shadeF) << 8) | (int) ((col & 255) * shadeF);
                int px = (int) (sx[oi] - sz / 2.0), py = (int) (sy[oi] - sz / 2.0);
                g.fill(px, py, px + sz, py + sz, c2);
                if (mx >= px && mx < px + sz && my >= py && my < py + sz) hover = val >= 1000 ? "Joint J" + (val - 1000) : val == 1 ? "Core part (Hull Core)" : "Part " + (val - 1);
            }
            for (Tag t : list("Joints")) {
                CompoundTag jj = (CompoundTag) t; int id = jj.getInt("Id");
                double[] q = proj(jj.getDouble("PX") - ccx, jj.getDouble("PY") - ccy, jj.getDouble("PZ") - ccz, cyw, syw, cp, spt);
                int x1 = (int) (ocx + q[0] * sc), y1 = (int) (ocy + q[1] * sc);
                boolean bad = jj.getDouble("Ratio") > 1 || jj.getBoolean("Stalled");
                int c = id == selected ? SEL_COL : bad ? BAD_COL : JOINT_COL;
                g.fill(x1 - 3, y1 - 3, x1 + 4, y1 - 2, c); g.fill(x1 - 3, y1 + 3, x1 + 4, y1 + 4, c); g.fill(x1 - 3, y1 - 3, x1 - 2, y1 + 4, c); g.fill(x1 + 3, y1 - 3, x1 + 4, y1 + 4, c);
                g.drawString(font, "J" + id, x1 + 5, y1 - 4, c, true);
                markers.add(new int[]{id, x1, y1});
            }
            double[] cm = proj(data.getDouble("CX") - ccx, data.getDouble("CY") - ccy, data.getDouble("CZ") - ccz, cyw, syw, cp, spt);
            int cmx = (int) (ocx + cm[0] * sc), cmy = (int) (ocy + cm[1] * sc);
            g.fill(cmx - 4, cmy, cmx + 5, cmy + 1, SEL_COL); g.fill(cmx, cmy - 4, cmx + 1, cmy + 5, SEL_COL);
            tips.add(new Tip(cmx - 4, cmy - 4, cmx + 5, cmy + 5, "Centre of mass"));
            if (data.contains("SX0")) {
                double sy0 = data.getDouble("SY");
                double[][] cr = {{data.getDouble("SX0"), data.getDouble("SZ0")}, {data.getDouble("SX1"), data.getDouble("SZ0")}, {data.getDouble("SX1"), data.getDouble("SZ1")}, {data.getDouble("SX0"), data.getDouble("SZ1")}};
                int sc2 = data.getBoolean("Balanced") ? 0xFF3AD7FF : BAD_COL;
                for (int k = 0; k < 4; k++) {
                    double[] a1 = proj(cr[k][0] - ccx, sy0 - ccy, cr[k][1] - ccz, cyw, syw, cp, spt), a2 = proj(cr[(k + 1) % 4][0] - ccx, sy0 - ccy, cr[(k + 1) % 4][1] - ccz, cyw, syw, cp, spt);
                    for (int st = 0; st <= 20; st++) { double f = st / 20.0; int lx2 = (int) (ocx + (a1[0] + (a2[0] - a1[0]) * f) * sc), ly2 = (int) (ocy + (a1[1] + (a2[1] - a1[1]) * f) * sc); g.fill(lx2, ly2, lx2 + 1, ly2 + 1, sc2); }
                }
            }
            for (Tag t : list("Contacts")) {
                CompoundTag c = (CompoundTag) t;
                double[] q = proj(c.getInt("X") + .5 - ccx, c.getInt("Y") + .5 - ccy, c.getInt("Z") + .5 - ccz, cyw, syw, cp, spt);
                int x1 = (int) (ocx + q[0] * sc), y1 = (int) (ocy + q[1] * sc);
                for (int k = -3; k <= 3; k++) { g.fill(x1 + k, y1 + k, x1 + k + 1, y1 + k + 1, BAD_COL); g.fill(x1 + k, y1 - k, x1 + k + 1, y1 - k + 1, BAD_COL); }
                tips.add(new Tip(x1 - 4, y1 - 4, x1 + 5, y1 + 5, "Touches the ground here! Put Gantry Scaffold under it instead."));
            }
            g.disableScissor();
            if (hover != null) tips.add(new Tip(mx, my, mx + 1, my + 1, hover));
        }
        int ly = cvY + cvH + 3;
        int[][] leg = {{CORE_COL}, {PART_COLS[0]}, {JOINT_COL}, {BAD_COL}};
        String[] ln = {"Core", "Part", "Joint", "Problem"};
        int lx = x;
        for (int i = 0; i < 4; i++) { g.fill(lx, ly + 1, lx + 6, ly + 7, leg[i][0]); g.drawString(font, ln[i], lx + 8, ly, DIM, false); lx += 14 + font.width(ln[i]); }
        g.drawString(font, "+ = weight", x, ly + 10, DIM, false);
        if (list("Joints").isEmpty()) g.drawString(font, "No joints yet: place Bearing Joints between parts.", x, y + 14, WARN, false);
        CompoundTag j = joint(selected);
        if (j == null) return;
        if (romX >= 0) { g.fill(romX, romY, romX + romW, romY + 14, 0xFF14281A); g.drawString(font, romText, romX + 3, romY + 3, romWarn ? WARN : TXT, false); tips.add(new Tip(romX, romY, romX + romW, romY + 14, romTipText)); }
        int dx = x + 168, dw = W - NAV - 8 - 174;
        g.drawString(font, "J" + j.getInt("Id") + "  " + j.getString("Tier"), dx, y, HEAD, false);
        String gears = j.getString("Gears");
        String spd = j.getInt("Motors") == 0 ? "" : String.format("max %.0f\u00B0/s", j.getDouble("MaxSpeed")) + (gears.isEmpty() || gears.equals("1") ? "" : "  gears " + gears + ":1");
        g.drawString(font, font.plainSubstrByWidth(spd, dw - 92), dx + 92, y, j.getDouble("NeedSpeed") > j.getDouble("MaxSpeed") + 0.01 && j.getInt("Mode") != 0 ? WARN : DIM, false);
        boolean free = j.getList("Children", Tag.TAG_COMPOUND).isEmpty();
        g.drawString(font, String.format("Position  %d, %d, %d", j.getInt("X"), j.getInt("Y"), j.getInt("Z")), dx, y + 11, WARN, false);
        tips.add(new Tip(dx, y + 10, dx + dw, y + 20, "World X, Y, Z of this joint's first bearing block. Press F3 and look at a bearing to match it."));
        g.drawString(font, String.format("%d blk, strength %.2f, %s axis", j.getInt("K"), j.getDouble("Area"), j.getString("Axis").toUpperCase()), dx, y + 22, DIM, false);
        double r = j.getDouble("Ratio"), sr = j.getDouble("StandRatio"), share = j.getDouble("Share");
        String capTip = String.format("Limit: %sN straight down, %sN*m of leverage. Over 100%% it snaps. Bigger joints: force grows with blocks, leverage with blocks^1.5.",
                StructureScan.kilo(j.getDouble("FCap")), StructureScan.kilo(j.getDouble("MCap")));
        boolean motors = j.getInt("Motors") > 0;
        double tq = j.getDouble("Torque"), req = j.getDouble("Req"), sreq = j.getDouble("StandTq");
        int bx = dx + 40, bw = dw - 40;
        g.drawString(font, "LIFTED", dx, y + 33, DIM, false);
        bar(g, bx, y + 32, bw, r, r > 1 ? ERR : r > 0.8 ? WARN : OK, free ? "holds nothing" : String.format("hangs %s, %.0f%%", StructureScan.tons(j.getDouble("Sub")), r * 100),
                "Lifted: the weight of everything below this joint hangs from it (a leg in the air, an arm). " + capTip);
        bar(g, bx, y + 43, bw, !motors ? 0 : req <= 0 ? 1 : tq / req, !motors ? DIM : tq >= req ? OK : WARN,
                !motors ? "no motor" : "turn " + StructureScan.kilo(tq) + " / " + StructureScan.kilo(req) + " N*m",
                "Turning force needed to swing what hangs below. Put Servo Motors touching the joint. Motors of all touching blocks add up.");
        g.drawString(font, "STAND", dx, y + 57, DIM, false);
        if (share <= 0) g.drawString(font, "no foot below this joint", bx, y + 57, DIM, false);
        else {
            bar(g, bx, y + 56, bw, sr, sr > 1 ? ERR : sr > 0.8 ? WARN : OK, String.format("carries %s, %.0f%%", StructureScan.tons(j.getDouble("Stand")), sr * 100),
                    String.format("Standing: its feet carry %.0f%% of the whole rig. This joint holds that, minus what is below it, so lower joints carry more. Equal legs share evenly. ", share * 100) + capTip);
            boolean passive = j.getBoolean("Ball") || j.getInt("Mode") == 0;
            bar(g, bx, y + 67, bw, passive ? 1 : !motors ? 0 : sreq <= 0 ? 1 : tq / sreq, passive || !motors ? DIM : tq >= sreq ? OK : WARN,
                    passive ? (j.getBoolean("Ball") ? "ball: levels itself" : "locked: rigid") : !motors ? "no motor" : "hold " + StructureScan.kilo(tq) + " / " + StructureScan.kilo(sreq) + " N*m",
                    "Turning force needed to keep the rig up from this joint while standing (bent knees need more). Straight legs need little.");
        }
        int iy = y + 82;
        int rec = j.getInt("RecMode"), cm = j.getInt("Mode"); if (cm == 2) cm = 1; if (cm == 5) cm = 0;
        boolean good = rec < 0 || rec == cm;
        for (var mln : font.split(Component.literal(j.getString("Role")), dw)) { g.drawString(font, mln, dx, iy, TXT, false); iy += 10; }
        if (!good) { g.drawString(font, "\u25B6 Set Mode to " + MODE_NAMES[rec] + " (now " + MODE_NAMES[cm] + ")", dx, iy, WARN, false); iy += 10; }
        iy += 1;
        if (j.getBoolean("LegHip")) {
            g.drawString(font, String.format("Foot grip %.2f%s", j.getDouble("Grip"), j.getDouble("Grip") < 0.3 ? "  (slippery!)" : ""), dx, iy, j.getDouble("Grip") < 0.3 ? WARN : TXT, false); iy += 11;
            g.drawString(font, "Push " + StructureScan.kilo(j.getDouble("Push")) + "N, carries " + StructureScan.tons(j.getDouble("Carry") / 9.81), dx, iy, j.getDouble("Carry") < data.getDouble("Mass") * 9.81 ? WARN : TXT, false); iy += 11;
            g.drawString(font, String.format("Leg %.1f blocks, %d joint%s", j.getDouble("LegLen"), j.getInt("LegJoints"), j.getInt("LegJoints") == 1 ? "" : "s (knee)"), dx, iy, DIM, false); iy += 11;
        } else if (j.getInt("Blades") > 0) {
            g.drawString(font, j.getInt("Blades") + " blades on this rotor", dx, iy, TXT, false); iy += 11;
        }
    }

    private void power(GuiGraphics g, int x, int y) {
        boolean inf = data.getBoolean("Infinite");
        double out = data.getDouble("Output"), draw = data.getDouble("MaxDraw"); long en = data.getLong("Energy");
        g.drawString(font, String.format("Output %s CJ/t     Full-power use %s CJ/t", StructureScan.kilo(out), StructureScan.kilo(draw)), x, y, HEAD, false);
        double use = Math.min(out, draw);
        g.drawString(font, inf ? "Runs forever (Zero-Point Core)" : use <= 0 ? "Nothing uses power yet." : String.format("Lasts about %.0f s at full power", en / use / 20.0), x, y + 12, TXT, false);
        if (out < draw) g.drawString(font, "Engines are too weak for everything at once: parts slow down.", x, y + 24, WARN, false);
        int ry = y + 40, bw = 110;
        g.drawString(font, "SOURCE", x, ry, DIM, false); g.drawString(font, "STORED", x + 120, ry, DIM, false); g.drawString(font, "OUTPUT", x + 240, ry, DIM, false);
        ry += 12;
        for (Tag t : list("Engines")) {
            if (ry > top() + H - 46) break;
            CompoundTag e = (CompoundTag) t;
            boolean zp = e.getBoolean("ZP"), on = e.getBoolean("Run") && e.getString("Note").isEmpty();
            g.drawString(font, font.plainSubstrByWidth(e.getString("Name"), 115), x, ry, on ? TXT : WARN, false);
            if (zp) g.drawString(font, e.getLong("Energy") < 0 ? "PRIMED" : "needs Nether Star", x + 120, ry, e.getLong("Energy") < 0 ? OK : WARN, false);
            else bar(g, x + 120, ry, bw, e.getLong("Energy") / (double) Math.max(1, e.getLong("Cap")), 0xFF3AD7FF, StructureScan.kilo(e.getLong("Energy")), StructureScan.kilo(e.getLong("Energy")) + " / " + StructureScan.kilo(e.getLong("Cap")) + " CJ");
            g.drawString(font, StructureScan.kilo(e.getInt("Out")) + " CJ/t" + (on ? "" : "  (" + e.getString("Note") + ")"), x + 240, ry, on ? TXT : WARN, false);
            ry += 12;
        }
        if (list("Engines").isEmpty()) g.drawString(font, "No engines or batteries found.", x, ry, WARN, false);
        if (built()) g.drawString(font, "While built, stored energy is shown for the whole Colossus.", x, top() + H - 46, DIM, false);
    }

    private void help(GuiGraphics g, int x, int y) {
        String[] lines = {
                "§aBuild§r anything. Stand it on Gantry Scaffold so it touches",
                "nothing else: scaffold is never picked up, every other block is.",
                "§aOne Hull Core§r per Colossus. Joints branch out from its part.",
                "§aJoints§r split it into parts. Joint blocks side by side work as",
                "one bigger joint. Shapes: Bearing (full), Slab, Panel, Pin.",
                "§aServo Motors§r touching a joint move it. Other motors drive wheels.",
                "§aPower:§r engines + fuel, Flux Accumulator (FE), Kinetic Dynamo (Create).",
                "§aPilot Throne§r faces forward. Right-click it or press V to sit.",
                "§aGearboxes§r between motor and joint trade speed for torque.",
                "§aJoints§r tab: drag to turn the 3D view, right-drag to move, scroll to zoom.",
                "§aKeys:§r W/S go, A/D turn, Space/C up/down, H hover,",
                "G this screen, K park, F5 camera, Shift leave the seat.",
                "§aPark§r turns it back into blocks; BUILD makes it move again."};
        int ly = y;
        for (String s : lines) { g.drawString(font, s, x, ly, TXT, false); ly += 12; }
    }

    @Override public boolean isPauseScreen() { return false; }
}
