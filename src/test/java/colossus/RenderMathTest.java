package colossus;

import colossus.client.render.SegmentTransform;
import colossus.net.PoseInterpolation;
import colossus.physics.math.Pose;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

public class RenderMathTest {
    static void close(Vec3 a, Vec3 b, double eps) {
        assertTrue(a + " vs " + b, a.distance(b) < eps);
    }

    @Test
    public void transformPlacesBodyVerticesAtTheirWorldPosition() {
        Quat q = Quat.axisAngle(new Vec3(0.3, 1, -0.2).normalize(), 1.1);
        Pose pose = new Pose(new Vec3(20_000_123.5, 70.25, -40.75), q);
        Vec3 camera = new Vec3(20_000_100.0, 64.0, -30.0);
        SegmentTransform st = SegmentTransform.of(pose, camera);
        for (Vec3 v : new Vec3[]{Vec3.ZERO, new Vec3(3.5, -1.5, 2), new Vec3(-7, 4, 0.5)}) {
            Vec3 expected = pose.toWorld(v).sub(camera);
            close(st.apply(v), expected, 1e-6); // expected passes through world coordinates near 2e7
        }
    }

    @Test
    public void identityPoseIsAPureOffset() {
        Pose pose = new Pose(new Vec3(10, 5, -3), Quat.IDENTITY);
        SegmentTransform st = SegmentTransform.of(pose, new Vec3(1, 1, 1));
        close(st.chunkOffset(), new Vec3(9, 4, -4), 1e-12);
        double[] m = st.model();
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) assertEquals(c == r ? 1 : 0, m[c * 4 + r], 1e-12);
    }

    @Test
    public void fogDistanceStaysWithinSegmentRadius() {
        // the shader takes fog distance from |v + chunkOffset|; it must be within |v| of the true distance
        Pose pose = new Pose(new Vec3(50, 0, 0), Quat.axisAngle(Vec3.Y, Math.PI));
        SegmentTransform st = SegmentTransform.of(pose, Vec3.ZERO);
        Vec3 v = new Vec3(4, 0, 0);
        double fog = v.add(st.chunkOffset()).length(), real = st.apply(v).length();
        assertTrue(Math.abs(fog - real) <= 2 * v.length() + 1e-9);
    }

    @Test
    public void alphaClampsAndScales() {
        long iv = PoseInterpolation.INTERVAL_NANOS;
        assertEquals(0, PoseInterpolation.alpha(100, 100, iv), 0);
        assertEquals(0, PoseInterpolation.alpha(50, 100, iv), 0);
        assertEquals(0.5, PoseInterpolation.alpha(100 + iv / 2, 100, iv), 1e-12);
        assertEquals(1, PoseInterpolation.alpha(100 + 3 * iv, 100, iv), 0);
        assertEquals(1, PoseInterpolation.alpha(0, 0, 0), 0);
    }

    @Test
    public void interpolateBlendsPositionAndRotation() {
        Pose a = new Pose(Vec3.ZERO, Quat.IDENTITY);
        Pose b = new Pose(new Vec3(2, 0, 0), Quat.axisAngle(Vec3.Y, Math.PI / 2));
        Pose mid = PoseInterpolation.interpolate(a, b, 0.5);
        close(mid.position(), new Vec3(1, 0, 0), 1e-12);
        assertEquals(Math.PI / 4, mid.rotation().angle(), 1e-9);
        assertSame(a, PoseInterpolation.interpolate(a, b, 0));
        assertSame(b, PoseInterpolation.interpolate(a, b, 1));
    }
}
