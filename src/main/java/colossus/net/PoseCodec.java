package colossus.net;

import colossus.physics.math.Quat;

/**
 * Network quantisation for Segment pose sync (blueprint: "quantized quaternions (smallest-three, 10 bits each)").
 * A unit quaternion packs into one int: 2 bits for the index of the dropped (largest) component, 3 × 10 bits for the
 * others in [−1/√2, 1/√2]. Worst-case angular error is well under 0.2°.
 */
public final class PoseCodec {
    private PoseCodec() {}

    private static final double RANGE = 1 / Math.sqrt(2);
    private static final int BITS = 10, MAX = (1 << BITS) - 1;

    public static int packQuat(Quat q) {
        double[] c = {q.w(), q.x(), q.y(), q.z()};
        int largest = 0;
        for (int i = 1; i < 4; i++) if (Math.abs(c[i]) > Math.abs(c[largest])) largest = i;
        double sign = c[largest] < 0 ? -1 : 1; // q and −q are the same rotation: make the dropped one positive
        int packed = largest;
        int shift = 2;
        for (int i = 0; i < 4; i++) {
            if (i == largest) continue;
            double v = c[i] * sign;
            int qv = (int) Math.round((v + RANGE) / (2 * RANGE) * MAX);
            packed |= Math.max(0, Math.min(MAX, qv)) << shift;
            shift += BITS;
        }
        return packed;
    }

    public static Quat unpackQuat(int packed) {
        int largest = packed & 3;
        double[] c = new double[4];
        double sum = 0;
        int shift = 2;
        for (int i = 0; i < 4; i++) {
            if (i == largest) continue;
            int qv = (packed >>> shift) & MAX;
            c[i] = qv / (double) MAX * 2 * RANGE - RANGE;
            sum += c[i] * c[i];
            shift += BITS;
        }
        c[largest] = Math.sqrt(Math.max(0, 1 - sum));
        return new Quat(c[0], c[1], c[2], c[3]).normalize();
    }
}
