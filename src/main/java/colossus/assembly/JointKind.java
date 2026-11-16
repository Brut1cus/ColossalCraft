package colossus.assembly;

import colossus.kinetics.ComponentScaling;
import colossus.physics.api.JointType;

/**
 * The joint blocks from the blueprint's "Joint blocks" table and the constraint each one makes. Default limits apply
 * until the player sets their own in the Rigging GUI (Phase 2).
 */
public enum JointKind {
    THRUST_BEARING("thrust_bearing", JointType.HINGE, ComponentScaling.Component.BEARING, Math.toRadians(-120), Math.toRadians(120)),
    BALL_BEARING("ball_bearing", JointType.BALL, ComponentScaling.Component.JOINT, 0, Math.toRadians(60)),
    UNIVERSAL_JOINT("universal_joint", JointType.UNIVERSAL, ComponentScaling.Component.JOINT, 0, 0),
    SWIVEL_BEARING("swivel_bearing", JointType.SWIVEL, ComponentScaling.Component.BEARING, 1, -1),
    SLIDER_JOINT("slider_joint", JointType.SLIDER, ComponentScaling.Component.PISTON, -1.0, 1.0),
    SPRING_DAMPER("spring_damper", JointType.SPRING_DAMPER, ComponentScaling.Component.PISTON, -0.75, 0.25),
    FIXED_WELD("fixed_weld", JointType.FIXED, ComponentScaling.Component.JOINT, 0, 0);

    public final String id;
    public final JointType type;
    public final ComponentScaling.Component component;
    /** Hinge/slider: lower and upper limit. Ball: lower unused, upper = cone half-angle. */
    public final double lower, upper;

    JointKind(String id, JointType type, ComponentScaling.Component component, double lower, double upper) {
        this.id = id; this.type = type; this.component = component; this.lower = lower; this.upper = upper;
    }

    public static JointKind byId(String id) {
        for (JointKind k : values()) if (k.id.equals(id)) return k;
        return FIXED_WELD;
    }
}
