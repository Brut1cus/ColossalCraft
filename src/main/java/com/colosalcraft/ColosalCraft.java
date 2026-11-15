package com.colosalcraft;

import com.colosalcraft.colossus.Density;
import com.colosalcraft.network.Net;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.colosalcraft.colossus.ColossusCollision;
import com.colosalcraft.colossus.ColossusEntity;
import com.colosalcraft.colossus.StructureScan;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;

@Mod(ColosalCraft.MODID)
public class ColosalCraft {
    public static final String MODID = "colosalcraft";
    public static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    public ColosalCraft(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        com.colosalcraft.compat.Compat.init(modBus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modBus.addListener(Net::register);
        modBus.addListener(ColosalCraft::caps);
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new Density()));
        NeoForge.EVENT_BUS.addListener(ColosalCraft::commands);
    }

    private static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("colosalcraft").requires(s -> s.hasPermission(2))
            .then(Commands.literal("scan").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                var pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
                StructureScan.Result r = StructureScan.scan(c.getSource().getLevel(), pos);
                var m = r.model;
                c.getSource().sendSuccess(() -> Component.literal(String.format("blocks=%d mass=%s segs=%d joints=%d out=%s energy=%d thrustUp=%s gyro=%s errors=%s warnings=%s",
                        r.blocks.size(), StructureScan.tons(m.mass), m.segs.size(), m.joints.size(), StructureScan.kilo(m.output), m.energy,
                        StructureScan.kilo(m.thrustCap[1]), StructureScan.kilo(m.gyroCap), r.errors, r.warnings)), false);
                for (var j : m.joints) c.getSource().sendSuccess(() -> Component.literal(String.format(" J%d %s k=%d axis=%s parentSeg=%d children=%s sub=%s ratio=%.2f torque=%s/%s",
                        j.id, j.tier.id, j.k, j.axis, j.parentSeg, j.childSegs, StructureScan.tons(j.subMass), j.staticRatio, StructureScan.kilo(j.motorTorque), StructureScan.kilo(j.reqTorque))), false);
                return 1; })))
            .then(Commands.literal("assemble").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                boolean ok = ColossusEntity.assemble(c.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(c, "pos"), null);
                c.getSource().sendSuccess(() -> Component.literal(ok ? "assembled" : "failed"), false); return ok ? 1 : 0; })))
            .then(Commands.literal("list").executes(c -> {
                for (ColossusEntity ce : ColossusCollision.LOADED) c.getSource().sendSuccess(() -> Component.literal(String.format(
                        "Colossus %d at %.2f %.2f %.2f yaw=%.2f vel=%s blocks=%d energy=%d ground=%s", ce.getId(), ce.getX(), ce.getY(), ce.getZ(), ce.yaw, ce.vel, ce.blocks.size(), ce.energy, ce.onGroundC)), false);
                return 1; })));
    }

    private static void caps(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModRegistry.POWER_BE.get(), (be, side) -> be.tier() == null ? null : be.fuelHandler);
        e.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModRegistry.FLUX_BE.get(), (be, side) -> be.fe);
    }
}
