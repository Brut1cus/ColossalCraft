package colossus.physics;

import colossus.physics.api.MassProperties;
import colossus.physics.math.Mat3;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

public class MathTest {
    static void near(double a, double b, double eps) { assertEquals(b, a, eps); }
    static void near(Vec3 a, Vec3 b, double eps) { assertTrue(a + " vs " + b, a.distance(b) < eps); }

    @Test public void quaternionRotatesAndInverts() {
        Quat q = Quat.axisAngle(Vec3.Y, Math.PI / 2);
        near(q.rotate(Vec3.X), new Vec3(0, 0, -1), 1e-9);
        near(q.inverseRotate(q.rotate(new Vec3(1, 2, 3))), new Vec3(1, 2, 3), 1e-9);
        near(q.angle(), Math.PI / 2, 1e-9);
        near(q.toRotationVector(), new Vec3(0, Math.PI / 2, 0), 1e-9);
    }

    @Test public void fromToMapsVectors() {
        Vec3 a = new Vec3(1, 2, -1).normalize(), b = new Vec3(-3, 0.5, 2).normalize();
        near(Quat.fromTo(a, b).rotate(a), b, 1e-9);
        near(Quat.fromTo(Vec3.X, Vec3.X.neg()).rotate(Vec3.X), Vec3.X.neg(), 1e-9);
    }

    @Test public void matrixInverse() {
        Mat3 m = new Mat3(4, 1, 0, 1, 3, 1, 0, 1, 2);
        Mat3 p = m.mul(m.inverse());
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) near(p.get(i, j), i == j ? 1 : 0, 1e-12);
    }

    @Test public void blockInertiaMatchesSolidBox() {
        // 2x2x2 cube of 1 kg blocks == solid 8 kg cube of side 2: I = m/12 * (4 + 4) = 16/3
        MassProperties.Builder b = new MassProperties.Builder();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) b.addBlock(1, x, y, z);
        MassProperties mp = b.build();
        near(mp.mass(), 8, 1e-12);
        near(mp.centerOfMass(), new Vec3(1, 1, 1), 1e-12);
        near(mp.inertia().get(0, 0), 16.0 / 3, 1e-9);
        near(mp.inertia().get(0, 1), 0, 1e-9);
    }

    @Test public void removingABlockIsIncremental() {
        MassProperties.Builder b = new MassProperties.Builder();
        b.addBlock(1000, 0, 0, 0).addBlock(1000, 1, 0, 0).addBlock(5000, 2, 0, 0);
        b.remove(5000, new Vec3(2.5, 0.5, 0.5), 1, 1, 1);
        MassProperties mp = b.build();
        near(mp.mass(), 2000, 1e-9);
        near(mp.centerOfMass(), new Vec3(1, 0.5, 0.5), 1e-9);
        // two cubes side by side along X: Iyy = 2*(1000/6) + 2*1000*0.5²
        near(mp.inertia().get(1, 1), 2000.0 / 6 + 500, 1e-6);
    }
}
