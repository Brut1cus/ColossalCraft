package colossus.physics.jolt;

/**
 * Primary backend placeholder: Jolt Physics through jolt-jni, bundled with NeoForge jar-in-jar.
 * <p>
 * Status (Phase 1): not wired yet. jolt-jni is detected by reflection only, so the mod compiles and runs without it,
 * and {@link colossus.physics.BackendFactory} falls back to the pure-Java XPBD backend. Implementing this class means
 * mapping {@link colossus.physics.api.PhysicsBackend} onto Jolt's PhysicsSystem:
 * <ul>
 *   <li>BodyDesc → MutableCompoundShape of BoxShapes + MassProperties override</li>
 *   <li>HINGE/SWIVEL → HingeConstraint (motor in position mode), BALL → SwingTwistConstraint,
 *       SLIDER → SliderConstraint, SPRING_DAMPER → SliderConstraint with spring settings, FIXED/DOCK → FixedConstraint,
 *       UNIVERSAL → SixDOFConstraint with two free rotation axes</li>
 *   <li>TerrainQuery → per-chunk-section StaticCompoundShape bodies streamed near moving bodies</li>
 * </ul>
 * See docs/ROADMAP.md.
 */
public final class JoltBackend {
    private JoltBackend() {}

    /** Class from jolt-jni used as the presence probe. */
    public static final String PROBE_CLASS = "com.github.stephengold.joltjni.PhysicsSystem";

    public static boolean isAvailable() {
        try {
            Class.forName(PROBE_CLASS, false, JoltBackend.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
