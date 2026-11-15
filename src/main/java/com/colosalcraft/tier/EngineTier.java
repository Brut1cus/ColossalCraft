package com.colosalcraft.tier;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import com.colosalcraft.ModRegistry;

/** Engine hierarchy: coal -> zero point. Output in ColossalJoules per tick (CJ/t). */
public enum EngineTier {
    EMBER("Ember Boiler", 40, 1_000_000L),
    BLAZE("Blaze Combustor", 160, 4_000_000L),
    REDSTONE("Redstone Dynamo", 500, 10_000_000L),
    ION("Ender Ion Drive", 1_400, 40_000_000L),
    FISSION("Fission Reactor", 6_000, 600_000_000L),
    FUSION("Fusion Torus", 25_000, 2_500_000_000L),
    ZERO_POINT("Zero-Point Core", 100_000, Long.MAX_VALUE / 4);

    public final String title;
    public final int output;
    public final long capacity;

    EngineTier(String t, int out, long cap) { title = t; output = out; capacity = cap; }

    /** Energy given by one fuel item, 0 if not a valid fuel. ZERO_POINT returns -1 for a priming item. */
    public long fuelValue(ItemStack s) {
        switch (this) {
            case EMBER: return (long) s.getBurnTime(RecipeType.SMELTING) * 40L;
            case BLAZE:
                if (s.is(Items.BLAZE_ROD)) return 400_000; if (s.is(Items.BLAZE_POWDER)) return 200_000;
                if (s.is(Items.MAGMA_CREAM)) return 100_000; if (s.is(Items.FIRE_CHARGE)) return 50_000; return 0;
            case REDSTONE:
                if (s.is(Items.REDSTONE_BLOCK)) return 450_000; if (s.is(Items.REDSTONE)) return 50_000; return 0;
            case ION:
                if (s.is(Items.ENDER_EYE)) return 2_000_000; if (s.is(Items.ENDER_PEARL)) return 1_000_000;
                if (s.is(Items.POPPED_CHORUS_FRUIT)) return 300_000; if (s.is(Items.CHORUS_FRUIT)) return 150_000; return 0;
            case FISSION: return s.is(ModRegistry.ENRICHED_FUEL_CELL.get()) ? 60_000_000L : 0;
            case FUSION: return s.is(ModRegistry.DEUTERIUM_CELL.get()) ? 250_000_000L : 0;
            case ZERO_POINT: return s.is(Items.NETHER_STAR) ? -1 : 0;
        }
        return 0;
    }

    public String fuelHint() {
        return switch (this) {
            case EMBER -> "any furnace fuel"; case BLAZE -> "blaze rods/powder, magma cream";
            case REDSTONE -> "redstone"; case ION -> "ender pearls/eyes, chorus fruit";
            case FISSION -> "Enriched Fuel Cells"; case FUSION -> "Deuterium Cells";
            case ZERO_POINT -> "a Nether Star (primes forever)";
        };
    }
}
