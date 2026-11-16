package colossus.assembly;

import net.minecraft.world.level.block.RotatedPillarBlock;

/**
 * A joint block. The axis comes from the face it is placed against (like a log). Joint blocks are Segment boundaries:
 * the scan splits the build here, and touching joint blocks of the same kind and axis merge into one bigger joint
 * (an N×N footprint is N^k times stronger, see {@link colossus.kinetics.ComponentScaling}).
 */
public class JointBlock extends RotatedPillarBlock {
    public final JointKind kind;

    public JointBlock(Properties props, JointKind kind) {
        super(props);
        this.kind = kind;
    }
}
