package colossus.assembly;

import colossus.assembly.graph.CoreTier;
import colossus.assembly.graph.StructureScanner;
import colossus.core.Colossus;
import colossus.core.ColossusConfig;
import colossus.core.ColossusManager;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import colossus.shipyard.PlotAllocator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Build-to-life steps 4–7: scan, validate, assemble (move blocks to ship space with block entity data intact and spawn
 * the physics bodies) and disassemble (snap every Segment to the grid and put its blocks back).
 */
public final class AssemblyService {
    private AssemblyService() {}

    /** Flags for silent block moves: no neighbour updates, no shape updates, no drops. */
    private static final int MOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    public static StructureScanner.Result scan(ServerLevel level, BlockPos core) {
        CoreTier tier = level.getBlockState(core).getBlock() instanceof HullCoreBlock hc ? hc.tier : CoreTier.WOODEN;
        int serverMax = ColossusConfig.MAX_BLOCKS_PER_COLOSSUS.get();
        LevelVoxelSource src = new LevelVoxelSource(level);
        CoreTier.Budget budget = tier.budget(0, serverMax);
        int limit = Math.min(serverMax, tier.growth == 1 ? serverMax : budget.blocks()) + 1;
        StructureScanner.Result r = StructureScanner.scan(src, core.getX(), core.getY(), core.getZ(), budget, limit);
        if (tier.growth == 1 && r.latticeBlocks() > 0 && ColossusConfig.LATTICE_GROWTH.get()) {
            r = StructureScanner.scan(src, core.getX(), core.getY(), core.getZ(), tier.budget(r.latticeBlocks(), serverMax), limit);
        }
        return r;
    }

    /** Chat report of a scan (stand-in for the Assembly GUI). */
    public static void report(ServerLevel level, BlockPos core, ServerPlayer player) {
        StructureScanner.Result r = scan(level, core);
        player.sendSystemMessage(Component.translatable("colossus.scan.summary", r.blockCount(), r.segments().size(), r.joints().size(),
                String.format(Locale.ROOT, "%.1f", r.totalMass() / 1000.0)).withStyle(ChatFormatting.AQUA));
        for (String e : r.errors()) player.sendSystemMessage(Component.literal("✖ " + e).withStyle(ChatFormatting.RED));
        for (String w : r.warnings()) player.sendSystemMessage(Component.literal("⚠ " + w).withStyle(ChatFormatting.YELLOW));
        if (r.ok()) player.sendSystemMessage(Component.translatable("colossus.scan.ready").withStyle(ChatFormatting.GREEN));
    }

    // ------------------------------------------------------------------ assemble

    public static @Nullable UUID assemble(ServerLevel level, BlockPos core, @Nullable ServerPlayer player) {
        ColossusSavedData data = ColossusSavedData.get(level);
        if (data.all().size() >= ColossusConfig.MAX_COLOSSI_PER_LEVEL.get()) {
            tell(player, Component.translatable("colossus.assembly.too_many").withStyle(ChatFormatting.RED));
            return null;
        }
        StructureScanner.Result r = scan(level, core);
        if (!r.ok()) {
            tell(player, Component.translatable("colossus.assembly.failed").withStyle(ChatFormatting.RED));
            for (String e : r.errors()) tell(player, Component.literal("✖ " + e).withStyle(ChatFormatting.RED));
            return null;
        }
        UUID id = UUID.randomUUID();
        ColossusRecord rec = new ColossusRecord(id);
        rec.owner = player == null ? null : player.getUUID();
        rec.coreTier = ((HullCoreBlock) level.getBlockState(core).getBlock()).tier.name();
        rec.origin = core.immutable();

        // 1. capture every block and its block-entity data before touching the world
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        for (StructureScanner.SegmentPlan sp : r.segments()) for (long[] c : sp.cells()) {
            BlockPos p = new BlockPos((int) c[0], (int) c[1], (int) c[2]);
            states.put(p, level.getBlockState(p));
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) tags.put(p, be.saveWithFullMetadata(level.registryAccess()));
        }

        // 2. one plot per Segment; copy blocks there
        for (StructureScanner.SegmentPlan sp : r.segments()) {
            PlotAllocator.Plot plot = data.plots().allocate();
            ColossusRecord.SegmentRecord s = new ColossusRecord.SegmentRecord();
            s.plotIndex = plot.index();
            s.plotAnchor = new BlockPos(plot.centerX(), core.getY(), plot.centerZ());
            s.cells = new long[sp.cells().size()];
            s.comRel = sp.mass().centerOfMass();
            s.mass = sp.mass().mass();
            s.inertia = sp.mass().inertia();
            s.root = sp.root();
            s.pose = new Pose(new Vec3(core.getX(), core.getY(), core.getZ()).add(s.comRel), Quat.IDENTITY);
            Set<ChunkPos> chunks = new HashSet<>();
            for (int i = 0; i < sp.cells().size(); i++) {
                long[] c = sp.cells().get(i);
                BlockPos world = new BlockPos((int) c[0], (int) c[1], (int) c[2]);
                BlockPos rel = world.subtract(core);
                s.cells[i] = rel.asLong();
                BlockPos dst = s.plotAnchor.offset(rel);
                if (chunks.add(new ChunkPos(dst))) level.setChunkForced(dst.getX() >> 4, dst.getZ() >> 4, true);
                level.setBlock(dst, states.get(world), MOVE_FLAGS);
                CompoundTag tag = tags.get(world);
                if (tag != null) {
                    BlockEntity nbe = level.getBlockEntity(dst);
                    if (nbe != null) {
                        nbe.loadWithComponents(tag, level.registryAccess());
                        if (nbe instanceof HullCoreBlockEntity hc && world.equals(core)) hc.setColossusId(id);
                        nbe.setChanged();
                    }
                }
            }
            rec.segments.add(s);
        }
        for (StructureScanner.JointPlan jp : r.joints()) {
            ColossusRecord.JointRecord j = new ColossusRecord.JointRecord();
            j.kind = jp.type();
            j.parent = jp.parent();
            j.child = jp.child();
            j.anchorRel = jp.anchor().sub(new Vec3(core.getX(), core.getY(), core.getZ()));
            j.axis = jp.axis();
            j.footprint = jp.footprint();
            rec.joints.add(j);
        }

        // 3. clear the originals (block entities first, so containers do not spill)
        for (BlockPos p : states.keySet()) level.removeBlockEntity(p);
        for (BlockPos p : states.keySet()) level.setBlock(p, Blocks.AIR.defaultBlockState(), MOVE_FLAGS);
        for (BlockPos p : states.keySet()) level.updateNeighborsAt(p, Blocks.AIR);

        data.put(rec);
        ColossusManager.get(level).spawn(rec);
        Colossus.LOG.info("Assembled Colossus {}: {} blocks, {} segments, {} joints", id, r.blockCount(), r.segments().size(), r.joints().size());
        tell(player, Component.translatable("colossus.assembly.done", r.blockCount(), r.segments().size()).withStyle(ChatFormatting.GREEN));
        for (String w : r.warnings()) tell(player, Component.literal("⚠ " + w).withStyle(ChatFormatting.YELLOW));
        return id;
    }

    // ------------------------------------------------------------------ disassemble

    /** Quarter turn about +Y closest to a rotation, and the matching Minecraft {@link Rotation}. */
    record Snap(Quat quat, Rotation rotation) {}

    static Snap snap(Quat q) {
        Rotation[] rots = {Rotation.NONE, Rotation.COUNTERCLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.CLOCKWISE_90};
        Snap best = null;
        double bestDot = -1;
        for (int k = 0; k < 4; k++) {
            Quat c = Quat.axisAngle(Vec3.Y, k * Math.PI / 2);
            double d = Math.abs(c.dot(q));
            if (d > bestDot) { bestDot = d; best = new Snap(c, rots[k]); }
        }
        return best;
    }

    /** World block position a Segment cell lands on when snapped. */
    static BlockPos target(ColossusRecord.SegmentRecord s, Pose pose, Snap sn, long cell) {
        Vec3 c = pose.position().add(sn.quat().rotate(s.local(BlockPos.of(cell))));
        return BlockPos.containing(c.x(), c.y(), c.z());
    }

    public static boolean disassemble(ServerLevel level, UUID id, @Nullable ServerPlayer player) {
        ColossusSavedData data = ColossusSavedData.get(level);
        ColossusRecord rec = data.byId(id);
        if (rec == null) return false;
        ColossusManager mgr = ColossusManager.get(level);

        // 1. where does every block go?
        Map<BlockPos, BlockPos> moves = new LinkedHashMap<>(); // plot pos -> world pos
        Map<BlockPos, Rotation> rotations = new HashMap<>();
        List<BlockPos> blocked = new ArrayList<>();
        Set<BlockPos> taken = new HashSet<>();
        for (int si = 0; si < rec.segments.size(); si++) {
            ColossusRecord.SegmentRecord s = rec.segments.get(si);
            Pose pose = mgr.pose(rec, si);
            Snap sn = snap(pose.rotation());
            for (long cell : s.cells) {
                BlockPos from = s.plotPos(cell), to = target(s, pose, sn, cell);
                if (!taken.add(to) || !placeable(level, to)) blocked.add(to);
                moves.put(from, to);
                rotations.put(from, sn.rotation());
            }
        }
        if (!blocked.isEmpty()) {
            tell(player, Component.translatable("colossus.disassembly.blocked", blocked.size(), blocked.get(0).toShortString()).withStyle(ChatFormatting.RED));
            return false;
        }

        // 2. stop physics, then copy blocks back
        mgr.despawn(id);
        mgr.flush();
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (BlockPos from : moves.keySet()) {
            states.put(from, level.getBlockState(from));
            BlockEntity be = level.getBlockEntity(from);
            if (be != null) tags.put(from, be.saveWithFullMetadata(level.registryAccess()));
        }
        for (Map.Entry<BlockPos, BlockPos> m : moves.entrySet()) {
            BlockPos from = m.getKey(), to = m.getValue();
            BlockState st = states.get(from).rotate(rotations.get(from));
            level.setBlock(to, st, MOVE_FLAGS);
            CompoundTag tag = tags.get(from);
            if (tag != null) {
                BlockEntity nbe = level.getBlockEntity(to);
                if (nbe != null) {
                    nbe.loadWithComponents(tag, level.registryAccess());
                    if (nbe instanceof HullCoreBlockEntity hc) hc.setColossusId(null);
                    nbe.setChanged();
                }
            }
        }
        // 3. empty the plots and release their chunks
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos from : moves.keySet()) {
            level.removeBlockEntity(from);
            level.setBlock(from, Blocks.AIR.defaultBlockState(), MOVE_FLAGS);
            chunks.add(new ChunkPos(from));
        }
        for (ChunkPos c : chunks) level.setChunkForced(c.x, c.z, false);
        for (BlockPos to : moves.values()) level.updateNeighborsAt(to, level.getBlockState(to).getBlock());

        data.remove(id);
        tell(player, Component.translatable("colossus.disassembly.done", moves.size()).withStyle(ChatFormatting.GREEN));
        return true;
    }

    private static boolean placeable(ServerLevel level, BlockPos p) {
        if (level.isOutsideBuildHeight(p) || ColossusSavedData.PLOTS_REGION.inShipSpace(p.getX(), p.getZ())) return false;
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.canBeReplaced();
    }

    private static void tell(@Nullable ServerPlayer p, Component c) { if (p != null) p.sendSystemMessage(c); }
}
