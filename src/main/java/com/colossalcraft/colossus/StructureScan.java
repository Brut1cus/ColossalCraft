package com.colossalcraft.colossus;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.Config;
import com.colossalcraft.block.HullCoreBlockEntity;
import com.colossalcraft.tier.EngineTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Flood-fills from a Hull Core and analyses the result for the Hull Core screen and for assembly. */
public final class StructureScan {
    public static final TagKey<Block> BLACKLIST = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ColossalCraft.MODID, "assembly_blacklist"));

    public static class Result {
        public final List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        public final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public final Map<BlockPos, CompoundTag> bes = new HashMap<>();
        public StructureModel model; public CompoundTag jointCfg = new CompoundTag(); public boolean hover = true;
        public boolean built; public int entityId = -1; public long liveEnergy = -1; public boolean infinite;
        public boolean ok() { return errors.isEmpty() && model != null; }
    }

    public static boolean canTake(BlockState s) {
        // Everything can be part of a Colossus except air, loose fluids, unbreakable blocks and scaffolding (tag colossalcraft:assembly_blacklist)
        return !s.isAir() && !(s.getBlock() instanceof LiquidBlock) && !s.is(BLACKLIST) && s.getBlock().defaultDestroyTime() >= 0;
    }

    public static Result scan(Level level, BlockPos heart) {
        Result r = new Result();
        if (level.getBlockEntity(heart) instanceof HullCoreBlockEntity hc) { r.jointCfg = hc.joints.copy(); r.hover = hc.hover; }
        int max = Config.MAX_BLOCKS.get();
        ArrayDeque<BlockPos> q = new ArrayDeque<>(); Set<BlockPos> seen = new HashSet<>();
        q.add(heart); seen.add(heart);
        boolean tooBig = false;
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            BlockState s = level.getBlockState(p);
            if (!canTake(s)) continue;
            if (r.blocks.size() >= max) { tooBig = true; break; }
            BlockPos local = p.subtract(heart);
            r.blocks.put(local, s);
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) { CompoundTag tag = be.saveWithFullMetadata(level.registryAccess()); com.colossalcraft.compat.Compat.onLift(be, tag); r.bes.put(local, tag); }
            for (Direction d : Direction.values()) { BlockPos n = p.relative(d); if (seen.add(n)) q.add(n); }
        }
        if (tooBig) { r.errors.add("Over " + max + " blocks: it's probably touching the ground or a wall."); r.errors.add("Build on Gantry Scaffold so it touches nothing else, or raise maxBlocks in config."); }
        r.model = new StructureModel(r.blocks, r.bes, r.jointCfg);
        analyze(r);
        return r;
    }


    /** Same report for a Colossus that is already built (for the Hull Core screen while flying). */
    public static Result fromEntity(ColossusEntity c) {
        Result r = new Result();
        r.blocks.putAll(c.blocks); r.bes.putAll(c.bes); r.jointCfg = c.jointCfg.copy(); r.hover = c.hover();
        r.model = c.model; r.built = true; r.entityId = c.getId();
        r.liveEnergy = c.energy; r.infinite = c.model.infinite;
        analyze(r);
        return r;
    }

    public static void analyze(Result r) {
        StructureModel m = r.model;
        int segMax = Config.MAX_SEGMENT_BLOCKS.get();
        for (StructureModel.Seg s : m.segs) if (s.blocks.size() > segMax) r.errors.add("Segment " + s.id + " has " + s.blocks.size() + " blocks (limit " + segMax + "). Split it with joints or raise maxBlocksPerSegment.");
        if (m.hearts > 1) r.errors.add("Only ONE Hull Core per Colossus. Assemble the inner one first to nest it.");
        if (m.seat == null) r.warnings.add("No Pilot Throne: nobody can steer it.");
        if (m.output <= 0) r.warnings.add("No running engine: no power.");
        else if ((r.liveEnergy >= 0 ? r.liveEnergy : m.energy) <= 0 && !m.infinite) r.warnings.add("Engines are empty: add fuel.");
        for (StructureModel.Joint j : m.joints) {
            if (j.staticRatio > 1) r.warnings.add("Joint J" + j.id + " overloaded " + (int) (j.staticRatio * 100) + "%: it will snap!");
            if (j.mode != StructureModel.LOCKED && j.stalled()) r.warnings.add(j.motors == 0 ? "Joint J" + j.id + " is set to move, but no Servo Motor touches it."
                    : "Joint J" + j.id + " motors too weak: " + kilo(j.motorTorque) + " of " + kilo(j.reqTorque) + " N*m needed.");
        }
        if (m.hasWalkers() && m.gyroCap < m.mass) r.warnings.add("Gyros too weak for walking: " + (int) (100 * m.gyroCap / m.mass) + "% stable. Add gyros.");
        boolean anyThrust = false; for (double v : m.thrustCap) anyThrust |= v > 0;
        if (!anyThrust && !m.hasWalkers() && m.driveMotors == 0) r.warnings.add("No thrusters, walking joints or drive motors: it can't move itself.");
    }

    public static String tons(double kg) { return kg >= 1e6 ? String.format("%,.0f t", kg / 1000) : String.format("%.1f t", kg / 1000); }

    public static String kilo(double v) {
        if (Math.abs(v) >= 1e9) return String.format("%.2fG", v / 1e9);
        if (Math.abs(v) >= 1e6) return String.format("%.2fM", v / 1e6);
        if (Math.abs(v) >= 1e3) return String.format("%.1fk", v / 1e3);
        return String.format("%.0f", v);
    }

    /** Data for the Hull Core screen. */
    public static CompoundTag toTag(Result r) {
        CompoundTag t = new CompoundTag(); StructureModel m = r.model;
        ListTag er = new ListTag(); r.errors.forEach(s -> er.add(StringTag.valueOf(s))); t.put("Errors", er);
        ListTag wr = new ListTag(); r.warnings.forEach(s -> wr.add(StringTag.valueOf(s))); t.put("Warnings", wr);
        t.putBoolean("Hover", r.hover);
        t.putInt("Blocks", r.blocks.size()); t.putDouble("Mass", m.mass);
        t.putString("Seat", m.seat == null ? "none" : "facing " + m.forward.getName());
        t.putDouble("Gyro", m.gyroCap); t.putDouble("GyroPow", m.gyroPower);
        t.putDouble("Output", m.output); t.putLong("Energy", r.liveEnergy >= 0 ? r.liveEnergy : m.energy); t.putBoolean("Infinite", m.infinite);
        t.putBoolean("Built", r.built); t.putInt("Entity", r.entityId);
        t.putDouble("MaxDraw", m.maxDraw()); t.putDouble("Drive", m.driveForce); t.putInt("DriveMotors", m.driveMotors);
        t.putInt("Sails", m.sails); t.putInt("Thrusters", m.thrusters);
        for (Direction d : Direction.values()) t.putDouble("T_" + d.getName(), m.thrustCap[d.ordinal()]);
        ListTag segs = new ListTag();
        for (StructureModel.Seg s : m.segs) {
            CompoundTag c = new CompoundTag(); c.putInt("Id", s.id); c.putInt("Parent", s.parentJoint); c.putInt("Blocks", s.blocks.size()); c.putDouble("Mass", s.mass);
            segs.add(c);
        }
        t.put("Segs", segs);
        ListTag js = new ListTag();
        for (StructureModel.Joint j : m.joints) {
            CompoundTag c = new CompoundTag();
            c.putInt("Id", j.id); c.putInt("Parent", j.parentSeg); c.putString("Tier", j.tier.id); c.putInt("K", j.k); c.putDouble("Area", j.area); c.putString("Axis", j.axis.getName());
            c.putDouble("Sub", j.subMass); c.putDouble("Ratio", j.staticRatio); c.putDouble("FCap", j.forceCap); c.putDouble("MCap", j.momentCap);
            c.putDouble("Torque", j.motorTorque); c.putDouble("Req", j.reqTorque); c.putInt("Motors", j.motors);
            c.putInt("Mode", j.mode); c.putInt("Amp", j.amp); c.putBoolean("Stalled", j.stalled());
            ListTag ch = new ListTag(); for (int cs : j.childSegs) { CompoundTag x = new CompoundTag(); x.putInt("S", cs); ch.add(x); } c.put("Children", ch);
            js.add(c);
        }
        t.put("Joints", js);
        ListTag en = new ListTag();
        for (StructureModel.EngineInfo e : m.engines) {
            CompoundTag c = new CompoundTag(); c.putString("Name", e.name()); c.putLong("Energy", e.energy()); c.putLong("Cap", e.capacity());
            c.putInt("Out", e.output()); c.putBoolean("Run", e.running()); c.putString("Note", e.note()); c.putBoolean("ZP", e.zeroPoint());
            en.add(c);
        }
        t.put("Engines", en);
        return t;
    }
}
