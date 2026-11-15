package com.colossalcraft;

import com.colossalcraft.colossus.Density;
import com.colossalcraft.network.Net;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.colossalcraft.colossus.ColossusCollision;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureScan;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;

@Mod(ColossalCraft.MODID)
public class ColossalCraft {
    public static final String MODID = "colossalcraft";
    public static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    public ColossalCraft(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        com.colossalcraft.compat.Compat.init(modBus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modBus.addListener(Net::register);
        modBus.addListener(ColossalCraft::caps);
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new Density()));
        NeoForge.EVENT_BUS.addListener(ColossalCraft::commands);
        // Crouch is 'lower' while piloting: only the leave key (V) lets the pilot off
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityMountEvent e) -> {
            if (e.isDismounting() && e.getEntityBeingMounted() instanceof ColossusEntity c && !c.allowDismount && e.getEntityMounting() instanceof net.minecraft.world.entity.player.Player p
                    && p.isAlive() && !c.isRemoved() && p.isShiftKeyDown()) e.setCanceled(true);
        });
    }

    private static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("colossalcraft").requires(s -> s.hasPermission(2))
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
                for (ColossusEntity ce : ColossusCollision.all(false)) c.getSource().sendSuccess(() -> Component.literal(String.format(
                        "Colossus %d at %.2f %.2f %.2f yaw=%.2f vel=%s blocks=%d energy=%d ground=%s", ce.getId(), ce.getX(), ce.getY(), ce.getZ(), ce.yaw, ce.vel, ce.blocks.size(), ce.energy, ce.onGroundC)), false);
                return 1; })));
    }

    private static void caps(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModRegistry.POWER_BE.get(), (be, side) -> be.tier() == null ? null : be.fuelPipe);
        e.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModRegistry.FLUX_BE.get(), (be, side) -> be.fe);
    }
}
