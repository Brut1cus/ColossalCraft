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
    private static final String[] MODE_NAMES = {"Locked", "Walk (left)", "Walk (right)", "Spin", "Sway"};
    private static final String[] MODE_HELP = {
            "Doesn't move. Use for rigid parts.",
            "Swings while walking forward. Use on one leg...",
            "...and this one on the other leg (opposite timing).",
            "Turns all the time while powered: propellers, wheels, turrets.",
            "Slow idle motion: tails, antennae, heads."};
    private static final String[] TABS = {"Overview", "Joints", "Power", "Help"};

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

        if (tab == 1) jointWidgets(l + NAV + 8 + 168, t + 30);
    }

    private void jointWidgets(int x, int y) {
        CompoundTag j = joint(selected);
        if (j == null) return;
        int mode = j.getInt("Mode");
        int by = y + 66;
        for (int i = 0; i < MODE_NAMES.length; i++) {
            int mi = i;
            Button b = Button.builder(Component.literal((mode == i ? "> " : "") + MODE_NAMES[i]), z -> send(1, selected, mi)).bounds(x, by + i * 15, 92, 14).build();
            b.setTooltip(Tooltip.create(Component.literal(MODE_HELP[i])));
            b.active = mode != i; addRenderableWidget(b);
        }
        int amp = j.getInt("Amp");
        int ay = by + MODE_NAMES.length * 15 + 3;
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
        if (tab == 1) {
            int x0 = left() + NAV + 8, y0 = top() + 42;
            if (mx >= x0 && mx < x0 + 160 && my >= y0) {
                int i = (int) ((my - y0) / 11) + scroll;
                if (i >= 0 && i < treeRows.size() && treeRows.get(i).joint >= 0 && my < top() + H - 34) { selected = treeRows.get(i).joint; rebuild(); return true; }
            }
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
            case 1 -> joints(g, cx, cy);
            case 2 -> power(g, cx, cy);
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
            if ((m == 1 || m == 2) && !j.getBoolean("Stalled")) walkers = true;
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
            StringBuilder ft = new StringBuilder("It stands on legs. To stay up it needs a powered gyro in a foot, and every joint from that foot up to the Hull Core needs a strong enough Servo Motor.");
            for (Tag f : list("Feet")) { CompoundTag fc = (CompoundTag) f; ft.append("\nFoot part ").append(fc.getInt("Seg")).append(": ").append(fc.getBoolean("Ok") ? "OK" : fc.getString("Problem")); }
            check(g, x, cy, data.getBoolean("FootOK") ? 0 : 2, "Footing (gyro in a foot)", ft.toString()); cy += 12;
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
        for (Tag s : strings("Warnings")) { if (py > top() + H - 46) break; g.drawString(font, font.plainSubstrByWidth("! " + s.getAsString(), W - NAV - 16), x, py, WARN, false); py += 10; }
    }

    private void joints(GuiGraphics g, int x, int y) {
        g.drawString(font, "JOINT TREE  (click one)", x, y, DIM, false);
        g.fill(x - 2, y + 10, x + 162, top() + H - 36, PANEL);
        int ry = y + 12;
        for (int i = scroll; i < treeRows.size() && ry < top() + H - 44; i++, ry += 11) {
            Row r = treeRows.get(i);
            if (r.joint >= 0 && r.joint == selected) g.fill(x - 2, ry - 1, x + 162, ry + 10, 0xFF1E4A2A);
            String prefix = "  ".repeat(Math.min(r.indent, 8)) + (r.joint >= 0 ? "◆ " : "■ ");
            g.drawString(font, font.plainSubstrByWidth(prefix + r.text, 158), x, ry, r.color, false);
        }
        if (treeRows.size() <= 1) {
            g.drawString(font, "No joints yet.", x + 2, y + 26, WARN, false);
            g.drawString(font, "Place Bearing Joints", x + 2, y + 40, DIM, false);
            g.drawString(font, "between parts to make", x + 2, y + 51, DIM, false);
            g.drawString(font, "limbs, wheels and tails.", x + 2, y + 62, DIM, false);
        }
        CompoundTag j = joint(selected);
        if (j == null) return;
        int dx = x + 168, dw = W - NAV - 8 - 174;
        g.drawString(font, "J" + j.getInt("Id") + "  " + cap(j.getString("Tier")) + " joint", dx, y, HEAD, false);
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
        int hy = y + 66 + MODE_NAMES.length * 15 + 3;
        g.drawString(font, "Swing " + j.getInt("Amp") + "°", dx + 28, hy + 4, TXT, false);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(MODE_HELP[mode]), dw - 98);
        int ly = y + 68; for (var ln : lines) { g.drawString(font, ln, dx + 98, ly, DIM, false); ly += 10; }
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
                "§aKeys:§r W/S go, A/D turn, Space/C up/down, H hover,",
                "G this screen, K park, F5 camera, Shift leave the seat.",
                "§aPark§r turns it back into blocks; BUILD makes it move again."};
        int ly = y;
        for (String s : lines) { g.drawString(font, s, x, ly, TXT, false); ly += 12; }
    }

    @Override public boolean isPauseScreen() { return false; }
}
