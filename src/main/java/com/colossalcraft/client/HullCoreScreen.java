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
    private static final String[] MODE_NAMES = {"Locked", "Walk (left)", "Walk (right)", "Spin", "Sway", "Program"};
    private static final String[] MODE_HELP = {
            "Doesn't move. Use for rigid parts.",
            "Swings while walking forward. Use on one leg...",
            "...and this one on the other leg (opposite timing).",
            "Turns all the time while powered: propellers, wheels, turrets.",
            "Slow idle motion: tails, antennae, heads.",
            "Follows the keyframes you set in the Animate tab."};
    private static final String[] TABS = {"Overview", "Joints", "Animate", "Power", "Help"};
    private static final String[] VIEWS = {"ViewX", "ViewZ", "ViewTop"}, VIEW_NAMES = {"Side X", "Side Z", "Top"};
    private static final int CORE_COL = 0xFF2CFF7A, JOINT_COL = 0xFFFFD34A, BAD_COL = 0xFFFF4A4A, SEL_COL = 0xFFFFFFFF;
    private static final int[] PART_COLS = {0xFF3AA0FF, 0xFF2A7FD9, 0xFF5BC0FF, 0xFF1E66B3, 0xFF4C8DFF, 0xFF77B8FF};
    private int view = 0;
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

    public boolean matches(BlockPos p, CompoundTag d) { return built() ? d.getInt("Entity") == entity() : p.equals(pos) && !d.getBoolean("Built"); }
    public void setData(CompoundTag d) { data = d; rebuild(); }
    public void setTab(int t) { tab = t; scroll = 0; rebuild(); }
    public void setView(int v) { view = v; rebuild(); }
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
        for (int i = 0; i < TABS.length; i++) {
            int ti = i;
            Button b = Button.builder(Component.literal(TABS[i]), x -> { tab = ti; scroll = 0; rebuild(); }).bounds(l + 6, t + 28 + i * 24, NAV - 10, 20).build();
            b.active = tab != i; addRenderableWidget(b);
        }
        Button hover = Button.builder(Component.literal("Hover: " + (data.getBoolean("Hover") ? "ON" : "OFF")), x -> send(3, -1, 0)).bounds(l + 6, t + H - 76, NAV - 10, 18).build();
        hover.setTooltip(Tooltip.create(Component.literal("Hover assist: thrusters hold height on their own when you don't press up/down.")));
        addRenderableWidget(hover);
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
        addRenderableWidget(act);

        if (tab == 1) {
            for (int i = 0; i < 3; i++) {
                int vi = i;
                Button b = Button.builder(Component.literal(VIEW_NAMES[i]), z -> { view = vi; rebuild(); }).bounds(l + NAV + 8 + i * 54, t + 27, 52, 12).build();
                b.active = view != i; addRenderableWidget(b);
            }
            jointWidgets(l + NAV + 8 + 168, t + 30);
        }
        if (tab == 2) animWidgets(l + NAV + 8, t + 30);
    }

    private void animWidgets(int x, int y) {
        int dw = W - NAV - 16;
        int gs = (int) Math.round(data.getDouble("GaitSpeed") * 100);
        addRenderableWidget(Button.builder(Component.literal("-"), z -> send(9, -1, gs - (gs > 50 ? 10 : 5))).bounds(x + 150, y - 2, 14, 12).build());
        addRenderableWidget(Button.builder(Component.literal("+"), z -> send(9, -1, gs + (gs >= 50 ? 10 : 5))).bounds(x + 166, y - 2, 14, 12).build());
        int bx = x;
        for (Tag t : list("Joints")) {
            CompoundTag j = (CompoundTag) t; int id = j.getInt("Id");
            if (bx + 26 > x + dw) break;
            Button b = Button.builder(Component.literal("J" + id), z -> { selected = id; rebuild(); }).bounds(bx, y + 12, 25, 12).build();
            b.active = selected != id; addRenderableWidget(b); bx += 27;
        }
        CompoundTag j = joint(selected); if (j == null) return;
        int mode = j.getInt("Mode");
        Button prog = Button.builder(Component.literal(mode == 5 ? "> Program" : "Use Program"), z -> send(1, selected, 5)).bounds(x + 70, y + 27, 62, 12).build();
        prog.active = mode != 5; prog.setTooltip(Tooltip.create(Component.literal("Switch this joint to follow the keyframes below.")));
        addRenderableWidget(prog);
        Button loop = Button.builder(Component.literal("Loop: " + (j.getBoolean("Loop") ? "ON" : "OFF")), z -> send(10, selected, 0)).bounds(x + 134, y + 27, 50, 12).build();
        loop.setTooltip(Tooltip.create(Component.literal("ON: plays all the time (idle animations). OFF: plays only while walking.")));
        addRenderableWidget(loop);
        int ph = j.getInt("Phase"), sp = j.getInt("SpeedX");
        addRenderableWidget(Button.builder(Component.literal("-"), z -> send(7, selected, ph - 15)).bounds(x + 188, y + 27, 12, 12).build());
        addRenderableWidget(Button.builder(Component.literal("+"), z -> send(7, selected, ph + 15)).bounds(x + 228, y + 27, 12, 12).build());
        addRenderableWidget(Button.builder(Component.literal("-"), z -> send(8, selected, sp - 25)).bounds(x + 246, y + 27, 12, 12).build());
        addRenderableWidget(Button.builder(Component.literal("+"), z -> send(8, selected, sp + 25)).bounds(x + 290, y + 27, 12, 12).build());
        int[] keys = Arrays.copyOf(j.getIntArray("Keys"), 8);
        int colW = dw / 8;
        for (int i = 0; i < 8; i++) {
            int ki = i, v = keys[i], cx = x + i * colW + (colW - 22) / 2;
            addRenderableWidget(Button.builder(Component.literal("\u25B2"), z -> send(6, selected, (ki << 16) | (Math.min(180, v + (hasShiftDown() ? 15 : 5)) + 180))).bounds(cx, y + 108, 22, 11).build());
            addRenderableWidget(Button.builder(Component.literal("\u25BC"), z -> send(6, selected, (ki << 16) | (Math.max(-180, v - (hasShiftDown() ? 15 : 5)) + 180))).bounds(cx, y + 132, 22, 11).build());
        }
    }

    private void jointWidgets(int x, int y) {
        CompoundTag j = joint(selected);
        if (j == null) return;
        int mode = j.getInt("Mode");
        int by = y + 66;
        for (int i = 0; i < MODE_NAMES.length; i++) {
            int mi = i;
            Button b = Button.builder(Component.literal((mode == i ? "> " : "") + MODE_NAMES[i]), z -> send(1, selected, mi)).bounds(x, by + i * 14, 92, 13).build();
            b.setTooltip(Tooltip.create(Component.literal(MODE_HELP[i])));
            b.active = mode != i; addRenderableWidget(b);
        }
        int amp = j.getInt("Amp");
        int ay = by + MODE_NAMES.length * 14 + 3;
        addRenderableWidget(Button.builder(Component.literal("-"), z -> send(2, selected, amp - (amp > 30 ? 10 : 5))).bounds(x, ay, 18, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), z -> send(2, selected, amp + (amp >= 30 ? 10 : 5))).bounds(x + 74, ay, 18, 16).build());
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
            treeRows.add(new Row("J" + j.getInt("Id") + " " + cap(j.getString("Tier")) + " " + (int) (r * 100) + "% @" + j.getInt("X") + "," + j.getInt("Y") + "," + j.getInt("Z"),
                    r > 1 ? ERR : j.getBoolean("Stalled") ? WARN : TXT, j.getInt("Id"), depth + 1));
            for (Tag c : j.getList("Children", Tag.TAG_COMPOUND)) addSeg(((CompoundTag) c).getInt("S"), segs, bySeg, depth + 2);
        }
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------------ input
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (tab == 1 && mx >= cvX && mx < cvX + cvW && my >= cvY && my < cvY + cvH) {
            int best = -1; double bd = 100;
            for (int[] mk : markers) { double d = Math.pow(mk[1] - mx, 2) + Math.pow(mk[2] - my, 2); if (d < bd) { bd = d; best = mk[0]; } }
            if (best >= 0) { selected = best; rebuild(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
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
        g.drawString(font, "HULL CORE", l + 8, t + 7, HEAD, false);
        String chip = built() ? "● BUILT - flying/driving" : "● PARKED - just blocks";
        g.drawString(font, chip, l + W - 8 - font.width(chip), t + 7, built() ? OK : WARN, false);
        int cx = l + NAV + 8, cy = t + 30;
        switch (tab) {
            case 0 -> overview(g, cx, cy);
            case 1 -> joints(g, cx, cy, mx, my);
            case 2 -> animate(g, cx, cy);
            case 3 -> power(g, cx, cy);
            default -> help(g, cx, cy);
        }
        // footer status
        ListTag er = strings("Errors");
        String status = !er.isEmpty() ? er.getString(0) : built() ? "Press G while piloting to open this screen." : "Ready. Press BUILD COLOSSUS.";
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
            if ((m == 1 || m == 2 || m == 5) && !j.getBoolean("Stalled")) walkers = true;
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
        check(g, x, cy, canMove ? 0 : 1, "Can move itself", "Thrusters push. Joints set to Walk move legs. Servo Motors that touch no joint drive it along the ground."); cy += 12;
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

    /** 2D schematic: core part green, other parts blue, bearings yellow, problems red. Click a joint marker to select it. */
    private void joints(GuiGraphics g, int x, int y, int mx, int my) {
        cvX = x - 2; cvY = y + 12; cvW = 164; cvH = top() + H - 50 - cvY;
        g.fill(cvX, cvY, cvX + cvW, cvY + cvH, 0xFF04110A);
        for (int gx = cvX; gx < cvX + cvW; gx += 8) g.fill(gx, cvY, gx + 1, cvY + cvH, 0x1A2CFF7A);
        for (int gy = cvY; gy < cvY + cvH; gy += 8) g.fill(cvX, gy, cvX + cvW, gy + 1, 0x1A2CFF7A);
        markers.clear();
        CompoundTag v = data.getCompound(VIEWS[view]);
        if (v.contains("W")) {
            int gw = v.getInt("W"), gh = v.getInt("H");
            int cell = Math.max(1, Math.min((cvW - 8) / gw, (cvH - 8) / gh));
            int ox = cvX + (cvW - gw * cell) / 2, oy = cvY + (cvH - gh * cell) / 2;
            int[] cells = v.getIntArray("Cells");
            Set<Integer> hiSegs = new HashSet<>();
            CompoundTag sj = joint(selected);
            if (sj != null) for (Tag c : sj.getList("Children", Tag.TAG_COMPOUND)) hiSegs.add(((CompoundTag) c).getInt("S"));
            String hover = null;
            for (int i = 0; i < cells.length; i++) {
                int val = cells[i]; if (val == 0) continue;
                int u = i % gw, row = v.getBoolean("FlipV") ? gh - 1 - i / gw : i / gw;
                int col;
                if (val >= 1000) { CompoundTag jj = joint(val - 1000); col = jj != null && (jj.getDouble("Ratio") > 1 || jj.getBoolean("Stalled")) ? BAD_COL : val - 1000 == selected ? SEL_COL : JOINT_COL; }
                else { int seg = val - 1; col = seg == 0 ? CORE_COL : PART_COLS[seg % PART_COLS.length]; if (hiSegs.contains(seg)) col = 0xFFB8E6FF; }
                int px = ox + u * cell, py = oy + row * cell;
                g.fill(px, py, px + cell - (cell > 3 ? 1 : 0), py + cell - (cell > 3 ? 1 : 0), col);
                if (mx >= px && mx < px + cell && my >= py && my < py + cell) hover = val >= 1000 ? "Joint J" + (val - 1000) : val == 1 ? "Core part (Hull Core)" : "Part " + (val - 1);
            }
            for (Tag t : list("Joints")) {
                CompoundTag jj = (CompoundTag) t; int id = jj.getInt("Id");
                double[] sp = toScreen(v, jj.getDouble("PX"), jj.getDouble("PY"), jj.getDouble("PZ"), ox, oy, cell);
                int sx = (int) sp[0], sy = (int) sp[1];
                boolean bad = jj.getDouble("Ratio") > 1 || jj.getBoolean("Stalled");
                int c = id == selected ? SEL_COL : bad ? BAD_COL : JOINT_COL;
                g.fill(sx - 3, sy - 3, sx + 4, sy - 2, c); g.fill(sx - 3, sy + 3, sx + 4, sy + 4, c); g.fill(sx - 3, sy - 3, sx - 2, sy + 4, c); g.fill(sx + 3, sy - 3, sx + 4, sy + 4, c);
                g.drawString(font, "J" + id, sx + 5, sy - 4, c, true);
                markers.add(new int[]{id, sx, sy});
            }
            double[] com = toScreen(v, data.getDouble("CX"), data.getDouble("CY"), data.getDouble("CZ"), ox, oy, cell);
            g.fill((int) com[0] - 4, (int) com[1], (int) com[0] + 5, (int) com[1] + 1, SEL_COL); g.fill((int) com[0], (int) com[1] - 4, (int) com[0] + 1, (int) com[1] + 5, SEL_COL);
            tips.add(new Tip((int) com[0] - 4, (int) com[1] - 4, (int) com[0] + 5, (int) com[1] + 5, "Centre of mass"));
            if (data.contains("SX0")) { // feet support area
                double[] a1 = toScreen(v, data.getDouble("SX0"), data.getDouble("SY"), data.getDouble("SZ0"), ox, oy, cell);
                double[] a2 = toScreen(v, data.getDouble("SX1"), data.getDouble("SY"), data.getDouble("SZ1"), ox, oy, cell);
                int x0 = (int) Math.min(a1[0], a2[0]), x1 = (int) Math.max(a1[0], a2[0]), y0 = (int) Math.min(a1[1], a2[1]), y1 = (int) Math.max(a1[1], a2[1]);
                int sc = data.getBoolean("Balanced") ? 0xFF3AD7FF : BAD_COL;
                if (view == 2) { g.fill(x0, y0, x1, y0 + 1, sc); g.fill(x0, y1, x1, y1 + 1, sc); g.fill(x0, y0, x0 + 1, y1, sc); g.fill(x1, y0, x1 + 1, y1 + 1, sc); }
                else g.fill(x0, y0, x1, y0 + 1, sc);
            }
            for (Tag t : list("Contacts")) {
                CompoundTag c = (CompoundTag) t;
                double[] sp = toScreen(v, c.getInt("X") + .5, c.getInt("Y") + .5, c.getInt("Z") + .5, ox, oy, cell);
                int sx = (int) sp[0], sy = (int) sp[1];
                for (int k = -3; k <= 3; k++) { g.fill(sx + k, sy + k, sx + k + 1, sy + k + 1, BAD_COL); g.fill(sx + k, sy - k, sx + k + 1, sy - k + 1, BAD_COL); }
                tips.add(new Tip(sx - 4, sy - 4, sx + 5, sy + 5, "Touches the ground here! Put Gantry Scaffold under it instead."));
            }
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
        int dx = x + 168, dw = W - NAV - 8 - 174;
        g.drawString(font, "J" + j.getInt("Id") + "  " + cap(j.getString("Tier")) + " joint", dx, y, HEAD, false);
        String gears = j.getString("Gears");
        String spd = j.getInt("Motors") == 0 ? "" : String.format("max %.0f\u00B0/s", j.getDouble("MaxSpeed")) + (gears.isEmpty() || gears.equals("1") ? "" : "  gears " + gears + ":1");
        g.drawString(font, spd, dx + 92, y, j.getDouble("NeedSpeed") > j.getDouble("MaxSpeed") + 0.01 && j.getInt("Mode") != 0 ? WARN : DIM, false);
        boolean free = j.getList("Children", Tag.TAG_COMPOUND).isEmpty();
        g.drawString(font, String.format("Position  %d, %d, %d", j.getInt("X"), j.getInt("Y"), j.getInt("Z")), dx, y + 11, WARN, false);
        tips.add(new Tip(dx, y + 10, dx + dw, y + 20, "World X, Y, Z of this joint's first bearing block. Press F3 and look at a bearing to match it."));
        g.drawString(font, String.format("%d blk, strength %.2f, %s axis", j.getInt("K"), j.getDouble("Area"), j.getString("Axis").toUpperCase()), dx, y + 22, DIM, false);
        double r = j.getDouble("Ratio");
        bar(g, dx, y + 33, dw, r, r > 1 ? ERR : r > 0.8 ? WARN : OK, free ? "holds nothing" : String.format("load %.0f%%, holds %s", r * 100, StructureScan.tons(j.getDouble("Sub"))),
                String.format("Limit: %sN straight down, %sN*m of leverage. Over 100%% it snaps. Bigger joints: force grows with blocks, leverage with blocks^1.5.",
                        StructureScan.kilo(j.getDouble("FCap")), StructureScan.kilo(j.getDouble("MCap"))));
        double tq = j.getDouble("Torque"), req = j.getDouble("Req");
        String tl = j.getInt("Motors") == 0 ? "no motor" : StructureScan.kilo(tq) + " / " + StructureScan.kilo(req) + " N*m";
        bar(g, dx, y + 45, dw, j.getInt("Motors") == 0 ? 0 : req <= 0 ? 1 : tq / req, j.getInt("Motors") == 0 ? DIM : tq >= req ? OK : WARN, tl,
                "Motor torque compared with what this limb needs. Put Servo Motors touching the joint. Motors of all touching blocks add up.");
        g.drawString(font, "Movement:", dx, y + 57, DIM, false);
        int mode = j.getInt("Mode");
        int hy = y + 66 + MODE_NAMES.length * 14 + 3;
        g.drawString(font, "Swing " + j.getInt("Amp") + "°", dx + 28, hy + 4, TXT, false);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(MODE_HELP[mode]), dw - 98);
        int hy2 = y + 68; for (var hl : lines) { g.drawString(font, hl, dx + 98, hy2, DIM, false); hy2 += 10; }
    }

    private void animate(GuiGraphics g, int x, int y) {
        int dw = W - NAV - 16;
        double gs = data.getDouble("GaitSpeed"), sc = data.getDouble("GaitScale");
        g.drawString(font, String.format("GAIT  %.2f steps/s", gs), x, y, HEAD, false);
        g.drawString(font, String.format("gears allow %.0f%%", sc * 100), x + 186, y, sc < 0.999 ? WARN : OK, false);
        tips.add(new Tip(x + 186, y - 1, x + dw, y + 9, "If a joint's gears can't turn as fast as the gait asks, the whole gait slows down to match it."));
        CompoundTag j = joint(selected);
        if (j == null) { g.drawString(font, "No joints to animate.", x, y + 30, WARN, false); return; }
        g.drawString(font, "J" + j.getInt("Id") + "  " + MODE_NAMES[j.getInt("Mode")], x, y + 29, TXT, false);
        g.drawString(font, j.getInt("Phase") + "\u00B0", x + 202, y + 29, TXT, false);
        tips.add(new Tip(x + 188, y + 26, x + 240, y + 39, "Phase: shifts this joint's timing in the cycle (e.g. 180\u00B0 for the opposite leg)."));
        g.drawString(font, String.format("%.2fx", j.getInt("SpeedX") / 100.0), x + 260, y + 29, TXT, false);
        tips.add(new Tip(x + 246, y + 26, x + 302, y + 39, "Speed: how many times this joint's keyframes play per step."));
        // graph
        int gx = x, gy = y + 44, gw = dw, gh = 60, mid = gy + gh / 2;
        g.fill(gx, gy, gx + gw, gy + gh, 0xFF04110A);
        g.fill(gx, mid, gx + gw, mid + 1, 0x552CFF7A);
        g.fill(gx, gy + gh / 4, gx + gw, gy + gh / 4 + 1, 0x222CFF7A); g.fill(gx, gy + 3 * gh / 4, gx + gw, gy + 3 * gh / 4 + 1, 0x222CFF7A);
        g.drawString(font, "+90", gx + 1, gy + 1, DIM, false); g.drawString(font, "-90", gx + 1, gy + gh - 9, DIM, false);
        int[] keys = Arrays.copyOf(j.getIntArray("Keys"), 8);
        int colW = dw / 8;
        for (int px = 0; px < gw; px++) {
            double f = px / (double) gw * 8; int i0 = (int) f % 8, i1 = (i0 + 1) % 8; double tt = f - Math.floor(f);
            double deg = keys[i0] * (1 - tt) + keys[i1] * tt;
            int py = mid - (int) Math.round(Math.max(-90, Math.min(90, deg)) / 90.0 * (gh / 2 - 2));
            g.fill(gx + px, py, gx + px + 1, py + 2, 0xFF3AD7FF);
        }
        for (int i = 0; i < 8; i++) {
            int cx = x + i * colW + colW / 2;
            int py = mid - (int) Math.round(Math.max(-90, Math.min(90, keys[i])) / 90.0 * (gh / 2 - 2));
            g.fill(cx - 2, py - 1, cx + 3, py + 4, JOINT_COL);
            String val = keys[i] + "\u00B0";
            g.drawString(font, val, cx - font.width(val) / 2, y + 122, TXT, false);
        }
        double need = j.getDouble("NeedSpeed"), max = j.getDouble("MaxSpeed");
        String sl = j.getInt("Motors") == 0 ? "No Servo Motor on this joint: it can't move." :
                String.format("Needs %.0f\u00B0/s, gears allow %.0f\u00B0/s%s", need, max, need > max + 0.01 ? "  (slows the gait)" : "");
        g.drawString(font, sl, x, y + 148, j.getInt("Motors") == 0 || need > max + 0.01 ? WARN : DIM, false);
        g.drawString(font, "\u25B2\u25BC \u00B15\u00B0 (Shift \u00B115\u00B0). The 8 keys play over one step.", x, y + 160, DIM, false);
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
                "§aAnimate§r tab: keyframes, phase and speed for each joint.",
                "§aKeys:§r W/S go, A/D turn, Space/C up/down, H hover,",
                "G this screen, K park, F5 camera, Shift leave the seat.",
                "§aPark§r turns it back into blocks; BUILD makes it move again."};
        int ly = y;
        for (String s : lines) { g.drawString(font, s, x, ly, TXT, false); ly += 12; }
    }

    @Override public boolean isPauseScreen() { return false; }
}
