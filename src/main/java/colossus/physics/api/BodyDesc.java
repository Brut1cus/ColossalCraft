package colossus.physics.api;

import colossus.physics.math.Pose;

import java.util.List;

/**
 * Everything a backend needs to create one rigid body.
 *
 * @param mass        mass properties; {@code mass.inertia()} is about the centre of mass in the body frame
 * @param pose        initial world pose of the centre of mass
 * @param shape       collision boxes relative to the centre of mass (greedy-meshed block shapes)
 * @param friction    Coulomb friction coefficient of the body's surface
 * @param restitution bounciness 0..1
 * @param isStatic    immovable (infinite mass), used for anchors and tests
 * @param ccd         continuous collision for fast bodies (rotor tips, jets)
 * @param group       collision group: bodies with the same non-zero group and {@code filterSameGroup} skip each other
 */
public record BodyDesc(MassProperties mass, Pose pose, List<Box> shape, double friction, double restitution,
                       boolean isStatic, boolean ccd, int group) {

    public static BodyDesc dynamic(MassProperties mass, Pose pose, List<Box> shape) {
        return new BodyDesc(mass, pose, shape, 0.6, 0.1, false, false, 0);
    }

    public static BodyDesc fixed(Pose pose, List<Box> shape) {
        return new BodyDesc(MassProperties.NONE, pose, shape, 0.6, 0.1, true, false, 0);
    }

    public BodyDesc withFriction(double f) { return new BodyDesc(mass, pose, shape, f, restitution, isStatic, ccd, group); }
    public BodyDesc withGroup(int g) { return new BodyDesc(mass, pose, shape, friction, restitution, isStatic, ccd, g); }
}
