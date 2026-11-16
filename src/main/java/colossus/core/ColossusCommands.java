package colossus.core;

import colossus.assembly.AssemblyService;
import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * Operator commands (permission level 2):
 * {@code /colossus scan <pos>}, {@code assemble <pos>}, {@code disassemble <id>}, {@code disassemble nearest}, {@code gyros},
 * {@code list}, {@code physics}.
 */
public final class ColossusCommands {

    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("colossus").requires(s -> s.hasPermission(2))
                .then(Commands.literal("scan").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                    var r = AssemblyService.scan(c.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(c, "pos"));
                    reply(c, String.format(Locale.ROOT, "blocks=%d segments=%d joints=%d mass=%.1ft lattice=%d errors=%s warnings=%s",
                            r.blockCount(), r.segments().size(), r.joints().size(), r.totalMass() / 1000, r.latticeBlocks(), r.errors(), r.warnings()));
                    return r.ok() ? 1 : 0;
                })))
                .then(Commands.literal("assemble").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                    UUID id = AssemblyService.assemble(c.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(c, "pos"), c.getSource().getPlayer());
                    reply(c, id == null ? "assembly failed" : "assembled " + id);
                    return id == null ? 0 : 1;
                })))
                .then(Commands.literal("disassemble")
                        .then(Commands.literal("nearest").executes(c -> {
                            ColossusRecord r = nearest(c.getSource());
                            if (r == null) { reply(c, "no Colossus in this dimension"); return 0; }
                            return AssemblyService.disassemble(c.getSource().getLevel(), r.id, c.getSource().getPlayer()) ? 1 : 0;
                        }))
                        .then(Commands.argument("id", UuidArgument.uuid()).executes(c ->
                                AssemblyService.disassemble(c.getSource().getLevel(), UuidArgument.getUuid(c, "id"), c.getSource().getPlayer()) ? 1 : 0)))
                .then(Commands.literal("list").executes(c -> {
                    ServerLevel level = c.getSource().getLevel();
                    ColossusManager m = ColossusManager.get(level);
                    for (ColossusRecord r : ColossusSavedData.get(level).all()) {
                        var p = m.pose(r, Math.max(0, r.segments.indexOf(r.root()))).position();
                        long broken = r.joints.stream().filter(j -> j.broken).count();
                        reply(c, String.format(Locale.ROOT, "%s %s blocks=%d segments=%d joints=%d (broken %d) at %.1f %.1f %.1f",
                                r.id, r.coreTier, r.blockCount(), r.segments.size(), r.joints.size(), broken, p.x(), p.y(), p.z()));
                    }
                    return 1;
                }))
                .then(Commands.literal("gyros").executes(c -> {
                    ColossusRecord r = nearest(c.getSource());
                    if (r == null) { reply(c, "No Colossus in this dimension."); return 0; }
                    var info = ColossusManager.get(c.getSource().getLevel()).gyros(r.id);
                    if (info.isEmpty()) { reply(c, "Nearest Colossus has no Gyro Stabilizers."); return 0; }
                    for (ColossusManager.GyroInfo g : info) {
                        StringBuilder rotors = new StringBuilder();
                        for (var rot : g.rotors()) rotors.append(String.format(Locale.ROOT, " %dx%d@%.0frpm(%.0f%%)", rot.footprint, rot.footprint, rot.rpm(), rot.throttle() * 100));
                        reply(c, String.format(Locale.ROOT, "segment %d: capacity %.1f kN·m, momentum budget %.0f%% used, rotors%s",
                                g.segment(), g.torqueCapacity() / 1000, g.saturation() * 100, rotors));
                    }
                    return 1;
                }))
                .then(Commands.literal("physics").executes(c -> {
                    ColossusManager m = ColossusManager.get(c.getSource().getLevel());
                    reply(c, String.format(Locale.ROOT, "backend=%s lastStep=%.2fms", m.backendId(), m.lastStepMillis()));
                    return 1;
                })));
    }

    private static ColossusRecord nearest(CommandSourceStack src) {
        Vec3 at = src.getPosition();
        ColossusRecord best = null;
        double bestD = Double.MAX_VALUE;
        ColossusManager m = ColossusManager.get(src.getLevel());
        for (ColossusRecord r : ColossusSavedData.get(src.getLevel()).all()) {
            var p = m.pose(r, Math.max(0, r.segments.indexOf(r.root()))).position();
            double d = at.distanceToSqr(p.x(), p.y(), p.z());
            if (d < bestD) { bestD = d; best = r; }
        }
        return best;
    }

    private static void reply(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private ColossusCommands() {}
}
