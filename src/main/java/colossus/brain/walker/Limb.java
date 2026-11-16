package colossus.brain.walker;

import colossus.brain.ik.IkChain;
import colossus.physics.math.Vec3;

/**
 * One leg found by {@link LimbDiscovery}: the joints from the root Segment down to a foot, as an IK chain.
 * Positions are in the core frame (relative to the Hull Core block); at assembly every body frame is aligned with it,
 * so the chain's base frame is the root Segment's frame.
 *
 * @param joints    joint indices (into the Colossus's joint list) from hip to foot, one per IK link
 * @param chain     the IK chain: one link per driven joint, bones measured from the assembled pose
 * @param hip       first joint's centre (the chain's base point), core frame
 * @param footRest  bottom centre of the foot at assembly, core frame
 * @param footSegment the Segment that touches the ground
 */
public record Limb(int[] joints, IkChain chain, Vec3 hip, Vec3 footRest, int footSegment) {
    /** Hip-to-foot distance at assembly. */
    public double restLength() { return footRest.sub(hip).length(); }
}
