package com.colossalcraft;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue MASS_SCALE, MAX_SPEED, LIFT_SCALE;
    public static final ModConfigSpec.IntValue MAX_BLOCKS;
    public static final ModConfigSpec.BooleanValue GROUND_CRUSHING, ENTITY_CRUSHING, JOINT_BREAKING;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("colossus");
        MASS_SCALE = b.comment("Gameplay mass scale. 1.0 = real block densities (stone = 2500 kg).").defineInRange("massScale", 0.25, 0.01, 10.0);
        MAX_BLOCKS = b.comment("Most blocks one Colossus may hold.").defineInRange("maxBlocks", 8192, 16, 100_000);
        MAX_SPEED = b.comment("Top speed in blocks per second.").defineInRange("maxSpeed", 80.0, 1.0, 400.0);
        LIFT_SCALE = b.comment("Multiplier for Sky Sail lift.").defineInRange("liftScale", 40.0, 0.0, 1000.0);
        GROUND_CRUSHING = b.comment("Heavy landings break weak blocks below.").define("groundCrushing", true);
        ENTITY_CRUSHING = b.comment("Moving Colossi hurt mobs/players they hit.").define("entityCrushing", true);
        JOINT_BREAKING = b.comment("Overloaded joints snap off.").define("jointBreaking", true);
        b.pop();
        SPEC = b.build();
    }
}
