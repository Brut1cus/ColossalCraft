package colossus.core.data;

import colossus.brain.gait.GaitPattern;
import colossus.core.Colossus;
import colossus.kinetics.ComponentScaling;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Data-pack loaders:
 * <ul>
 *   <li>{@code data/<ns>/colossus/block_physics/*.json}: mass, friction, restitution by block id, tag or sound type</li>
 *   <li>{@code data/<ns>/colossus/component_scaling/*.json}: base torque/load and size exponents per component</li>
 *   <li>{@code data/<ns>/colossus/gaits/*.json}: phase offsets, duty factor, speed band</li>
 * </ul>
 * Files are applied in namespace/path order, so a pack can override the defaults shipped in {@code data/colossus}.
 */
public final class DataLoaders {
    private static final Gson GSON = new Gson();

    public static void onAddReloadListeners(AddReloadListenerEvent e) {
        e.addListener(new BlockPhysics());
        e.addListener(new Scaling());
        e.addListener(new Gaits());
    }

    static final class BlockPhysics extends SimpleJsonResourceReloadListener {
        BlockPhysics() { super(GSON, "colossus/block_physics"); }

        @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller pf) {
            BlockPhysicsTable t = ColossusData.BLOCK_PHYSICS;
            t.clear();
            for (JsonElement el : new TreeMap<>(files).values()) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("default")) t.setDefault(props(o.getAsJsonObject("default"), t.defaults()));
                    if (o.has("blocks")) for (var en : o.getAsJsonObject("blocks").entrySet()) t.putId(en.getKey(), props(en.getValue().getAsJsonObject(), t.defaults()));
                    if (o.has("tags")) for (var en : o.getAsJsonObject("tags").entrySet()) t.putTag(stripHash(en.getKey()), props(en.getValue().getAsJsonObject(), t.defaults()));
                    if (o.has("sound_types")) for (var en : o.getAsJsonObject("sound_types").entrySet()) t.putSound(en.getKey().toLowerCase(Locale.ROOT), props(en.getValue().getAsJsonObject(), t.defaults()));
                } catch (RuntimeException ex) {
                    Colossus.LOG.error("Bad block_physics file", ex);
                }
            }
        }
    }

    static final class Scaling extends SimpleJsonResourceReloadListener {
        Scaling() { super(GSON, "colossus/component_scaling"); }

        @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller pf) {
            ComponentScaling s = ColossusData.SCALING;
            s.resetToDefaults();
            for (JsonElement el : new TreeMap<>(files).values()) {
                try {
                    for (var en : el.getAsJsonObject().entrySet()) {
                        ComponentScaling.Component c = ComponentScaling.Component.valueOf(en.getKey().toUpperCase(Locale.ROOT));
                        JsonObject o = en.getValue().getAsJsonObject();
                        ComponentScaling.Coefficients old = s.get(c);
                        s.set(c, new ComponentScaling.Coefficients(
                                num(o, "base_torque", old.baseTorque()), num(o, "base_load", old.baseLoad()),
                                num(o, "torque_exponent", old.torqueExponent()), num(o, "load_exponent", old.loadExponent())));
                    }
                } catch (RuntimeException ex) {
                    Colossus.LOG.error("Bad component_scaling file", ex);
                }
            }
        }
    }

    static final class Gaits extends SimpleJsonResourceReloadListener {
        Gaits() { super(GSON, "colossus/gaits"); }

        @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller pf) {
            ColossusData.GAITS.clear();
            for (var en : new TreeMap<>(files).entrySet()) {
                try {
                    JsonObject o = en.getValue().getAsJsonObject();
                    JsonArray arr = o.getAsJsonArray("phase_offsets");
                    double[] offsets = new double[arr.size()];
                    for (int i = 0; i < offsets.length; i++) offsets[i] = arr.get(i).getAsDouble();
                    String name = en.getKey().toString();
                    ColossusData.GAITS.put(name, new GaitPattern(name, offsets, num(o, "duty_factor", 0.75),
                            o.has("dynamic") && o.get("dynamic").getAsBoolean(), num(o, "min_speed", 0), num(o, "max_speed", 99)));
                } catch (RuntimeException ex) {
                    Colossus.LOG.error("Bad gait file {}", en.getKey(), ex);
                }
            }
        }
    }

    private static BlockPhysicsTable.Props props(JsonObject o, BlockPhysicsTable.Props fallback) {
        return new BlockPhysicsTable.Props(num(o, "mass", fallback.mass()), num(o, "friction", fallback.friction()), num(o, "restitution", fallback.restitution()));
    }

    private static double num(JsonObject o, String k, double d) { return o.has(k) ? o.get(k).getAsDouble() : d; }

    private static String stripHash(String s) { return s.startsWith("#") ? s.substring(1) : s; }

    private DataLoaders() {}
}
