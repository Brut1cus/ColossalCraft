package colossus.net;

import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends Segment block contents to clients: to everyone in the dimension after assembly, and to one player for every
 * Colossus in their dimension when they log in, change dimension or respawn (the client drops its copy then).
 */
public final class StructureSync {
    private StructureSync() {}

    /** Payloads for one Colossus, reading block states from its (force-loaded) ship-space plots. */
    public static List<SegmentBlocks> payloads(ServerLevel level, ColossusRecord r, int maxCellsPerPart) {
        List<SegmentBlocks> out = new ArrayList<>();
        int count = r.segments.size();
        for (int si = 0; si < count; si++) {
            ColossusRecord.SegmentRecord s = r.segments.get(si);
            int n = s.cells.length;
            int parts = Math.max(1, (n + maxCellsPerPart - 1) / maxCellsPerPart);
            for (int part = 0; part < parts; part++) {
                int from = part * maxCellsPerPart, to = Math.min(n, from + maxCellsPerPart);
                long[] cells = new long[to - from];
                int[] states = new int[to - from];
                for (int i = from; i < to; i++) {
                    cells[i - from] = s.cells[i];
                    states[i - from] = Block.getId(level.getBlockState(s.plotPos(s.cells[i])));
                }
                out.add(new SegmentBlocks(r.id, si, count, s.comRel, part, parts, cells, states));
            }
        }
        return out;
    }

    public static void sendToLevel(ServerLevel level, ColossusRecord r) {
        for (SegmentBlocks p : payloads(level, r, SegmentBlocks.MAX_CELLS_PER_PART)) colossus.net.Send.toLevel(level, p);
    }

    public static void sendAll(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (ColossusRecord r : ColossusSavedData.get(level).all()) {
            for (SegmentBlocks p : payloads(level, r, SegmentBlocks.MAX_CELLS_PER_PART)) colossus.net.Send.toPlayer(player, p);
        }
    }

    public static void removed(ServerLevel level, UUID id) {
        colossus.net.Send.toLevel(level, new ColossusRemoved(id));
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) sendAll(sp);
    }

    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) sendAll(sp);
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) sendAll(sp);
    }
}
