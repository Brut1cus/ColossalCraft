package com.colossalcraft.colossus;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.*;

/**
 * Joints outside the legs (arms, tails, heads, turrets set to Sway or Program) driven by motor torque, like the legs (Stage 3).
 * Each one's animation (the swing, the keyframes) is only its target. Its motor is a servo: it holds up what hangs from it
 * (gravity on everything below it, at its current angle) and pushes toward the target with a spring and damper sized to how
 * heavy that is to turn, never harder than the motor and gears can, never faster than its top speed. So a heavy arm swings
 * slowly and overshoots a little, a weak motor lets its limb droop and lag, and a limb hanging from a joint whose motor is
 * too weak sags until the joint's stop.
 */
final class JointDynamics {
    private double[] q = new double[0], qd = new double[0], inertia = new double[0];
    private StructureModel built;

    private void prepare(StructureModel m) {
        if (built == m) return;
        built = m;
        int n = m.joints.size();
        q = new double[n]; qd = new double[n]; inertia = new double[n];
        Arrays.fill(q, Double.NaN);
        Set<Integer> legJoints = new HashSet<>(); for (StructureModel.LegChain L : m.legChains) legJoints.addAll(L.joints());
        for (StructureModel.Joint j : m.joints) {
            if (legJoints.contains(j.id) || !driven(j)) { inertia[j.id] = 0; continue; }
            // how heavy everything below this joint is to turn about its axis (rest pose)
            Vec3 a = StructureModel.axisVec(j.axis); double I = 0;
            ArrayDeque<Integer> qq = new ArrayDeque<>(j.childSegs);
            int guard = 0;
            while (!qq.isEmpty() && guard++ < 4000) {
                StructureModel.Seg s = m.segs.get(qq.poll());
                double[] in = s.inertia; double own = 0;
                double[] av = {a.x, a.y, a.z};
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) own += av[r] * in[r * 3 + c] * av[c];
                Vec3 d = s.com.subtract(j.pivot); Vec3 perp = d.subtract(a.scale(a.dot(d)));
                I += own + s.mass * perp.lengthSqr();
                for (int cj : s.childJoints) qq.addAll(m.joints.get(cj).childSegs);
            }
            inertia[j.id] = Math.max(1, I);
        }
    }
    private static boolean driven(StructureModel.Joint j) {
        return (j.mode == StructureModel.SWAY || j.mode == StructureModel.PROGRAM) && !j.ball && !j.universal && !j.childSegs.isEmpty() && j.mergedInto < 0;
    }

    /** One tick for every driven joint. Returns true if any joint is driven this way (their angles must go to clients). */
    boolean step(ColossusEntity c, double dt, double ratio) {
        StructureModel m = c.model;
        if (m == null || m.ikAngles == null) return false;
        prepare(m);
        Matrix4d[] art = c.currentArt();
        boolean any = false;
        double time = c.tickCount;
        for (StructureModel.Joint j : m.joints) {
            if (inertia[j.id] <= 0) continue;
            any = true;
            // its target: the animation it would play by itself
            double keep = m.ikAngles[j.id]; m.ikAngles[j.id] = Double.NaN;
            double target = m.angle(j, c.walkPhase, c.walkBlend, c.spin, time);
            m.ikAngles[j.id] = keep;
            if (Double.isNaN(q[j.id])) { q[j.id] = target; qd[j.id] = 0; }
            if (!j.active()) target = q[j.id]; // no working motor: it just hangs
            // gravity's pull about the axis at the current angle: everything below it, at its current place
            int ps = Math.max(0, j.parentSeg), cs = j.childSegs.get(0);
            double tg = 0;
            if (ps < art.length && cs < art.length) {
                Vector3d piv = art[ps].transformPosition(new Vector3d(j.pivot.x, j.pivot.y, j.pivot.z));
                Vec3 ax = StructureModel.axisVec(j.axis); Vector3d a = art[ps].transformDirection(new Vector3d(ax.x, ax.y, ax.z)).normalize();
                Vector3d com = art[cs].transformPosition(new Vector3d(j.subCom.x, j.subCom.y, j.subCom.z));
                double rx = com.x - piv.x, ry = com.y - piv.y, rz = com.z - piv.z, fy = -j.subMass * StructureModel.G;
                // (r x F) . a with F = (0, fy, 0)
                tg = (-rz * fy) * a.x + (rx * fy) * a.z;
            }
            double I = inertia[j.id], wn = 8, tmax = j.active() ? Math.max(0, j.motorTorque * ratio) : 0, vmax = j.maxSpeed > 0 ? Math.toRadians(j.maxSpeed) : 4;
            int SUB = 4; double h = dt / SUB;
            for (int s = 0; s < SUB; s++) {
                double tau = Math.max(-tmax, Math.min(tmax, -tg + I * (wn * wn * (target - q[j.id]) - 2 * wn * qd[j.id])));
                double v = qd[j.id] + (tau + tg) / I * h - qd[j.id] * 0.02; // a little friction in the joint
                v = Math.max(-vmax, Math.min(vmax, v));
                double a1 = q[j.id] + v * h, lim = j.clampAngle(a1);
                if (lim != a1) { a1 = lim; v = 0; }
                q[j.id] = a1; qd[j.id] = v;
            }
            m.ikAngles[j.id] = q[j.id];
        }
        return any;
    }
}
