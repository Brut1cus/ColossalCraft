package colossus.physics.api;

import colossus.physics.math.Pose;
import colossus.physics.math.Vec3;

/** Read-only state of a body after the last step. */
public record BodyState(Pose pose, Vec3 linearVelocity, Vec3 angularVelocity, boolean sleeping) {
    public static final BodyState MISSING = new BodyState(Pose.IDENTITY, Vec3.ZERO, Vec3.ZERO, true);
}
