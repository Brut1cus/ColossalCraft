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

    public record PilotInput(float fwd, float turn, boolean up, boolean down) implements CustomPacketPayload {
        public static final Type<PilotInput> TYPE = new Type<>(id("pilot_input"));
        public static final StreamCodec<ByteBuf, PilotInput> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, PilotInput::fwd, ByteBufCodecs.FLOAT, PilotInput::turn,
                ByteBufCodecs.BOOL, PilotInput::up, ByteBufCodecs.BOOL, PilotInput::down, PilotInput::new);
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
     *  6 set keyframe (value = index << 16 | angle + 180), 7 set phase, 8 set speed (x100), 9 set gait speed (x100), 10 toggle loop */
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

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("1");
        r.playToServer(PilotInput.TYPE, PilotInput.CODEC, Net::onInput);
        r.playToServer(PilotAction.TYPE, PilotAction.CODEC, Net::onPilotAction);
        r.playToServer(HullAction.TYPE, HullAction.CODEC, Net::onHullAction);
        r.playToServer(ClickColossus.TYPE, ClickColossus.CODEC, Net::onClickColossus);
        r.playToClient(ColossusConfig.TYPE, ColossusConfig.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.colossusConfig(p.entity(), p.cfg())));
        r.playToClient(HullData.TYPE, HullData.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.hullData(p.pos(), p.data())));
        r.playToClient(StructureRemoved.TYPE, StructureRemoved.CODEC, (p, ctx) -> ctx.enqueueWork(() -> com.colossalcraft.client.ClientHooks.structureRemoved(p.entity(), p.data())));
    }

    public static void sendScan(ServerPlayer sp, BlockPos pos, StructureScan.Result r) {
        PacketDistributor.sendToPlayer(sp, new HullData(pos, StructureScan.toTag(r)));
    }

    private static void onInput(PilotInput p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> { if (ctx.player().getVehicle() instanceof ColossusEntity c) c.setInput(p.fwd(), p.turn(), p.up(), p.down()); });
    }

    private static void onPilotAction(PilotAction p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player pl = ctx.player();
            if (pl.getVehicle() instanceof ColossusEntity c) {
                switch (p.action()) {
                    case 0 -> pl.stopRiding();
                    case 1 -> c.disassemble(pl);
                    case 2 -> { c.toggleHover(); pl.displayClientMessage(Component.literal("Hover assist: " + (c.hover() ? "ON" : "OFF")), true); }
                    case 3 -> { if (pl instanceof ServerPlayer sp) sendEntityScan(sp, c); }
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
        PacketDistributor.sendToPlayer(sp, new HullData(BlockPos.ZERO, StructureScan.toTag(StructureScan.fromEntity(c))));
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
            if (p.what() == 0) sendEntityScan(sp, c);
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
                case 1, 2, 6, 7, 8, 10 -> {
                    if (a.joint() >= 0 && a.joint() < r.model.joints.size()) {
                        String key = StructureModel.jointKey(r.model.joints.get(a.joint()).key);
                        hc.joints.put(key, applyJoint(hc.joints.getCompound(key), a)); hc.setChanged();
                    }
                }
                case 3 -> { hc.hover = !hc.hover; hc.setChanged(); }
                case 9 -> { hc.joints.put(StructureModel.GAIT, gait(hc.joints.getCompound(StructureModel.GAIT), a.value())); hc.setChanged(); }
                case 4 -> { if (ColossusEntity.assemble(sp.level(), a.pos(), sp)) { closeScreen(sp, a.pos()); return; } }
                default -> {}
            }
            sendScan(sp, a.pos(), StructureScan.scan(sp.level(), a.pos()));
        });
    }

    private static CompoundTag gait(CompoundTag g, int value) { g = g.copy(); g.putInt("Speed", Math.max(5, Math.min(400, value))); return g; }

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
            default -> {}
        }
        return c;
    }

    private static void onBuiltAction(ServerPlayer sp, HullAction a) {
        ColossusEntity c = colossusNear(sp, a.entity()); if (c == null) return;
        switch (a.action()) {
            case 1, 2, 6, 7, 8, 10 -> {
                if (a.joint() >= 0 && a.joint() < c.model.joints.size()) {
                    String key = StructureModel.jointKey(c.model.joints.get(a.joint()).key);
                    CompoundTag cfg = c.jointCfg.copy(); cfg.put(key, applyJoint(cfg.getCompound(key), a)); c.setJointCfg(cfg);
                }
            }
            case 3 -> c.toggleHover();
            case 9 -> { CompoundTag cfg = c.jointCfg.copy(); cfg.put(StructureModel.GAIT, gait(cfg.getCompound(StructureModel.GAIT), a.value())); c.setJointCfg(cfg); }
            case 5 -> { if (c.disassemble(sp)) { closeScreen(sp, BlockPos.ZERO); sp.displayClientMessage(Component.literal("Parked: the Colossus is blocks again."), true); return; } }
            default -> {}
        }
        sendEntityScan(sp, c);
    }
}
