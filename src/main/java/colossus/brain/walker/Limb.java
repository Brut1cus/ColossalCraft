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
 * @param ankle       joint index of the ankle (a ball or universal joint at the end of the leg, kept level instead of
 *                    being part of the IK chain), or −1 for a leg without one
 * @param ankleRest   the ankle's centre (core frame), the IK chain's end effector when there is an ankle
 */
public record Limb(int[] joints, IkChain chain, Vec3 hip, Vec3 footRest, int footSegment, int ankle, Vec3 ankleRest) {
    public boolean hasAnkle() { return ankle >= 0; }

    /** Foot point relative to the ankle (core frame); zero without an ankle. */
    public Vec3 footBelowAnkle() { return hasAnkle() ? footRest.sub(ankleRest) : Vec3.ZERO; }

    /** Hip-to-foot distance at assembly. */
    public double restLength() { return footRest.sub(hip).length(); }
}
