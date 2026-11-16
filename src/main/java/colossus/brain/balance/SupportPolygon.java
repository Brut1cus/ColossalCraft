package colossus.brain.balance;

import colossus.physics.math.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Convex hull of planted feet projected on the ground plane (X/Z). */
public final class SupportPolygon {
    /** Hull vertices counter-clockwise when viewed from above (+Y), as (x, z) pairs. */
    private final double[][] hull;

    public SupportPolygon(List<Vec3> feet) {
        List<double[]> pts = new ArrayList<>();
        for (Vec3 f : feet) pts.add(new double[]{f.x(), f.z()});
        this.hull = convexHull(pts);
    }

    public int vertexCount() { return hull.length; }

    public boolean isEmpty() { return hull.length == 0; }

    /**
     * Signed distance from the point to the polygon edge in the ground plane: positive inside (stability margin),
     * negative outside. For one or two feet (point/segment), the margin is minus the distance to it.
     */
    public double margin(Vec3 p) {
        double x = p.x(), z = p.z();
        if (hull.length == 0) return Double.NEGATIVE_INFINITY;
        if (hull.length == 1) return -Math.hypot(x - hull[0][0], z - hull[0][1]);
        if (hull.length == 2) return -segDist(x, z, hull[0], hull[1]);
        double min = Double.MAX_VALUE;
        boolean inside = true;
        for (int i = 0; i < hull.length; i++) {
            double[] a = hull[i], b = hull[(i + 1) % hull.length];
            double cross = (b[0] - a[0]) * (z - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (cross < 0) inside = false;
            min = Math.min(min, segDist(x, z, a, b));
        }
        return inside ? min : -min;
    }

    public boolean contains(Vec3 p) { return margin(p) >= 0; }

    /** Closest point of the polygon (including its interior) to p, at p's height. */
    public Vec3 closestPoint(Vec3 p) {
        if (hull.length == 0) return p;
        if (contains(p) && hull.length >= 3) return p;
        double bx = hull[0][0], bz = hull[0][1], best = Double.MAX_VALUE;
        for (int i = 0; i < hull.length; i++) {
            double[] a = hull[i], b = hull[(i + 1) % hull.length];
            double[] c = closestOnSeg(p.x(), p.z(), a, b);
            double d = Math.hypot(c[0] - p.x(), c[1] - p.z());
            if (d < best) { best = d; bx = c[0]; bz = c[1]; }
        }
        return new Vec3(bx, p.y(), bz);
    }

    public Vec3 centroid(double y) {
        double sx = 0, sz = 0;
        for (double[] v : hull) { sx += v[0]; sz += v[1]; }
        return hull.length == 0 ? new Vec3(0, y, 0) : new Vec3(sx / hull.length, y, sz / hull.length);
    }

    private static double segDist(double x, double z, double[] a, double[] b) {
        double[] c = closestOnSeg(x, z, a, b);
        return Math.hypot(x - c[0], z - c[1]);
    }

    private static double[] closestOnSeg(double x, double z, double[] a, double[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double l2 = dx * dx + dz * dz;
        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / l2));
        return new double[]{a[0] + t * dx, a[1] + t * dz};
    }

    /** Andrew's monotone chain. */
    static double[][] convexHull(List<double[]> in) {
        List<double[]> p = new ArrayList<>(in);
        p.sort(Comparator.<double[]>comparingDouble(v -> v[0]).thenComparingDouble(v -> v[1]));
        // dedupe
        List<double[]> pts = new ArrayList<>();
        for (double[] v : p) if (pts.isEmpty() || Math.hypot(v[0] - pts.get(pts.size() - 1)[0], v[1] - pts.get(pts.size() - 1)[1]) > 1e-9) pts.add(v);
        if (pts.size() <= 2) return pts.toArray(new double[0][]);
        double[][] h = new double[pts.size() * 2][];
        int k = 0;
        for (double[] v : pts) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], v) <= 0) k--;
            h[k++] = v;
        }
        for (int i = pts.size() - 2, t = k + 1; i >= 0; i--) {
            double[] v = pts.get(i);
            while (k >= t && cross(h[k - 2], h[k - 1], v) <= 0) k--;
            h[k++] = v;
        }
        double[][] out = new double[k - 1][];
        System.arraycopy(h, 0, out, 0, k - 1);
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }
}
