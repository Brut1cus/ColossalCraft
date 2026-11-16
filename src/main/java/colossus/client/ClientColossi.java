package colossus.client;

import colossus.net.PoseSync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side pose buffer: the last two pose packets per Colossus, for interpolation at partial tick.
 * Contains no client-only classes, so it is safe to reference from common payload registration.
 * The Segment renderer (vanilla / Sodium / Iris paths) reads this in Phase 2.
 */
public final class ClientColossi {
    public record Poses(PoseSync previous, PoseSync latest, long receivedAtNanos) {}

    private static final Map<UUID, Poses> POSES = new ConcurrentHashMap<>();

    public static void accept(PoseSync p) {
        POSES.compute(p.colossus(), (id, old) -> new Poses(old == null ? p : old.latest(), p, System.nanoTime()));
    }

    public static Poses get(UUID id) { return POSES.get(id); }

    public static Map<UUID, Poses> all() { return POSES; }

    public static void clear() { POSES.clear(); }

    private ClientColossi() {}
}
