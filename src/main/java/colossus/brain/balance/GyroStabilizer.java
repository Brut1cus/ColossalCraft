package colossus.brain.balance;

import colossus.physics.api.BodyState;
import colossus.physics.math.Mat3;
import colossus.physics.math.Quat;
import colossus.physics.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Gyro Stabilizer: a powered control-moment gyro that keeps one Segment level. Pure Java (unit-tested).
 * <p>
 * <b>Rotor.</b> Each unit is a flywheel. An N×N square of Gyro Stabilizer blocks merges into one rotor N times wider
 * and N² times heavier, so its inertia grows as N⁴ ({@code I = ½·m·r²}). A built-in motor spins it up toward
 * {@code throttle × maxRpm}; bigger rotors take longer (time constant {@code I·ω_max / motorTorque} grows as N²).
 * <p>
 * <b>Torque.</b> The spinning rotor holds angular momentum {@code H = I·ω}. Tilting its gimbal at up to
 * {@code gimbalRate} rad/s turns that momentum and pushes on the hull with up to {@code H × gimbalRate} N·m: more spin
 * and more rotor mass both mean more stabilizing torque.
 * <p>
 * <b>Momentum budget.</b> Torque pushed into the hull is taken from the gyro (angular momentum is conserved), stored as
 * gimbal momentum {@code h} with {@code |h| ≤ H}. A gyro shrugs off stumbles, recoil and gusts, but cannot hold a
 * permanent lean: once {@code h} saturates it stops helping in that direction. The gimbal motors bleed {@code h} back
 * toward zero (exponentially, {@code desaturation × capacity} when full), pushing that torque into the hull and through
 * it into the ground.
 * <p>
 * <b>Control.</b> A PD law toward upright: {@code τ = I_eff·(ωn²·e − 2ζωn·ω)}, where {@code e} is the rotation that
 * takes the Segment's up axis back to world up and {@code I_eff} is the inertia the gyro has to move (the whole
 * machine's, about this Segment's centre of mass). Yaw is damped but not steered. Bodies are assembled upright, so
 * body +Y is "up".
 */
public final class GyroStabilizer {

    /**
     * @param rotorMass         rotor mass of a 1x1 unit, kg (N×N: × N²)
     * @param rotorRadius       rotor radius of a 1x1 unit, m (N×N: × N)
     * @param maxRpm            full-throttle spin
     * @param motorTorque       spin motor torque of a 1x1 unit, N·m (N×N: × N²)
     * @param gimbalRate        fastest gimbal swing, rad/s; torque capacity = H × gimbalRate
     * @param naturalFrequency  PD stiffness, rad/s
     * @param dampingRatio      PD damping (1 = critical)
     * @param desaturation      gimbal re-centring torque at full saturation, as a fraction of capacity
     */
    public record Params(double rotorMass, double rotorRadius, double maxRpm, double motorTorque, double gimbalRate,
                         double naturalFrequency, double dampingRatio, double desaturation) {
        public static final Params DEFAULT = new Params(2000, 0.5, 3000, 26_000, 1.0, 2.0, 1.0, 0.1);
    }

    /** One flywheel (a single block or a merged N×N square). */
    public static final class Rotor {
        public final int footprint;
        public final double inertia, maxOmega, motorTorque;
        private volatile double throttle;
        private volatile double omega;

        public Rotor(int footprint, Params p) {
            this.footprint = Math.max(1, footprint);
            double n = this.footprint;
            double m = p.rotorMass() * n * n, r = p.rotorRadius() * n;
            this.inertia = 0.5 * m * r * r;
            this.maxOmega = p.maxRpm() * 2 * Math.PI / 60;
            this.motorTorque = p.motorTorque() * n * n;
        }

        /** Power level in [0, 1] (redstone signal / 15). Safe to call from any thread. */
        public void setThrottle(double t) { throttle = Math.max(0, Math.min(1, t)); }

        public double throttle() { return throttle; }

        public double omega() { return omega; }

        public double rpm() { return omega * 60 / (2 * Math.PI); }

        public double angularMomentum() { return inertia * omega; }

        /** Spin-up/down time constant, s: motor torque against bearing drag sized so full throttle settles at maxOmega. */
        public double timeConstant() { return maxOmega <= 0 || motorTorque <= 0 ? Double.POSITIVE_INFINITY : inertia * maxOmega / motorTorque; }

        /** Exact first-order step toward {@code throttle × maxOmega}. */
        public void spin(double dt) {
            double target = throttle * maxOmega;
            double tc = timeConstant();
            omega = Double.isInfinite(tc) ? omega : target + (omega - target) * Math.exp(-dt / tc);
        }
    }

    private final List<Rotor> rotors;
    private final Params params;
    /** Inertia the gyro has to move, in this body's frame (kg·m²). */
    private final Mat3 effectiveInertia;
    private volatile Vec3 stored = Vec3.ZERO;
    private volatile Vec3 lastTorque = Vec3.ZERO;

    public GyroStabilizer(List<Rotor> rotors, Mat3 effectiveInertiaLocal, Params params) {
        this.rotors = List.copyOf(rotors);
        this.effectiveInertia = effectiveInertiaLocal;
        this.params = params;
    }

    public List<Rotor> rotors() { return rotors; }

    /** Total rotor angular momentum, N·m·s. */
    public double angularMomentum() {
        double h = 0;
        for (Rotor r : rotors) h += r.angularMomentum();
        return h;
    }

    /** Most torque the gyros can push into the hull right now, N·m. */
    public double torqueCapacity() { return angularMomentum() * params.gimbalRate(); }

    /** Gimbal momentum soaked up so far, N·m·s (world frame). */
    public Vec3 storedMomentum() { return stored; }

    /** Fraction of the momentum budget in use, 0..1. */
    public double saturation() {
        double h = angularMomentum();
        return h <= 1e-9 ? 0 : Math.min(1, stored.length() / h);
    }

    public Vec3 lastTorque() { return lastTorque; }

    /** Rotation vector taking the body's up axis back to world up (axis × angle, world frame). */
    public static Vec3 tiltError(Quat q) {
        Vec3 up = q.rotate(Vec3.Y);
        Vec3 axis = up.cross(Vec3.Y);
        double s = axis.length(), c = up.dot(Vec3.Y);
        double angle = Math.atan2(s, c);
        if (s < 1e-9) return c > 0 ? Vec3.ZERO : q.rotate(Vec3.X).mul(Math.PI); // exactly upside down: roll back over
        return axis.mul(angle / s);
    }

    /**
     * Advance rotors and the controller by one physics step.
     *
     * @return torque to apply to the body this step (world frame, N·m)
     */
    public Vec3 update(BodyState body, double dt) {
        for (Rotor r : rotors) r.spin(dt);
        double hMax = angularMomentum();
        double cap = hMax * params.gimbalRate();
        Vec3 h = stored;
        if (hMax <= 1e-9 || dt <= 0) {
            stored = Vec3.ZERO;
            lastTorque = Vec3.ZERO;
            return Vec3.ZERO;
        }
        if (h.length() > hMax) h = h.normalize().mul(hMax); // a slowing rotor cannot hold what it had

        Quat q = body.pose().rotation();
        Mat3 iw = effectiveInertia.rotated(q);
        double wn = params.naturalFrequency();
        Vec3 want = tiltError(q).mul(wn * wn).sub(body.angularVelocity().mul(2 * params.dampingRatio() * wn));
        Vec3 tau = iw.transform(want);
        // re-centring bleeds h exponentially: desaturation × capacity when full, fading smoothly to zero
        tau = tau.add(h.mul(params.desaturation() * params.gimbalRate()));
        double tl = tau.length();
        if (tl > cap) tau = tau.mul(cap / tl);

        // the hull gains tau·dt of angular momentum, the gimbal loses it; clip at the budget
        Vec3 next = h.sub(tau.mul(dt));
        if (next.length() > hMax) {
            next = next.normalize().mul(hMax);
            tau = h.sub(next).mul(1 / dt);
        }
        stored = next;
        lastTorque = tau;
        return tau;
    }

    // ------------------------------------------------------------------ assembly helpers

    /** A rotor unit: indices into the cell list it was built from, and its footprint N. */
    public record Unit(int footprint, int[] cells) {}

    /**
     * Groups Gyro Stabilizer cells into rotors. A face-connected group that exactly fills an N×N×1 slab is one N×N
     * rotor; any other shape stays as separate 1x1 rotors (same rule as joint merging).
     */
    public static List<Unit> units(List<int[]> cells) {
        int n = cells.size();
        java.util.Map<Long, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) index.put(key(cells.get(i)), i);
        boolean[] seen = new boolean[n];
        List<Unit> out = new ArrayList<>();
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = 0; i < n; i++) {
            if (seen[i]) continue;
            List<Integer> group = new ArrayList<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(i);
            seen[i] = true;
            while (!queue.isEmpty()) {
                int c = queue.poll();
                group.add(c);
                int[] p = cells.get(c);
                for (int[] d : dirs) {
                    Integer o = index.get(key(new int[]{p[0] + d[0], p[1] + d[1], p[2] + d[2]}));
                    if (o != null && !seen[o]) { seen[o] = true; queue.add(o); }
                }
            }
            int size = squareSlab(cells, group);
            if (size > 0) {
                out.add(new Unit(size, group.stream().mapToInt(Integer::intValue).toArray()));
            } else {
                for (int c : group) out.add(new Unit(1, new int[]{c}));
            }
        }
        return out;
    }

    /** N if the group exactly fills an N×N×1 box, else 0. */
    static int squareSlab(List<int[]> cells, List<Integer> group) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int c : group) for (int a = 0; a < 3; a++) {
            min[a] = Math.min(min[a], cells.get(c)[a]);
            max[a] = Math.max(max[a], cells.get(c)[a]);
        }
        int[] ext = {max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1};
        java.util.Arrays.sort(ext);
        // the group is face-connected with no duplicates, so filling the box means count == volume
        return ext[0] == 1 && ext[1] == ext[2] && group.size() == ext[1] * ext[2] ? ext[1] : 0;
    }

    private static long key(int[] p) { return ((long) (p[0] & 0x1FFFFF) << 42) | ((long) (p[1] & 0x1FFFFF) << 21) | (p[2] & 0x1FFFFF); }

    /**
     * Inertia of several bodies about a point, in a common frame (parallel-axis theorem):
     * {@code Σ I_i + m_i·(|d_i|²·E − d_i·d_iᵀ)} with {@code d_i} from the point to body i's centre of mass.
     */
    public static Mat3 combinedInertia(List<Mat3> inertias, List<Double> masses, List<Vec3> centres, Vec3 about) {
        Mat3 sum = Mat3.ZERO;
        for (int i = 0; i < inertias.size(); i++) {
            Vec3 d = centres.get(i).sub(about);
            double m = masses.get(i), dd = d.lengthSq();
            Mat3 shift = new Mat3(
                    m * (dd - d.x() * d.x()), -m * d.x() * d.y(), -m * d.x() * d.z(),
                    -m * d.y() * d.x(), m * (dd - d.y() * d.y()), -m * d.y() * d.z(),
                    -m * d.z() * d.x(), -m * d.z() * d.y(), m * (dd - d.z() * d.z()));
            sum = sum.add(inertias.get(i)).add(shift);
        }
        return sum;
    }
}
