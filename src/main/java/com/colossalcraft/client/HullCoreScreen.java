package com.colossalcraft.client;

import com.colossalcraft.colossus.StructureModel;
import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.network.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** "HULL-OS": the computer screen inside the Hull Core. Shows the joint tree, power and lets you set up, then assemble. */
public class HullCoreScreen extends Screen {
    private static final int W = 360, H = 230;
    private static final int BG = 0xF0060D08, FRAME = 0xFF2CFF7A, TXT = 0xFF7CFF9E, DIM = 0xFF3C8F55, HEAD = 0xFFD6FFE2, WARN = 0xFFFFD34A, ERR = 0xFFFF5A5A;
    public final BlockPos pos;
    private CompoundTag data;
    private int tab = 0, scroll = 0;
    private record Row(String text, int color, int joint) {}
    private final List<Row> rows = new ArrayList<>();

    public HullCoreScreen(BlockPos pos, CompoundTag data) { super(Component.literal("Hull Core")); this.pos = pos; this.data = data; }

    public void setData(CompoundTag d) { data = d; rebuild(); }
    public void setTab(int t) { tab = t; scroll = 0; rebuild(); }
    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }
    private void send(int action, int joint) { PacketDistributor.sendToServer(new Net.HullAction(pos, action, joint)); }

    @Override protected void init() { rebuild(); }

    private void rebuild() {
        clearWidgets(); buildRows();
        int l = left(), t = top();
        String[] tabs = {"STATUS", "JOINTS", "POWER"};
        for (int i = 0; i < 3; i++) {
            int ti = i;
            addRenderableWidget(Button.builder(Component.literal((tab == i ? "> " : "") + tabs[i]), b -> { tab = ti; scroll = 0; rebuild(); }).bounds(l + 6 + i * 62, t + 18, 60, 14).build());
        }
        addRenderableWidget(Button.builder(Component.literal("RESCAN"), b -> send(0, -1)).bounds(l + W - 176, t + 18, 52, 14).build());
        addRenderableWidget(Button.builder(Component.literal("HOVER " + (data.getBoolean("Hover") ? "ON" : "OFF")), b -> send(3, -1)).bounds(l + W - 122, t + 18, 58, 14).build());
        boolean ok = data.getList("Errors", Tag.TAG_STRING).isEmpty();
        Button asm = Button.builder(Component.literal(ok ? "ASSEMBLE" : "BLOCKED"), b -> send(4, -1)).bounds(l + W - 62, t + 18, 56, 14).build();
        asm.active = ok; addRenderableWidget(asm);
        if (tab == 1) {
            int y = t + 40;
            for (int i = scroll; i < rows.size() && y < t + H - 14; i++, y += 11) {
                Row r = rows.get(i);
                if (r.joint < 0) continue;
                CompoundTag j = joint(r.joint);
                int ji = r.joint;
                addRenderableWidget(Button.builder(Component.literal(StructureModel.MODES[j.getInt("Mode")]), b -> send(1, ji)).bounds(l + W - 92, y - 1, 50, 10).build());
                addRenderableWidget(Button.builder(Component.literal(j.getInt("Amp") + "°"), b -> send(2, ji)).bounds(l + W - 40, y - 1, 32, 10).build());
            }
        }
    }

    private CompoundTag joint(int id) {
        for (Tag t : data.getList("Joints", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getInt("Id") == id) return (CompoundTag) t;
        return new CompoundTag();
    }

    private void buildRows() {
        rows.clear();
        ListTag segs = data.getList("Segs", Tag.TAG_COMPOUND), joints = data.getList("Joints", Tag.TAG_COMPOUND);
        if (tab == 0) {
            rows.add(new Row(String.format("BLOCKS %d   MASS %s   SEGMENTS %d   JOINTS %d", data.getInt("Blocks"), StructureScan.tons(data.getDouble("Mass")), segs.size(), joints.size()), TXT, -1));
            rows.add(new Row("PILOT THRONE: " + data.getString("Seat"), TXT, -1));
            rows.add(new Row(String.format("THRUST  up %sN  down %sN  N %sN  S %sN  E %sN  W %sN", k("T_up"), k("T_down"), k("T_north"), k("T_south"), k("T_east"), k("T_west")), TXT, -1));
            double mass = data.getDouble("Mass"), lift = data.getDouble("T_up");
            rows.add(new Row(String.format("LIFT/WEIGHT  %.2f   (%s)", lift / (mass * 9.81), lift >= mass * 9.81 ? "can fly" : "can't fly yet"), lift >= mass * 9.81 ? TXT : WARN, -1));
            double gy = data.getDouble("Gyro");
            rows.add(new Row(String.format("GYROS %s  ->  STABILITY %d%%", StructureScan.tons(gy), (int) Math.min(100, 100 * gy / Math.max(1, mass))), TXT, -1));
            rows.add(new Row(String.format("DRIVE MOTORS %d (%sN)   SKY SAILS %d", data.getInt("DriveMotors"), k("Drive"), data.getInt("Sails")), TXT, -1));
            rows.add(new Row("", TXT, -1));
            ListTag er = data.getList("Errors", Tag.TAG_STRING), wr = data.getList("Warnings", Tag.TAG_STRING);
            if (er.isEmpty() && wr.isEmpty()) rows.add(new Row("ALL SYSTEMS NOMINAL. READY TO ASSEMBLE.", HEAD, -1));
            for (Tag t : er) rows.add(new Row("ERROR: " + t.getAsString(), ERR, -1));
            for (Tag t : wr) rows.add(new Row("WARN:  " + t.getAsString(), WARN, -1));
        } else if (tab == 1) {
            rows.add(new Row("JOINT TREE (from the Hull Core segment)          load | torque", DIM, -1));
            Map<Integer, List<CompoundTag>> jointsBySeg = new HashMap<>();
            for (Tag t : joints) { CompoundTag j = (CompoundTag) t; jointsBySeg.computeIfAbsent(j.getInt("Parent"), x -> new ArrayList<>()).add(j); }
            Map<Integer, CompoundTag> segById = new HashMap<>();
            for (Tag t : segs) segById.put(((CompoundTag) t).getInt("Id"), (CompoundTag) t);
            if (!segById.isEmpty()) addSeg(0, segById, jointsBySeg, "", true);
        } else {
            boolean inf = data.getBoolean("Infinite");
            double out = data.getDouble("Output"), draw = data.getDouble("MaxDraw"); long en = data.getLong("Energy");
            rows.add(new Row(String.format("OUTPUT %s CJ/t   MAX DRAW %s CJ/t   STORED %s", StructureScan.kilo(out), StructureScan.kilo(draw), inf ? "INFINITE" : StructureScan.kilo(en) + " CJ"), HEAD, -1));
            double use = Math.min(out, draw);
            rows.add(new Row(inf ? "ENDURANCE: forever (zero-point)" : use <= 0 ? "ENDURANCE: -" : String.format("ENDURANCE at full power: %.0f s", en / use / 20.0), TXT, -1));
            rows.add(new Row(out < draw ? "Engines can't feed everything at once: parts run slower." : "Engines cover full load.", out < draw ? WARN : TXT, -1));
            rows.add(new Row("", TXT, -1));
            rows.add(new Row("ENGINE                 STORED             OUTPUT    STATE", DIM, -1));
            for (Tag t : data.getList("Engines", Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) t;
                String stored = e.getBoolean("ZP") ? (e.getLong("Energy") < 0 ? "PRIMED" : "-") : StructureScan.kilo(e.getLong("Energy")) + "/" + StructureScan.kilo(e.getLong("Cap"));
                String st = e.getBoolean("Run") && e.getString("Note").isEmpty() ? "ONLINE" : e.getString("Note");
                rows.add(new Row(String.format("%-22s %-18s %6s   %s", e.getString("Name"), stored, StructureScan.kilo(e.getInt("Out")), st), e.getBoolean("Run") ? TXT : WARN, -1));
            }
            rows.add(new Row("", TXT, -1));
            rows.add(new Row("CONSUMERS: thrusters, joint motors, drive motors, gyros (" + StructureScan.kilo(data.getDouble("GyroPow")) + " CJ/t)", DIM, -1));
        }
    }

    private String k(String key) { return StructureScan.kilo(data.getDouble(key)); }

    private void addSeg(int id, Map<Integer, CompoundTag> segs, Map<Integer, List<CompoundTag>> jbs, String indent, boolean root) {
        CompoundTag s = segs.get(id); if (s == null) return;
        rows.add(new Row(indent + (root ? "[CORE] " : "[SEG ") + (root ? "" : id + "] ") + StructureScan.tons(s.getDouble("Mass")) + ", " + s.getInt("Blocks") + " blocks", root ? HEAD : TXT, -1));
        for (CompoundTag j : jbs.getOrDefault(id, List.of())) {
            double ratio = j.getDouble("Ratio"); boolean stalled = j.getBoolean("Stalled");
            int n = (int) Math.round(Math.sqrt(j.getInt("K")));
            String size = n * n == j.getInt("K") ? n + "x" + n : j.getInt("K") + "blk";
            String torque = j.getInt("Motors") == 0 ? "no motor" : StructureScan.kilo(j.getDouble("Torque")) + "/" + StructureScan.kilo(j.getDouble("Req"));
            String txt = String.format("%s +-J%d %s %s %s-axis  %3d%% | %s", indent, j.getInt("Id"), j.getString("Tier").toUpperCase(), size, j.getString("Axis").toUpperCase(), (int) (ratio * 100), torque);
            rows.add(new Row(txt, ratio > 1 ? ERR : stalled ? WARN : TXT, j.getInt("Id")));
            for (Tag c : j.getList("Children", Tag.TAG_COMPOUND)) addSeg(((CompoundTag) c).getInt("S"), segs, jbs, indent + "   ", false);
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        int max = Math.max(0, rows.size() - 16);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(dy)));
        rebuild(); return true;
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        int l = left(), t = top();
        g.fill(l - 2, t - 2, l + W + 2, t + H + 2, FRAME);
        g.fill(l, t, l + W, t + H, BG);
        for (int y = t; y < t + H; y += 2) g.fill(l, y, l + W, y + 1, 0x18000000);
        g.fill(l, t + 35, l + W, t + 36, DIM);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int l = left(), t = top();
        String blink = (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : " ";
        g.drawString(font, "HULL-OS v1.0  //  COLOSSUS CONFIGURATOR " + blink, l + 6, t + 6, HEAD, false);
        int y = t + 40;
        for (int i = scroll; i < rows.size() && y < t + H - 14; i++, y += 11) {
            Row r = rows.get(i);
            int maxW = r.joint >= 0 ? W - 104 : W - 12;
            g.drawString(font, font.plainSubstrByWidth(r.text, maxW), l + 6, y, r.color, false);
        }
        g.drawString(font, "scroll: wheel   modes: LOCKED / WALK A / WALK B / SPIN / SWAY", l + 6, t + H - 11, DIM, false);
    }

    @Override public boolean isPauseScreen() { return false; }
}
