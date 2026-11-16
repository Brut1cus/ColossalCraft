package colossus.assembly.graph;

/**
 * What the scanner needs to know about each cell. The game layer implements this over a Level
 * ({@code colossus.assembly.LevelVoxelSource}); tests implement it over a map.
 */
public interface VoxelSource {

    enum Kind {
        /** Air, fluids, replaceable plants: not part of the build. */
        EMPTY,
        /** A normal block that will join a Segment. */
        BLOCK,
        /** A joint block (Segment boundary). */
        JOINT,
        /** Tagged {@code colossus:non_assemblable} or claimed by another ship mod: never picked up. */
        EXCLUDED,
        /** A Hull Core (or Core Lattice block). */
        CORE
    }

    /** Joint block data: which constraint it makes and its axis (0 = X, 1 = Y, 2 = Z). */
    record JointInfo(String type, int axis) {}

    Kind kind(int x, int y, int z);

    /** Only called for {@link Kind#JOINT} cells. */
    JointInfo joint(int x, int y, int z);

    /** Mass in kg of the block at the cell (block physics table × shape volume). */
    double mass(int x, int y, int z);

    /** True if the cell is solid world terrain that is not part of the build (for the "has support" check). */
    default boolean isSupportingGround(int x, int y, int z) { return kind(x, y, z) == Kind.EXCLUDED; }

    /**
     * Segment Glue: true if the player glued these two neighbouring cells into the same Segment, which overrides a
     * joint boundary between them (merge). Default: no glue.
     */
    default boolean glued(int ax, int ay, int az, int bx, int by, int bz) { return false; }

    /** True for Core Lattice blocks (they join the root Segment and raise the Netherite Core's budget). */
    default boolean isLattice(int x, int y, int z) { return false; }
}
