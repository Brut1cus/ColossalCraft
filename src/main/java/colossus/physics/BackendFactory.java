package colossus.physics;

import colossus.physics.api.PhysicsBackend;
import colossus.physics.jolt.JoltBackend;
import colossus.physics.xpbd.XpbdBackend;

import java.util.function.Consumer;

/** Picks the physics backend from config: "auto" (Jolt if present, else XPBD), "jolt" or "xpbd". */
public final class BackendFactory {
    private BackendFactory() {}

    public static PhysicsBackend create(String preference, int xpbdSubsteps, Consumer<String> log) {
        String p = preference == null ? "auto" : preference.toLowerCase(java.util.Locale.ROOT);
        if (!p.equals("xpbd") && JoltBackend.isAvailable()) {
            log.accept("jolt-jni found, but the Jolt backend is not implemented yet (Phase 2); using XPBD");
        } else if (p.equals("jolt")) {
            log.accept("Jolt backend requested but jolt-jni is not bundled; using XPBD");
        }
        return new XpbdBackend(xpbdSubsteps);
    }
}
