package com.colossalcraft.network;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.block.HullCoreBlockEntity;
import com.colossalcraft.colossus.ColossusCollision;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureModel;
import com.colossalcraft.colossus.StructureScan;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Net {
    private static ResourceLocation id(String s) { return ResourceLocation.fromNamespaceAndPath(ColossalCraft.MODID, s); }

    public record PilotInput(float fwd, float turn, boolean up, boolean down, boolean sprint, boolean jump) implements CustomPacketPayload {
        public static final Type<PilotInput> TYPE = new Type<>(id("pilot_input"));
        public static final StreamCodec<ByteBuf, PilotInput> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, PilotInput::fwd, ByteBufCodecs.FLOAT, PilotInput::turn,
                ByteBufCodecs.BOOL, PilotInput::up, ByteBufCodecs.BOOL, PilotInput::down,
                ByteBufCodecs.BOOL, PilotInput::sprint, ByteBufCodecs.BOOL, PilotInput::jump, PilotInput::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** 0 = enter/leave Pilot Throne, 1 = disassemble, 2 = toggle hover */
    public record PilotAction(int action) implements CustomPacketPayload {
        public static final Type<PilotAction> TYPE = new Type<>(id("pilot_action"));
        public static final StreamCodec<ByteBuf, PilotAction> CODEC = ByteBufCodecs.VAR_INT.map(PilotAction::new, PilotAction::action);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Hull Core screen data (server -> client). */
    public record HullData(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
        public static final Type<HullData> TYPE = new Type<>(id("hull_data"));
        public static final StreamCodec<ByteBuf, HullData> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, HullData::pos, ByteBufCodecs.COMPOUND_TAG, HullData::data, HullData::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Hull Core screen button (client -> server). entity = -1 for a Hull Core block, else the built Colossus.
     *  actions: 0 rescan, 1 set joint mode, 2 set swing angle, 3 toggle hover, 4 build, 5 park,
     *  6 set keyframe (value = index << 16 | angle + 180), 7 set phase, 8 set speed (x100), 9 set gait speed (x100), 10 toggle loop,
     *  11 IK solver, 12 gait type, 13 key for action #joint (value = GLFW key, -1 = default), 15 step group of a joint */
    public record HullAction(BlockPos pos, int entity, int action, int joint, int value) implements CustomPacketPayload {
        public static final Type<HullAction> TYPE = new Type<>(id("hull_action"));
        public static final StreamCodec<ByteBuf, HullAction> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, HullAction::pos, ByteBufCodecs.VAR_INT, HullAction::entity,
                ByteBufCodecs.VAR_INT, HullAction::action, ByteBufCodecs.VAR_INT, HullAction::joint, ByteBufCodecs.VAR_INT, HullAction::value, HullAction::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Player right-clicked a block on a built Colossus. what: 0 Hull Core screen, 1 sit in Pilot Throne */
    public record ClickColossus(int entity, int what) implements CustomPacketPayload {
        public static final Type<ClickColossus> TYPE = new Type<>(id("click_colossus"));
        public static final StreamCodec<ByteBuf, ClickColossus> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ClickColossus::entity, ByteBufCodecs.VAR_INT, ClickColossus::what, ClickColossus::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** New joint setup for a built Colossus (server -> clients). */
    public record ColossusConfig(int entity, CompoundTag cfg) implements CustomPacketPayload {
        public static final Type<ColossusConfig> TYPE = new Type<>(id("colossus_config"));
        public static final StreamCodec<ByteBuf, ColossusConfig> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ColossusConfig::entity, ByteBufCodecs.COMPOUND_TAG, ColossusConfig::cfg, ColossusConfig::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record StructureRemoved(int entity, CompoundTag data) implements CustomPacketPayload {
        public static final Type<StructureRemoved> TYPE = new Type<>(id("structure_removed"));
        public static final StreamCodec<ByteBuf, StructureRemoved> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, StructureRemoved::entity, ByteBufCodecs.COMPOUND_TAG, StructureRemoved::data, StructureRemoved::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Blocks of a built Colossus changed (server -> clients): {Del: [pos], Set: [{P, S, E?}]}. */
    public record ColossusBlocks(int entity, CompoundTag data) implements CustomPacketPayload {
        public static final Type<ColossusBlocks> TYPE = new Type<>(id("colossus_blocks"));
        public static final StreamCodec<ByteBuf, ColossusBlocks> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ColossusBlocks::entity, ByteBufCodecs.COMPOUND_TAG, ColossusBlocks::data, ColossusBlocks::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** A player used (0) or hit (1) a block on a built Colossus (client -> server). Local block position and local face. */
    public record ColossusBlock(int entity, long pos, int face, int action) implements CustomPacketPayload {
        public static final Type<ColossusBlock> TYPE = new Type<>(id("colossus_block"));
        public static final StreamCodec<ByteBuf, ColossusBlock> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ColossusBlock::entity, ByteBufCodecs.VAR_LONG, ColossusBlock::pos, ByteBufCodecs.VAR_INT, ColossusBlock::face, ByteBufCodecs.VAR_INT, ColossusBlock::action, ColossusBlock::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Step Controller map (server -> client). */
    public record StepData(CompoundTag data) implements CustomPacketPayload {
        public static final Type<StepData> TYPE = new Type<>(id("step_data"));
        public static final StreamCodec<ByteBuf, StepData> CODEC = StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, StepData::data, StepData::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Ask for a fresh Step Controller map: a built Colossus (entity >= 0) or a parked controller block (pos). */
    public record StepRequest(int entity, long pos) implements CustomPacketPayload {
        public static final Type<StepRequest> TYPE = new Type<>(id("step_request"));
        public static final StreamCodec<ByteBuf, StepRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, StepRequest::entity, ByteBufCodecs.VAR_LONG, StepRequest::pos, StepRequest::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("1");
        r.playToServer(PilotInput.TYPE, PilotInput.CODEC, Net::onInput);
        r.playToServer(PilotAction.TYPE, PilotAction.CODEC, Net::onPilotAction);
        r.playToServer(HullAction.TYPE, HullAction.CODEC, Net::onHullAction);
        r.playToServer(ClickColossus.TYPE, ClickColossus.CODEC, Net::onClickColossus);
        r.playToClient(ColossusConfig.TYPE, ColossusConfig.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.colossusConfig(p.entity(), p.cfg())));
        r.playToClient(HullData.TYPE, HullData.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.hullData(p.pos(), p.data())));
        r.playToClient(ColossusBlocks.TYPE, ColossusBlocks.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.colossusBlocks(p.entity(), p.data())));
        r.playToServer(ColossusBlock.TYPE, ColossusBlock.CODEC, (p, ctx) -> ctx.enqueueWork(() -> { if (ctx.player() instanceof ServerPlayer sp) com.colossalcraft.colossus.ColossusInteract.handle(sp, p); }));
        r.playToClient(StepData.TYPE, StepData.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.stepData(p.data())));
        r.playToServer(StepRequest.TYPE, StepRequest.CODEC, (p, ctx) -> ctx.enqueueWork(() -> { if (ctx.player() instanceof ServerPlayer sp) com.colossalcraft.colossus.StepPlanner.handle(sp, p); }));
        r.playToClient(StructureRemoved.TYPE, StructureRemoved.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.structureRemoved(p.entity(), p.data())));
    }

    /** Which screen each player has open: Hull Core (false) or Locomotion Controller (true). */
    private static final java.util.Map<java.util.UUID, Boolean> LOCO_SCREEN = new java.util.concurrent.ConcurrentHashMap<>();

    public static void sendScan(ServerPlayer sp, BlockPos pos, StructureScan.Result r) {
        CompoundTag t = StructureScan.toTag(r);
        t.putBoolean("Loco", LOCO_SCREEN.getOrDefault(sp.getUUID(), false));
        PacketDistributor.sendToPlayer(sp, new HullData(pos, t));
    }
    public static void openHullParked(ServerPlayer sp, BlockPos heart) { LOCO_SCREEN.put(sp.getUUID(), false); sendScan(sp, heart, StructureScan.scan(sp.level(), heart)); }

    /** Locomotion Controller (not built): find the Hull Core it belongs to and open the kinematics screen. */
    public static void openLocoParked(ServerPlayer sp, BlockPos controller) {
        BlockPos heart = findHeart(sp.level(), controller);
        if (heart == null) { sp.displayClientMessage(Component.literal("This Locomotion Controller isn't connected to a Hull Core."), true); return; }
        LOCO_SCREEN.put(sp.getUUID(), true);
        sendScan(sp, heart, StructureScan.scan(sp.level(), heart));
    }
    public static BlockPos findHeart(net.minecraft.world.level.Level level, BlockPos from) {
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(); java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        q.add(from); seen.add(from);
        int max = com.colossalcraft.Config.MAX_BLOCKS.get();
        while (!q.isEmpty() && seen.size() < max) {
            BlockPos p = q.poll();
            var st = level.getBlockState(p);
            if (st.getBlock() instanceof com.colossalcraft.block.HullCoreBlock) return p;
            if (!StructureScan.canTake(st)) continue;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) { BlockPos n = p.relative(d); if (seen.add(n)) q.add(n); }
        }
        return null;
    }

    private static void onInput(PilotInput p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> { if (ctx.player().getVehicle() instanceof ColossusEntity c) c.setInput(p.fwd(), p.turn(), p.up(), p.down(), p.sprint(), p.jump()); });
    }

    private static void onPilotAction(PilotAction p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player pl = ctx.player();
            if (pl.getVehicle() instanceof ColossusEntity c) {
                switch (p.action()) {
                    case 0 -> { c.allowDismount = true; pl.stopRiding(); c.allowDismount = false; }
                    case 1 -> c.disassemble(pl);
                    case 2 -> { c.toggleHover(); pl.displayClientMessage(Component.literal("Hover assist: " + (c.hover() ? "ON" : "OFF")), true); }
                    case 3 -> { if (pl instanceof ServerPlayer sp) { LOCO_SCREEN.put(sp.getUUID(), false); sendEntityScan(sp, c); } }
                    case 4 -> { if (pl instanceof ServerPlayer sp) { LOCO_SCREEN.put(sp.getUUID(), true); sendEntityScan(sp, c); } }
                }
                return;
            }
            if (p.action() != 0) return;
            ColossusEntity best = null; double bd = 36;
            for (ColossusEntity c : ColossusCollision.all(false)) {
                if (c.level() != pl.level() || c.model == null || c.model.seat == null) continue;
                double d = c.seatWorld().distanceToSqr(pl.position());
                if (d < bd) { bd = d; best = c; }
            }
            if (best != null) pl.startRiding(best, true);
            else pl.displayClientMessage(Component.literal("Stand next to a Pilot Throne on a Colossus."), true);
        });
    }

    public static void sendEntityScan(ServerPlayer sp, ColossusEntity c) {
        CompoundTag t = StructureScan.toTag(StructureScan.fromEntity(c));
        t.putBoolean("Loco", LOCO_SCREEN.getOrDefault(sp.getUUID(), false));
        PacketDistributor.sendToPlayer(sp, new HullData(BlockPos.ZERO, t));
    }

    private static ColossusEntity colossusNear(ServerPlayer sp, int id) {
        Entity e = sp.level().getEntity(id);
        if (!(e instanceof ColossusEntity c) || c.model == null) return null;
        return sp.distanceToSqr(c) < Math.pow(c.model.radius + 10, 2) ? c : null;
    }

    private static void onClickColossus(ClickColossus p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ColossusEntity c = colossusNear(sp, p.entity()); if (c == null) return;
            if (p.what() == 0) { LOCO_SCREEN.put(sp.getUUID(), false); sendEntityScan(sp, c); }
            else if (p.what() == 2) { LOCO_SCREEN.put(sp.getUUID(), true); sendEntityScan(sp, c); }
            else if (p.what() == 1 && !sp.isPassenger()) sp.startRiding(c, true);
        });
    }

    private static void closeScreen(ServerPlayer sp, BlockPos pos) {
        CompoundTag closed = new CompoundTag(); closed.putBoolean("Closed", true);
        PacketDistributor.sendToPlayer(sp, new HullData(pos, closed));
    }

    private static void onHullAction(HullAction a, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (a.entity() >= 0) { onBuiltAction(sp, a); return; }
            if (sp.distanceToSqr(Vec3.atCenterOf(a.pos())) > 100 || !(sp.level().getBlockEntity(a.pos()) instanceof HullCoreBlockEntity hc)) return;
            StructureScan.Result r = StructureScan.scan(sp.level(), a.pos());
            switch (a.action()) {
                case 11, 12, 13 -> { hc.joints = applyLoco(hc.joints, a); hc.setChanged(); }
                case 1, 2, 6, 7, 8, 10, 15 -> {
                    if (a.joint() >= 0 && a.joint() < r.model.joints.size()) {
                        String key = StructureModel.jointKey(r.model.joints.get(a.joint()).key);
                        hc.joints.put(key, applyJoint(hc.joints.getCompound(key), a)); hc.setChanged();
                    }
                }
                case 3 -> { hc.hover = !hc.hover; hc.setChanged(); }
                case 9 -> { hc.joints.put(StructureModel.GAIT, gait(hc.joints.getCompound(StructureModel.GAIT), a.value())); hc.setChanged(); }
                case 16 -> { hc.joints.put(StructureModel.GAIT, gaitShape(hc.joints.getCompound(StructureModel.GAIT), a.joint(), a.value())); hc.setChanged(); }
                case 4 -> { if (ColossusEntity.assemble(sp.level(), a.pos(), sp)) { closeScreen(sp, a.pos()); return; } }
                default -> {}
            }
            sendScan(sp, a.pos(), StructureScan.scan(sp.level(), a.pos()));
        });
    }

    private static CompoundTag applyLoco(CompoundTag cfg, HullAction a) {
        cfg = cfg.copy(); CompoundTag l = cfg.getCompound(StructureModel.LOCO).copy();
        switch (a.action()) {
            case 11 -> l.putInt("IK", Math.floorMod(a.value(), StructureModel.IK_MODES.length));
            case 12 -> l.putInt("Gait", Math.floorMod(a.value(), StructureModel.GAIT_MODES.length));
            case 13 -> l.putInt("K" + a.joint(), a.value());
            default -> {}
        }
        cfg.put(StructureModel.LOCO, l); return cfg;
    }

    private static CompoundTag gait(CompoundTag g, int value) { g = g.copy(); g.putInt("Speed", Math.max(5, Math.min(400, value))); return g; }
    /** Gait shape: which = 0 stride %, 1 lift %, 2 stance % (0 = default). */
    private static CompoundTag gaitShape(CompoundTag g, int which, int value) {
        g = g.copy();
        switch (which) {
            case 0 -> g.putInt("Stride", Math.max(20, Math.min(100, value)));
            case 1 -> g.putInt("Lift", Math.max(5, Math.min(50, value)));
            case 2 -> g.putInt("Duty", value < 50 ? 0 : Math.min(90, value));
            default -> {}
        }
        return g;
    }

    private static CompoundTag applyJoint(CompoundTag c, HullAction a) {
        c = c.copy();
        switch (a.action()) {
            case 1 -> c.putByte("Mode", (byte) Math.max(0, Math.min(StructureModel.MODES.length - 1, a.value())));
            case 2 -> c.putInt("Amp", Math.max(5, Math.min(120, a.value())));
            case 6 -> {
                int idx = a.value() >> 16, ang = Math.max(-180, Math.min(180, (a.value() & 0xFFFF) - 180));
                int[] k = Arrays.copyOf(c.getIntArray("Keys"), StructureModel.KEYS);
                if (idx >= 0 && idx < k.length) k[idx] = ang;
                c.putIntArray("Keys", k);
            }
            case 7 -> c.putInt("Phase", Math.floorMod(a.value(), 360));
            case 8 -> c.putInt("SpeedX", Math.max(25, Math.min(400, a.value())));
            case 10 -> c.putBoolean("Loop", !c.getBoolean("Loop"));
            case 15 -> c.putInt("Group", Math.floorMod(a.value(), 4));
            default -> {}
        }
        return c;
    }

    private static void onBuiltAction(ServerPlayer sp, HullAction a) {
        ColossusEntity c = colossusNear(sp, a.entity()); if (c == null) return;
        switch (a.action()) {
            case 11, 12, 13 -> c.setJointCfg(applyLoco(c.jointCfg, a));
            case 1, 2, 6, 7, 8, 10, 15 -> {
                if (a.joint() >= 0 && a.joint() < c.model.joints.size()) {
                    String key = StructureModel.jointKey(c.model.joints.get(a.joint()).key);
                    CompoundTag cfg = c.jointCfg.copy(); cfg.put(key, applyJoint(cfg.getCompound(key), a)); c.setJointCfg(cfg);
                }
            }
            case 3 -> c.toggleHover();
            case 9 -> { CompoundTag cfg = c.jointCfg.copy(); cfg.put(StructureModel.GAIT, gait(cfg.getCompound(StructureModel.GAIT), a.value())); c.setJointCfg(cfg); }
            case 16 -> { CompoundTag cfg = c.jointCfg.copy(); cfg.put(StructureModel.GAIT, gaitShape(cfg.getCompound(StructureModel.GAIT), a.joint(), a.value())); c.setJointCfg(cfg); }
            case 5 -> { if (c.disassemble(sp)) { closeScreen(sp, BlockPos.ZERO); sp.displayClientMessage(Component.literal("Parked: the Colossus is blocks again."), true); return; } }
            default -> {}
        }
        sendEntityScan(sp, c);
    }
}
