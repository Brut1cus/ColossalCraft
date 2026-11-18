package com.colossalcraft.colossus;

import com.colossalcraft.Config;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block mass = density (kg/m^3) * collision-shape volume * massScale.
 * Datapacks can override: data/<ns>/colossal_density/<any>.json  {"values": {"minecraft:iron_block": 7870, "#minecraft:logs": 650}}
 */
public final class Density extends SimpleJsonResourceReloadListener {
    private static final Map<Block, Double> BLOCKS = new ConcurrentHashMap<>();
    private static final List<Map.Entry<TagKey<Block>, Double>> TAGS = new ArrayList<>();
    private static final Map<BlockState, double[]> CACHE = new ConcurrentHashMap<>();

    public Density() { super(new Gson(), "colossal_density"); }

    @Override protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller pf) {
        BLOCKS.clear(); TAGS.clear(); CACHE.clear();
        for (JsonElement e : map.values()) {
            JsonObject vals = e.getAsJsonObject().getAsJsonObject("values");
            if (vals == null) continue;
            for (var en : vals.entrySet()) {
                String k = en.getKey(); double d = en.getValue().getAsDouble();
                if (k.startsWith("#")) TAGS.add(Map.entry(TagKey.create(Registries.BLOCK, ResourceLocation.parse(k.substring(1))), d));
                else BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(k)).ifPresent(b -> BLOCKS.put(b, d));
            }
        }
    }

    /** kg/m^3 before scale */
    public static double density(BlockState s) {
        Double d = BLOCKS.get(s.getBlock());
        if (d != null) return d;
        for (var t : TAGS) if (s.is(t.getKey())) return t.getValue();
        Block b = s.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        if (id.getNamespace().equals(com.colossalcraft.ColossalCraft.MODID)) return id.getPath().equals("sky_sail") ? 300 : id.getPath().contains("obsidian") ? 2600 : 3000; // light alloy parts
        if (b == Blocks.IRON_BLOCK) return 7870; if (b == Blocks.GOLD_BLOCK) return 19300; if (b == Blocks.NETHERITE_BLOCK) return 25000;
        // storage blocks of precious things are heavy (a walker built of them needs much bigger joints than a stone one)
        if (b == Blocks.DIAMOND_BLOCK || b == Blocks.EMERALD_BLOCK) return 9000; if (b == Blocks.REDSTONE_BLOCK) return 8000; if (b == Blocks.LAPIS_BLOCK) return 7000; if (b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) return 2600;
        if (b == Blocks.COPPER_BLOCK) return 8960;
        if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS)) return 120;
        Double byName = byName(id.getPath());
        if (byName != null) return byName;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_STAIRS)) return 650;
        if (s.is(BlockTags.LEAVES)) return 150;
        SoundType st = s.getSoundType();
        if (st == SoundType.WOOD || st == SoundType.BAMBOO_WOOD || st == SoundType.CHERRY_WOOD || st == SoundType.NETHER_WOOD) return 650;
        if (st == SoundType.WOOL) return 120;
        if (st == SoundType.GLASS) return 2500;
        if (st == SoundType.METAL || st == SoundType.ANVIL || st == SoundType.COPPER || st == SoundType.CHAIN || st == SoundType.LANTERN) return 7800;
        if (st == SoundType.NETHERITE_BLOCK) return 25000;
        if (st == SoundType.GRASS || st == SoundType.CROP || st == SoundType.VINE || st == SoundType.AZALEA_LEAVES) return 200;
        if (st == SoundType.SAND || st == SoundType.GRAVEL) return 1600;
        if (st == SoundType.SLIME_BLOCK || st == SoundType.HONEY_BLOCK) return 1100;
        return 2500; // stone-like default
    }

    private static final String[][] NAMES = {
            {"osmium", "22590"}, {"iridium", "22560"}, {"platinum", "21450"}, {"tungsten", "19250"}, {"uranium", "19050"}, {"gold", "19300"},
            {"lead", "11340"}, {"silver", "10490"}, {"copper", "8960"}, {"brass", "8500"}, {"bronze", "8800"}, {"nickel", "8900"}, {"invar", "8100"},
            {"constantan", "8900"}, {"electrum", "14000"}, {"steel", "7850"}, {"iron", "7870"}, {"tin", "7300"}, {"zinc", "7140"}, {"titanium", "4500"},
            {"aluminum", "2700"}, {"aluminium", "2700"}, {"andesite_casing", "3000"}, {"casing", "3000"}, {"machine", "4000"}, {"frame", "2000"},
            {"concrete", "2400"}, {"brick", "1900"}, {"glass", "2500"}, {"plank", "650"}, {"log", "650"}, {"wood", "650"}, {"wool", "120"},
            {"cloth", "200"}, {"rubber", "1100"}, {"plastic", "950"}, {"obsidian", "2600"}, {"diamond", "3500"}, {"emerald", "2750"}};
    private static Double byName(String path) {
        for (String[] n : NAMES) if (path.contains(n[0])) return Double.parseDouble(n[1]);
        return null;
    }

    /** m^3 occupied by the block's collision shape (min 0.05). */
    public static double volume(BlockState s) {
        try {
            VoxelShape sh = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            double v = 0;
            for (AABB a : sh.toAabbs()) v += a.getXsize() * a.getYsize() * a.getZsize();
            return Math.max(0.05, Math.min(1.0, v));
        } catch (Exception e) { return 1.0; }
    }

    /** kg (after gameplay scale). */
    public static double mass(BlockState s) {
        double[] c = CACHE.computeIfAbsent(s, st -> new double[]{density(st) * volume(st)});
        return c[0] * Config.MASS_SCALE.get();
    }
}
