package colossus.brain.walker;

import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

/** Test access to package-private walker helpers. */
public final class WalkerBrainAccess {
    private WalkerBrainAccess() {}

    public static Quat swingOnly(Quat q, Vec3 axis) { return WalkerBrain.swingOnly(q, axis); }
}
