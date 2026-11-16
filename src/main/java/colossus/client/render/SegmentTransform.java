package colossus.client.render;

import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

/**
 * Model transform for drawing a Segment mesh with the vanilla chunk shaders. Pure Java (unit-tested): no Minecraft
 * imports, so it lives beside the renderer but runs in {@code ./gradlew test}.
 * <p>
 * Mesh vertices are in the body frame (relative to the Segment's centre of mass). The wanted camera-relative position
 * is {@code t + R·v}, with {@code t = pose.position − camera}. The chunk shaders compute
 * {@code ModelViewMat · (Position + ChunkOffset)} and take fog distance from {@code Position + ChunkOffset}, so we set
 * {@code ChunkOffset = t} and fold {@code T(t)·R·T(−t)} into the model-view matrix:
 * {@code T(t)·R·T(−t)·(v + t) = t + R·v}. Fog then sees {@code |v + t|}, within the Segment's radius of the true
 * distance, instead of the distance from the centre of mass.
 */
public record SegmentTransform(Vec3 chunkOffset, double[] model) {

    /** @param pose body pose (centre of mass, world space); @param camera camera position (world space). */
    public static SegmentTransform of(Pose pose, Vec3 camera) {
        Vec3 t = pose.position().sub(camera);
        double[] r = rotation(pose.rotation());
        // M = T(t) · R · T(-t); with R as 3x3, M's translation column is t - R·t
        double rtx = r[0] * t.x() + r[3] * t.y() + r[6] * t.z();
        double rty = r[1] * t.x() + r[4] * t.y() + r[7] * t.z();
        double rtz = r[2] * t.x() + r[5] * t.y() + r[8] * t.z();
        double[] m = {
                r[0], r[1], r[2], 0,
                r[3], r[4], r[5], 0,
                r[6], r[7], r[8], 0,
                t.x() - rtx, t.y() - rty, t.z() - rtz, 1
        };
        return new SegmentTransform(t, m);
    }

    /** 3x3 rotation matrix of a unit quaternion, column-major. */
    static double[] rotation(Quat q) {
        double w = q.w(), x = q.x(), y = q.y(), z = q.z();
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y),
                2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x),
                2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y)
        };
    }

    /** What the shader computes for a body-frame vertex: {@code model · (v + chunkOffset)}, camera-relative. */
    public Vec3 apply(Vec3 v) {
        double px = v.x() + chunkOffset.x(), py = v.y() + chunkOffset.y(), pz = v.z() + chunkOffset.z();
        return new Vec3(
                model[0] * px + model[4] * py + model[8] * pz + model[12],
                model[1] * px + model[5] * py + model[9] * pz + model[13],
                model[2] * px + model[6] * py + model[10] * pz + model[14]);
    }

    /** Column-major floats, for {@code Matrix4f.set(float[])}. */
    public float[] modelFloats() {
        float[] f = new float[16];
        for (int i = 0; i < 16; i++) f[i] = (float) model[i];
        return f;
    }
}
