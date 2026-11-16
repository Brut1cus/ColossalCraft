package colossus.core;

import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code config/colossus-server.toml} (per world: {@code serverconfig/}). Server owners set every ceiling here. */
public final class ColossusConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_COLOSSUS;
    public static final ModConfigSpec.IntValue MAX_COLOSSI_PER_LEVEL;
    public static final ModConfigSpec.BooleanValue LATTICE_GROWTH;
    public static final ModConfigSpec.BooleanValue ENABLE_ASSEMBLY;

    public static final ModConfigSpec.ConfigValue<String> PHYSICS_BACKEND;
    public static final ModConfigSpec.IntValue PHYSICS_HZ;
    public static final ModConfigSpec.IntValue XPBD_SUBSTEPS;
    public static final ModConfigSpec.DoubleValue GRAVITY;
    public static final ModConfigSpec.DoubleValue AIR_SCALE_HEIGHT;

    public static final ModConfigSpec.BooleanValue JOINTS_HOLD_POSE;
    public static final ModConfigSpec.BooleanValue JOINT_BREAKING;
    public static final ModConfigSpec.DoubleValue STRAIN_BREAK_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Size limits").push("limits");
        MAX_BLOCKS_PER_COLOSSUS = b.comment("Hard ceiling on blocks in one Colossus, whatever its Hull Core tier or lattice size.")
                .defineInRange("maxBlocksPerColossus", 1_000_000, 16, 50_000_000);
        MAX_COLOSSI_PER_LEVEL = b.comment("Most assembled Colossi per dimension.").defineInRange("maxColossiPerLevel", 64, 1, 10_000);
        LATTICE_GROWTH = b.comment("Allow Core Lattice blocks to grow a Netherite Core's budget.").define("latticeGrowth", true);
        ENABLE_ASSEMBLY = b.comment("Allow Hull Cores to assemble Colossi at all (scanning always works).").define("enableAssembly", true);
        b.pop();

        b.comment("Physics").push("physics");
        PHYSICS_BACKEND = b.comment("auto, jolt or xpbd. 'auto' uses Jolt when its natives are bundled, else the built-in XPBD solver.")
                .define("backend", "auto");
        PHYSICS_HZ = b.comment("Fixed physics rate on the physics thread.").defineInRange("rateHz", 60, 20, 240);
        XPBD_SUBSTEPS = b.comment("XPBD substeps per physics step. More = stiffer joints, more CPU.").defineInRange("xpbdSubsteps", 12, 1, 64);
        GRAVITY = b.comment("Gravity in m/s² (1 block = 1 m).").defineInRange("gravity", 9.81, 0.0, 100.0);
        AIR_SCALE_HEIGHT = b.comment("Air density halves roughly every 0.7 × this many blocks above sea level.")
                .defineInRange("airScaleHeight", 600.0, 10.0, 100_000.0);
        b.pop();

        b.comment("Joints").push("joints");
        JOINTS_HOLD_POSE = b.comment("Until a walker brain drives them, motorised joints servo-hold the pose they were assembled in.")
                .define("holdPose", true);
        JOINT_BREAKING = b.comment("Joints overloaded past their size rating for too long snap.").define("breaking", true);
        STRAIN_BREAK_SECONDS = b.comment("Seconds of sustained overload before a joint snaps.").defineInRange("strainBreakSeconds", 3.0, 0.1, 600.0);
        b.pop();

        SPEC = b.build();
    }

    private ColossusConfig() {}
}
