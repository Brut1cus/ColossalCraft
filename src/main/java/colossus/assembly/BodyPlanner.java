package colossus.assembly;

import colossus.kinetics.ComponentScaling;
import colossus.physics.api.*;
import colossus.physics.math.Mat3;
import colossus.physics.math.Pose;
import colossus.physics.math.Vec3;
import colossus.physics.shape.GreedyMesher;

import java.util.List;

/**
 * Turns a scanned/saved Colossus into physics descriptors. Pure Java, shared by the game layer and the tests.
 * <p>
 * Frames: cells and joint anchors are in the core frame (relative to the Hull Core block); each body's local frame is
 * the core frame shifted to that Segment's centre of mass.
 */
public final class BodyPlanner {
    private BodyPlanner() {}

    public record SegmentInput(Vec3 comRel, double mass, Mat3 inertia, List<int[]> cellsRel, Pose pose) {}

    public record JointInput(JointKind kind, int parent, int child, Vec3 anchorRel, int axis, int footprint) {
        public Vec3 axisVector() { return axis == 0 ? Vec3.X : axis == 1 ? Vec3.Y : Vec3.Z; }
    }

    public static BodyDesc body(SegmentInput s, int group) {
        List<Box> shape = GreedyMesher.mesh(s.cellsRel(), s.comRel());
        return BodyDesc.dynamic(new MassProperties(s.mass(), Vec3.ZERO, s.inertia()), s.pose(), shape).withGroup(group);
    }

    public static ConstraintDesc constraint(JointInput j, List<SegmentInput> segs, List<BodyHandle> handles, double gravity) {
        SegmentInput a = segs.get(j.parent()), b = segs.get(j.child());
        Vec3 ax = j.axisVector();
        // a universal joint is a cross: the child's hinge axis is perpendicular to the parent's, or the solver snaps it 90°
        Vec3 axB = j.kind().type == JointType.UNIVERSAL ? (j.axis() == 0 ? Vec3.Y : j.axis() == 1 ? Vec3.Z : Vec3.X) : ax;
        ConstraintDesc d = ConstraintDesc.of(j.kind().type, handles.get(j.parent()), handles.get(j.child()),
                j.anchorRel().sub(a.comRel()), j.anchorRel().sub(b.comRel()), ax, axB);
        return switch (j.kind().type) {
            case HINGE, SLIDER -> d.withLimits(j.kind().lower, j.kind().upper);
            case BALL -> d.withCone(j.kind().upper, Math.toRadians(45));
            case SPRING_DAMPER -> {
                double k = Math.max(1000, b.mass() * gravity / 0.15);
                yield d.withLimits(j.kind().lower, j.kind().upper).withSpring(k, 2 * Math.sqrt(k * Math.max(1, b.mass())), 0);
            }
            default -> d;
        };
    }

    /** Servo limit for a motorised joint: its size-scaled rated torque (or load, for sliders). 0 if not motorisable. */
    public static double holdLimit(JointInput j, ComponentScaling scaling) {
        if (!j.kind().type.motorisable()) return 0;
        return j.kind().type == JointType.SLIDER ? scaling.loadCapacity(j.kind().component, j.footprint())
                : scaling.torqueCapacity(j.kind().component, j.footprint());
    }

    /** Servo-hold a joint in the pose it was assembled in, at its rated torque (no-op for joints without a servo). */
    public static void hold(PhysicsBackend backend, ConstraintHandle h, JointInput j, ComponentScaling scaling) {
        double max = holdLimit(j, scaling);
        if (h == null || max <= 0) return;
        if (j.kind().type.orientationMotor()) backend.setMotorOrientation(h, colossus.physics.math.Quat.IDENTITY, max);
        else backend.setMotorTarget(h, 0, max);
    }

    /** Load ratio of a joint (1 = at its rating) from the last physics step. */
    public static double strain(JointInput j, ConstraintState cs, ComponentScaling scaling) {
        double torqueCap = scaling.torqueCapacity(j.kind().component, j.footprint());
        double loadCap = scaling.loadCapacity(j.kind().component, j.footprint());
        return Math.max(torqueCap > 0 ? cs.torque() / torqueCap : 0, loadCap > 0 ? cs.force() / loadCap : 0);
    }
}
