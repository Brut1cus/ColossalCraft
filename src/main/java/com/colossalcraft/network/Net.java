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
    /** Hull Core screen button (client -> server). 0 rescan, 1 cycle joint mode, 2 cycle joint amp, 3 hover, 4 assemble */
    public record HullAction(BlockPos pos, int action, int joint) implements CustomPacketPayload {
        public static final Type<HullAction> TYPE = new Type<>(id("hull_action"));
        public static final StreamCodec<ByteBuf, HullAction> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, HullAction::pos,
                ByteBufCodecs.VAR_INT, HullAction::action, ByteBufCodecs.VAR_INT, HullAction::joint, HullAction::new);
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
                }
                return;
            }
            if (p.action() != 0) return;
            ColossusEntity best = null; double bd = 36;
            for (ColossusEntity c : ColossusCollision.LOADED) {
                if (c.level() != pl.level() || c.model == null || c.model.seat == null) continue;
                double d = c.seatWorld().distanceToSqr(pl.position());
                if (d < bd) { bd = d; best = c; }
            }
            if (best != null) pl.startRiding(best, true);
            else pl.displayClientMessage(Component.literal("Stand next to a Pilot Throne on a Colossus."), true);
        });
    }

    private static void onHullAction(HullAction a, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (sp.distanceToSqr(Vec3.atCenterOf(a.pos())) > 100 || !(sp.level().getBlockEntity(a.pos()) instanceof HullCoreBlockEntity hc)) return;
            StructureScan.Result r = StructureScan.scan(sp.level(), a.pos());
            switch (a.action()) {
                case 1, 2 -> {
                    if (a.joint() >= 0 && a.joint() < r.model.joints.size()) {
                        StructureModel.Joint j = r.model.joints.get(a.joint());
                        String key = StructureModel.jointKey(j.key);
                        CompoundTag c = hc.joints.getCompound(key);
                        if (a.action() == 1) c.putByte("Mode", (byte) ((j.mode + 1) % StructureModel.MODES.length));
                        else { int[] amps = {10, 20, 30, 45, 60, 90}; int next = amps[0]; for (int v : amps) if (v > j.amp) { next = v; break; } c.putInt("Amp", next); }
                        hc.joints.put(key, c); hc.setChanged();
                    }
                }
                case 3 -> { hc.hover = !hc.hover; hc.setChanged(); }
                case 4 -> {
                    if (ColossusEntity.assemble(sp.level(), a.pos(), sp)) {
                        CompoundTag closed = new CompoundTag(); closed.putBoolean("Closed", true);
                        PacketDistributor.sendToPlayer(sp, new HullData(a.pos(), closed));
                        return;
                    }
                }
                default -> {}
            }
            sendScan(sp, a.pos(), StructureScan.scan(sp.level(), a.pos()));
        });
    }
}
