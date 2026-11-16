package colossus.core.data;

import java.util.*;

/**
 * Per-block mass, friction and restitution, resolved in the blueprint's order:
 * exact block id → block tag → sound type → default (1,000 kg). Filled from
 * {@code data/<ns>/colossus/block_physics/*.json} by the game layer; pure Java so it can be tested and read by the
 * physics thread.
 * <p>
 * JSON shape: {@code {"blocks": {"minecraft:iron_block": {"mass": 7800, "friction": 0.4, "restitution": 0.2}},
 * "tags": {"minecraft:logs": {"mass": 600}}, "sound_types": {"wool": {"mass": 50}}}}. Missing fields fall back to the
 * default entry.
 */
public final class BlockPhysicsTable {

    /** Full-block values: mass in kg, Coulomb friction, restitution. */
    public record Props(double mass, double friction, double restitution) {}

    /** What the table needs to know about a block state. */
    public interface BlockKey {
        String id();
        /** Tags in registry iteration order. */
        Collection<String> tags();
        /** Sound type name ("wool", "stone", "metal", ...), may be null. */
        String soundType();
        /** Collision-shape volume in m³ (1 for a full block, 0.5 for a slab). */
        double shapeVolume();
    }

    public static final Props DEFAULT = new Props(1000, 0.6, 0.1);

    private final Map<String, Props> byId = new HashMap<>();
    private final LinkedHashMap<String, Props> byTag = new LinkedHashMap<>();
    private final Map<String, Props> bySound = new HashMap<>();
    private Props fallback = DEFAULT;

    public void clear() { byId.clear(); byTag.clear(); bySound.clear(); fallback = DEFAULT; }

    public void putId(String id, Props p) { byId.put(id, p); }
    public void putTag(String tag, Props p) { byTag.put(tag, p); }
    public void putSound(String sound, Props p) { bySound.put(sound, p); }
    public void setDefault(Props p) { fallback = p; }
    public Props defaults() { return fallback; }

    /** Full-block properties (before shape-volume scaling). */
    public Props resolve(BlockKey k) {
        Props p = byId.get(k.id());
        if (p != null) return p;
        if (!byTag.isEmpty()) {
            Collection<String> tags = k.tags();
            for (Map.Entry<String, Props> e : byTag.entrySet()) if (tags.contains(e.getKey())) return e.getValue();
        }
        if (k.soundType() != null) {
            p = bySound.get(k.soundType());
            if (p != null) return p;
        }
        return fallback;
    }

    /** Mass in kg of this block state: full-block mass × collision volume (slabs weigh half). */
    public double mass(BlockKey k) {
        double v = k.shapeVolume();
        return resolve(k).mass() * (v <= 0 ? 0 : Math.min(1, v));
    }

    /** Parse a numeric field with fallback, for loaders handing in decoded JSON maps. */
    public static Props props(Map<String, ?> m, Props fallback) {
        return new Props(num(m, "mass", fallback.mass()), num(m, "friction", fallback.friction()), num(m, "restitution", fallback.restitution()));
    }

    private static double num(Map<String, ?> m, String k, double d) {
        Object o = m.get(k);
        return o instanceof Number n ? n.doubleValue() : d;
    }
}
