package com.colossalcraft.colossus;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.*;

/**
 * Stage 3: leg joints driven by motor torque.
 *
 * Each leg is a chain of rigid links (the parts between its joints), solved with Featherstone's articulated-body algorithm:
 * given the torque at every joint, it gives every joint's angular acceleration exactly, with the links' real masses and
 * inertias, gravity, and the body's own motion. The IK no longer sets a lifted leg's pose: it only gives each joint a target.
 * Each joint's motor is a servo: it pushes toward the target with a torque (holding the leg's weight up, plus a spring and
 * damper sized to the link's inertia), never more than the motor (and its gears) can give, never faster than its top speed.
 * So heavy legs swing slowly, weak motors can't lift a leg in time and the foot lands short, and a leg overshoots and settles.
 * A planted leg is held where its foot is (the foot can't move); what its joints must hold there is checked elsewhere
 * (the walker limits how hard a foot can push by its joints' torque).
 *
 * Spatial (6D) vectors: [angular x y z, linear x y z], all in the Colossus' own frame, about its origin.
 */
public final class LegDynamics {
    /** Joint angle and speed (by joint id) for the lifted legs' dynamics. NaN = not started. */
    double[] q = new double[0], qd = new double[0];
    /** Biggest distance this tick between where a lifted leg's joints are and where the IK wants them (radians): how far it lags. */
    public double lag;
    private StructureModel built;
    private Link[][] links = new Link[0][];

    /** A link: everything a joint moves but the next joint down doesn't (rest pose): mass, centre of mass, inertia about it. */
    private record Link(double m, Vec3 com, double[] inertia) {}

    private void prepare(StructureModel m) {
        if (built == m) return;
        built = m;
        q = new double[m.joints.size()]; qd = new double[m.joints.size()];
        Arrays.fill(q, Double.NaN);
        links = new Link[m.legChains.size()][];
        for (int li = 0; li < m.legChains.size(); li++) {
            List<Integer> chain = m.legChains.get(li).joints();
            links[li] = new Link[chain.size()];
            for (int k = 0; k < chain.size(); k++) {
                Set<Integer> stop = k + 1 < chain.size() ? Set.of(chain.get(k + 1)) : Set.of();
                List<StructureModel.Seg> segs = new ArrayList<>();
                ArrayDeque<Integer> qq = new ArrayDeque<>(m.joints.get(chain.get(k)).childSegs);
                while (!qq.isEmpty() && segs.size() < 4000) {
                    StructureModel.Seg s = m.segs.get(qq.poll()); segs.add(s);
                    for (int cj : s.childJoints) if (!stop.contains(cj)) qq.addAll(m.joints.get(cj).childSegs);
                }
                double mass = 0, cx = 0, cy = 0, cz = 0;
                for (StructureModel.Seg s : segs) { mass += s.mass; cx += s.mass * s.com.x; cy += s.mass * s.com.y; cz += s.mass * s.com.z; }
                Vec3 com = mass > 0 ? new Vec3(cx / mass, cy / mass, cz / mass) : m.joints.get(chain.get(k)).pivot;
                double[] in = new double[9];
                for (StructureModel.Seg s : segs) { // parallel axis: each part's own inertia, moved to the link's centre of mass
                    double dx = s.com.x - com.x, dy = s.com.y - com.y, dz = s.com.z - com.z, d2 = dx * dx + dy * dy + dz * dz;
                    double[] r = {dx, dy, dz};
                    for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) in[a * 3 + b] += s.inertia[a * 3 + b] + s.mass * ((a == b ? d2 : 0) - r[a] * r[b]);
                }
                links[li][k] = new Link(Math.max(1, mass), com, in);
            }
        }
    }

    /** Move every lifted leg's joints by their motors' torque for one tick (in sub-steps). Planted legs follow their IK. */
    void step(ColossusEntity c, WalkerController w, double dt, double ratio, Vec3 bodyAccelWorld) {
        StructureModel m = c.model;
        if (m.ikAngles == null) return;
        prepare(m);
        lag = 0;
        Matrix4d[] art = c.currentArt();
        double cs = Math.cos(-c.yaw), sn = Math.sin(-c.yaw); // world -> the Colossus' frame (about the vertical)
        Vec3 aBody = new Vec3(bodyAccelWorld.x * cs - bodyAccelWorld.z * sn, bodyAccelWorld.y, bodyAccelWorld.x * sn + bodyAccelWorld.z * cs);
        for (int li = 0; li < m.legChains.size() && li < w.state.length; li++) {
            StructureModel.LegChain L = m.legChains.get(li);
            List<Integer> chain = L.joints();
            boolean lifted = w.state[li] == WalkerController.State.SWING;
            if (!lifted) { // planted (or falling): the leg is where the IK puts it; remember how fast it moved
                for (int jid : chain) { double t = m.ikAngles[jid]; if (Double.isNaN(t)) continue; qd[jid] = Double.isNaN(q[jid]) ? 0 : (t - q[jid]) / dt; q[jid] = t; }
                continue;
            }
            int n = chain.size();
            double[] target = new double[n], tmax = new double[n], vmax = new double[n];
            for (int k = 0; k < n; k++) {
                StructureModel.Joint J = m.joints.get(chain.get(k));
                target[k] = Double.isNaN(m.ikAngles[J.id]) ? 0 : m.ikAngles[J.id];
                if (Double.isNaN(q[J.id])) { q[J.id] = target[k]; qd[J.id] = 0; }
                tmax[k] = Math.max(0, J.motorTorque * ratio);
                vmax[k] = J.maxSpeed > 0 ? Math.toRadians(J.maxSpeed) : 6;
            }
            int parentSeg = Math.max(0, m.joints.get(chain.get(0)).parentSeg);
            Matrix4d base = parentSeg < art.length ? new Matrix4d(art[parentSeg]) : new Matrix4d();
            double wn = Math.max(4, Math.min(20, 6 * Math.sqrt(StructureModel.G / Math.max(0.5, L.length())))); // servo stiffness: big legs respond slower
            int SUB = 4; double h = dt / SUB;
            for (int s = 0; s < SUB; s++) {
                for (int k = 0; k < n; k++) m.ikAngles[chain.get(k)] = q[chain.get(k)]; // the pose is where the joints really are
                double[][] S = new double[n][], I = new double[n][];
                pose(m, chain, links[li], base, S, I);
                double[] qq = new double[n], qv = new double[n];
                for (int k = 0; k < n; k++) { qq[k] = q[chain.get(k)]; qv[k] = qd[chain.get(k)]; }
                double[] v0 = {0, c.yawRate, 0, 0, 0, 0};
                double[] a0 = {0, 0, 0, aBody.x, aBody.y + StructureModel.G, aBody.z}; // gravity as an upward acceleration of the base
                double[] D = new double[n];
                double[] bias = abaBias(S, I, qv, v0, a0, D);
                double[] tau = new double[n];
                for (int k = 0; k < n; k++) {
                    double err = target[k] - qq[k];
                    double t = bias[k] + D[k] * (wn * wn * err - 2 * wn * qv[k]);
                    tau[k] = Math.max(-tmax[k], Math.min(tmax[k], t));
                }
                double[] qdd = aba(S, I, qv, tau, v0, a0);
                for (int k = 0; k < n; k++) {
                    StructureModel.Joint J = m.joints.get(chain.get(k));
                    double v = qv[k] + qdd[k] * h;
                    v = Math.max(-vmax[k], Math.min(vmax[k], v));
                    double a = qq[k] + v * h, lim = J.clampAngle(a);
                    if (lim != a) { a = lim; v = 0; } // the joint's stop
                    q[J.id] = a; qd[J.id] = v;
                }
            }
            for (int k = 0; k < n; k++) { int jid = chain.get(k); lag = Math.max(lag, Math.abs(target[k] - q[jid])); m.ikAngles[jid] = q[jid]; }
        }
    }

    /** The leg's links where the current angles put them: each joint's motion axis S (6D) and each link's spatial inertia I (6x6). */
    private static void pose(StructureModel m, List<Integer> chain, Link[] links, Matrix4d base, double[][] S, double[][] I) {
        Matrix4d T = new Matrix4d(base);
        for (int k = 0; k < chain.size(); k++) {
            StructureModel.Joint J = m.joints.get(chain.get(k));
            Vec3 ax = StructureModel.axisVec(J.axis);
            Matrix4d pre = new Matrix4d(T).translate(J.pivot.x, J.pivot.y, J.pivot.z);
            if (J.universal) { // a ball hip: its twist and sideways tilt (set by the IK) come first, then the swing this joint drives
                Vec3 f = Vec3.atLowerCornerOf(m.forward.getNormal());
                double yaw = J.id < m.ikYaw.length && !Double.isNaN(m.ikYaw[J.id]) ? m.ikYaw[J.id] : 0, roll = J.id < m.ikRoll.length && !Double.isNaN(m.ikRoll[J.id]) ? m.ikRoll[J.id] : 0;
                pre.rotate(yaw, 0, 1, 0).rotate(roll, f.x, f.y, f.z);
            }
            Vector3d P = pre.transformPosition(new Vector3d(0, 0, 0));
            Vector3d A = pre.transformDirection(new Vector3d(ax.x, ax.y, ax.z)).normalize();
            S[k] = new double[]{A.x, A.y, A.z, P.y * A.z - P.z * A.y, P.z * A.x - P.x * A.z, P.x * A.y - P.y * A.x}; // (axis, P x axis)
            double ang = Double.isNaN(m.ikAngles[J.id]) ? 0 : m.ikAngles[J.id];
            T = new Matrix4d(pre).rotate(ang, ax.x, ax.y, ax.z).translate(-J.pivot.x, -J.pivot.y, -J.pivot.z);
            Link L = links[k];
            Vector3d c = T.transformPosition(new Vector3d(L.com().x, L.com().y, L.com().z));
            double[] R = new double[9]; // the link's rotation, to turn its inertia
            Vector3d ex = T.transformDirection(new Vector3d(1, 0, 0)), ey = T.transformDirection(new Vector3d(0, 1, 0)), ez = T.transformDirection(new Vector3d(0, 0, 1));
            R[0] = ex.x; R[3] = ex.y; R[6] = ex.z; R[1] = ey.x; R[4] = ey.y; R[7] = ey.z; R[2] = ez.x; R[5] = ez.y; R[8] = ez.z;
            I[k] = spatialInertia(L.m(), new double[]{c.x, c.y, c.z}, rot(R, L.inertia()));
        }
    }
    private static double[] rot(double[] R, double[] in) { // R * in * R^T
        double[] t = new double[9], o = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) t[i * 3 + j] += R[i * 3 + k] * in[k * 3 + j];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) o[i * 3 + j] += t[i * 3 + k] * R[j * 3 + k];
        return o;
    }
    /** Spatial inertia about the origin of a body with mass m, centre of mass c and rotational inertia Ic about c (6x6 row-major). */
    public static double[] spatialInertia(double m, double[] c, double[] Ic) {
        double[] X = new double[36];
        double[] cx = {0, -c[2], c[1], c[2], 0, -c[0], -c[1], c[0], 0};
        double cc = c[0] * c[0] + c[1] * c[1] + c[2] * c[2];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            X[i * 6 + j] = Ic[i * 3 + j] + m * ((i == j ? cc : 0) - c[i] * c[j]);   // Ic + m cx cx^T
            X[i * 6 + j + 3] = m * cx[i * 3 + j];                                      // m cx
            X[(i + 3) * 6 + j] = -m * cx[i * 3 + j];                                   // m cx^T
            X[(i + 3) * 6 + j + 3] = i == j ? m : 0;                                   // m E
        }
        return X;
    }
    private static double[] mul(double[] M, double[] v) { double[] o = new double[6]; for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) o[i] += M[i * 6 + j] * v[j]; return o; }
    private static double dot(double[] a, double[] b) { double s = 0; for (int i = 0; i < 6; i++) s += a[i] * b[i]; return s; }
    private static double[] add(double[] a, double[] b) { double[] o = new double[6]; for (int i = 0; i < 6; i++) o[i] = a[i] + b[i]; return o; }
    private static double[] scale(double[] a, double s) { double[] o = new double[6]; for (int i = 0; i < 6; i++) o[i] = a[i] * s; return o; }
    /** Motion cross product v x m. */
    static double[] crm(double[] v, double[] m) {
        double wx = v[0], wy = v[1], wz = v[2], vx = v[3], vy = v[4], vz = v[5];
        return new double[]{wy * m[2] - wz * m[1], wz * m[0] - wx * m[2], wx * m[1] - wy * m[0],
                wy * m[5] - wz * m[4] + vy * m[2] - vz * m[1], wz * m[3] - wx * m[5] + vz * m[0] - vx * m[2], wx * m[4] - wy * m[3] + vx * m[1] - vy * m[0]};
    }
    /** Force cross product v x* f. */
    static double[] crf(double[] v, double[] f) {
        double wx = v[0], wy = v[1], wz = v[2], vx = v[3], vy = v[4], vz = v[5];
        return new double[]{wy * f[2] - wz * f[1] + vy * f[5] - vz * f[4], wz * f[0] - wx * f[2] + vz * f[3] - vx * f[5], wx * f[1] - wy * f[0] + vx * f[4] - vy * f[3],
                wy * f[5] - wz * f[4], wz * f[3] - wx * f[5], wx * f[4] - wy * f[3]};
    }

    /** Featherstone's articulated-body algorithm for a serial chain: joint accelerations from joint torques. */
    public static double[] aba(double[][] S, double[][] I, double[] qd, double[] tau, double[] v0, double[] a0) {
        int n = S.length;
        double[][] v = new double[n][], c = new double[n][], pA = new double[n][], IA = new double[n][], U = new double[n][];
        double[] D = new double[n], u = new double[n];
        double[] vp = v0;
        for (int k = 0; k < n; k++) { // velocities, velocity-product accelerations, bias forces
            double[] sq = scale(S[k], qd[k]);
            v[k] = add(vp, sq); c[k] = crm(vp, sq);
            IA[k] = I[k].clone(); pA[k] = crf(v[k], mul(I[k], v[k]));
            vp = v[k];
        }
        for (int k = n - 1; k >= 0; k--) { // articulated inertias, from the foot up
            U[k] = mul(IA[k], S[k]); D[k] = Math.max(1e-9, dot(S[k], U[k])); u[k] = tau[k] - dot(S[k], pA[k]);
            if (k > 0) {
                double[] Ia = IA[k].clone();
                for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) Ia[i * 6 + j] -= U[k][i] * U[k][j] / D[k];
                double[] pa = add(add(pA[k], mul(Ia, c[k])), scale(U[k], u[k] / D[k]));
                for (int i = 0; i < 36; i++) IA[k - 1][i] += Ia[i];
                pA[k - 1] = add(pA[k - 1], pa);
            }
        }
        double[] qdd = new double[n]; double[] ap = a0;
        for (int k = 0; k < n; k++) { // accelerations, from the hip down
            double[] a1 = add(ap, c[k]);
            qdd[k] = (u[k] - dot(U[k], a1)) / D[k];
            ap = add(a1, scale(S[k], qdd[k]));
        }
        return qdd;
    }
    /** The torques that hold every joint's speed steady right now (gravity, the body's motion, swinging), and each joint's
     *  articulated inertia D (how heavy it feels to that joint's motor). */
    public static double[] abaBias(double[][] S, double[][] I, double[] qd, double[] v0, double[] a0, double[] Dout) {
        int n = S.length;
        double[][] v = new double[n][], a = new double[n][], f = new double[n][];
        double[] vp = v0, ap = a0;
        for (int k = 0; k < n; k++) {
            double[] sq = scale(S[k], qd[k]);
            v[k] = add(vp, sq); a[k] = add(ap, crm(vp, sq));
            f[k] = add(mul(I[k], a[k]), crf(v[k], mul(I[k], v[k])));
            vp = v[k]; ap = a[k];
        }
        double[] tau = new double[n];
        for (int k = n - 1; k >= 0; k--) { tau[k] = dot(S[k], f[k]); if (k > 0) f[k - 1] = add(f[k - 1], f[k]); }
        // articulated inertia seen by each joint
        double[][] IA = new double[n][];
        for (int k = 0; k < n; k++) IA[k] = I[k].clone();
        for (int k = n - 1; k >= 0; k--) {
            double[] U = mul(IA[k], S[k]); double D = Math.max(1e-9, dot(S[k], U)); Dout[k] = D;
            if (k > 0) for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) IA[k - 1][i * 6 + j] += IA[k][i * 6 + j] - U[i] * U[j] / D;
        }
        return tau;
    }
}
