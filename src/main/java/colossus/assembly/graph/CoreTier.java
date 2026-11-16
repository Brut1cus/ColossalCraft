package colossus.assembly.graph;

/**
 * Hull Core tiers and their budgets (blueprint table). All numbers are defaults; server config can lower them.
 * Netherite and up grow with Core Lattice blocks: +16,384 blocks, +32 Segments, +32 joints each.
 */
public enum CoreTier {
    WOODEN(512, 8, 8, 0),
    IRON(4_096, 32, 32, 0),
    STEEL(32_768, 128, 128, 0),
    NETHERITE(131_072, 512, 512, 1),
    SINGULARITY(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 2);

    public final int blocks, segments, joints;
    /** 0 = fixed budget, 1 = grows with lattice, 2 = only the server cap applies. */
    public final int growth;

    public static final int LATTICE_BLOCKS = 16_384, LATTICE_SEGMENTS = 32, LATTICE_JOINTS = 32;

    CoreTier(int blocks, int segments, int joints, int growth) {
        this.blocks = blocks; this.segments = segments; this.joints = joints; this.growth = growth;
    }

    public record Budget(int blocks, int segments, int joints) {}

    /** Effective budget with {@code lattice} Core Lattice blocks attached and the server's hard ceiling. */
    public Budget budget(int lattice, int serverMaxBlocks) {
        long b = blocks, s = segments, j = joints;
        if (growth == 1) {
            b += (long) LATTICE_BLOCKS * lattice;
            s += (long) LATTICE_SEGMENTS * lattice;
            j += (long) LATTICE_JOINTS * lattice;
        }
        return new Budget((int) Math.min(Math.min(b, Integer.MAX_VALUE), serverMaxBlocks),
                (int) Math.min(s, Integer.MAX_VALUE), (int) Math.min(j, Integer.MAX_VALUE));
    }

    /** Lattice heat starts past this many lattice blocks (radiators needed). */
    public static final int LATTICE_COOLING_THRESHOLD = 16;
}
