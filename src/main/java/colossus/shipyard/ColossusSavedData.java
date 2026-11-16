package colossus.shipyard;

import colossus.core.Colossus;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Per-dimension store of assembled Colossi and the plot allocator ({@code data/colossus.dat}). */
public final class ColossusSavedData extends SavedData {
    /** Geometry-only allocator for "is this position in ship space?" checks (same origin and grid as every level's). */
    public static final PlotAllocator PLOTS_REGION = new PlotAllocator();

    private final Map<UUID, ColossusRecord> colossi = new LinkedHashMap<>();
    private final PlotAllocator plots = new PlotAllocator();

    public static final SavedData.Factory<ColossusSavedData> FACTORY = new SavedData.Factory<>(ColossusSavedData::new, ColossusSavedData::load, null);

    public static ColossusSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, Colossus.MODID);
    }

    public PlotAllocator plots() { return plots; }

    public Collection<ColossusRecord> all() { return colossi.values(); }

    public ColossusRecord byId(UUID id) { return colossi.get(id); }

    public void put(ColossusRecord r) { colossi.put(r.id, r); setDirty(); }

    public void remove(UUID id) {
        ColossusRecord r = colossi.remove(id);
        if (r != null) for (ColossusRecord.SegmentRecord s : r.segments) plots.free(s.plotIndex);
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ColossusRecord r : colossi.values()) list.add(r.save());
        tag.put("Colossi", list);
        tag.putLongArray("Plots", plots.save());
        return tag;
    }

    static ColossusSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ColossusSavedData d = new ColossusSavedData();
        for (Tag t : tag.getList("Colossi", Tag.TAG_COMPOUND)) {
            try {
                ColossusRecord r = ColossusRecord.load((CompoundTag) t);
                d.colossi.put(r.id, r);
            } catch (RuntimeException e) {
                Colossus.LOG.error("Skipping unreadable Colossus record", e);
            }
        }
        d.plots.load(tag.getLongArray("Plots"));
        return d;
    }
}
