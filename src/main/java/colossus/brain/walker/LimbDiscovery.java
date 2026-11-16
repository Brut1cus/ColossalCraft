package colossus.brain.walker;

import colossus.assembly.BodyPlanner;
import colossus.assembly.JointKind;
import colossus.brain.ik.IkChain;
import colossus.physics.api.JointType;
import colossus.physics.math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Finds the legs of an assembled machine (blueprint "limb discovery"): every path through the joint tree from the root
 * Segment to a foot. A foot is a leaf Segment that rests on the ground at assembly (its lowest block is within
 * {@link #GROUND_TOLERANCE} of the machine's lowest block) or that the caller marks (Foot Pads). Fixed welds and
 * passive spring dampers on the way are folded into the bones; legs with no driven joint are skipped. Pure Java.
 */
public final class LimbDiscovery {
    public static final double GROUND_TOLERANCE = 1.0;

    private LimbDiscovery() {}

    /** Bottom centre of a Segment's lowest layer of cells, core frame. */
    public static Vec3 footPoint(BodyPlanner.SegmentInput s) {
        int minY = Integer.MAX_VALUE;
        for (int[] c : s.cellsRel()) minY = Math.min(minY, c[1]);
        double sx = 0, sz = 0;
        int n = 0;
        for (int[] c : s.cellsRel()) if (c[1] == minY) { sx += c[0] + 0.5; sz += c[2] + 0.5; n++; }
        return new Vec3(sx / n, minY, sz / n);
    }

    /**
     * @param root       root Segment index
     * @param markedFoot extra feet (for example Segments holding a Foot Pad), may be null
     * @param broken     joint index → true if that joint has snapped (skipped), may be null
     */
    public static List<Limb> discover(List<BodyPlanner.SegmentInput> segments, List<BodyPlanner.JointInput> joints, int root,
                                      IntPredicate markedFoot, IntPredicate broken) {
        double lowest = Double.MAX_VALUE;
        for (BodyPlanner.SegmentInput s : segments) lowest = Math.min(lowest, footPoint(s).y());
        List<Limb> out = new ArrayList<>();
        walk(segments, joints, root, new ArrayList<>(), lowest, markedFoot, broken, out, new boolean[segments.size()]);
        return out;
    }

    private static void walk(List<BodyPlanner.SegmentInput> segs, List<BodyPlanner.JointInput> joints, int seg, List<Integer> path,
                             double lowest, IntPredicate marked, IntPredicate broken, List<Limb> out, boolean[] seen) {
        seen[seg] = true;
        boolean leaf = true;
        for (int j = 0; j < joints.size(); j++) {
            BodyPlanner.JointInput ji = joints.get(j);
            if (ji.parent() != seg || seen[ji.child()] || (broken != null && broken.test(j))) continue;
            leaf = false;
            path.add(j);
            walk(segs, joints, ji.child(), path, lowest, marked, broken, out, seen);
            path.remove(path.size() - 1);
        }
        if (!leaf || path.isEmpty()) return;
        Vec3 foot = footPoint(segs.get(seg));
        boolean grounded = foot.y() - lowest <= GROUND_TOLERANCE || (marked != null && marked.test(seg));
        if (!grounded) return;
        Limb limb = build(joints, path, foot, seg);
        if (limb != null) out.add(limb);
    }

    /** IK chain for a joint path: one link per driven joint, passive joints folded into the bones. */
    static Limb build(List<BodyPlanner.JointInput> joints, List<Integer> path, Vec3 foot, int footSeg) {
        List<Integer> driven = new ArrayList<>();
        for (int j : path) if (joints.get(j).kind().type.motorisable()) driven.add(j);
        if (driven.isEmpty()) return null;
        List<IkChain.Link> links = new ArrayList<>();
        for (int i = 0; i < driven.size(); i++) {
            BodyPlanner.JointInput ji = joints.get(driven.get(i));
            Vec3 next = i + 1 < driven.size() ? joints.get(driven.get(i + 1)).anchorRel() : foot;
            Vec3 bone = next.sub(ji.anchorRel());
            JointKind k = ji.kind();
            links.add(switch (k.type) {
                case HINGE -> IkChain.Link.hinge(ji.axisVector(), bone, k.lower, k.upper);
                case SWIVEL -> IkChain.Link.hinge(ji.axisVector(), bone, 1, -1); // unlimited
                case SLIDER -> IkChain.Link.slider(ji.axisVector(), bone, k.lower, k.upper);
                case BALL -> IkChain.Link.ball(ji.axisVector(), bone, k.upper);
                // a universal joint has no twist; the ball model plus the joint's own constraint is close enough
                case UNIVERSAL -> IkChain.Link.ball(ji.axisVector(), bone, Math.toRadians(60));
                default -> throw new IllegalStateException("not driven: " + k);
            });
        }
        int[] ids = driven.stream().mapToInt(Integer::intValue).toArray();
        return new Limb(ids, new IkChain(links), joints.get(ids[0]).anchorRel(), foot, footSeg);
    }

    /** Is this joint type one the brain drives? */
    public static boolean driven(JointType t) { return t.motorisable(); }
}
