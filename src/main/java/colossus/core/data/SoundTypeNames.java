package colossus.core.data;

import net.minecraft.world.level.block.SoundType;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Names for vanilla sound types, used as the third fallback in block_physics ("sound_types": {"wool": ...}).
 * Modded blocks usually reuse a vanilla SoundType, so this also gives them sensible masses.
 */
public final class SoundTypeNames {
    private static final Map<SoundType, String> NAMES = new IdentityHashMap<>();

    static {
        NAMES.put(SoundType.WOOL, "wool");
        NAMES.put(SoundType.WOOD, "wood");
        NAMES.put(SoundType.CHERRY_WOOD, "wood");
        NAMES.put(SoundType.BAMBOO_WOOD, "wood");
        NAMES.put(SoundType.NETHER_WOOD, "wood");
        NAMES.put(SoundType.STONE, "stone");
        NAMES.put(SoundType.DEEPSLATE, "stone");
        NAMES.put(SoundType.DEEPSLATE_BRICKS, "stone");
        NAMES.put(SoundType.TUFF, "stone");
        NAMES.put(SoundType.NETHERRACK, "stone");
        NAMES.put(SoundType.METAL, "metal");
        NAMES.put(SoundType.COPPER, "metal");
        NAMES.put(SoundType.ANVIL, "metal");
        NAMES.put(SoundType.CHAIN, "metal");
        NAMES.put(SoundType.NETHERITE_BLOCK, "netherite");
        NAMES.put(SoundType.GLASS, "glass");
        NAMES.put(SoundType.AMETHYST, "glass");
        NAMES.put(SoundType.GRAVEL, "gravel");
        NAMES.put(SoundType.SAND, "sand");
        NAMES.put(SoundType.SNOW, "snow");
        NAMES.put(SoundType.GRASS, "plant");
        NAMES.put(SoundType.CROP, "plant");
        NAMES.put(SoundType.AZALEA_LEAVES, "plant");
        NAMES.put(SoundType.CHERRY_LEAVES, "plant");
        NAMES.put(SoundType.SLIME_BLOCK, "slime");
        NAMES.put(SoundType.HONEY_BLOCK, "slime");
        NAMES.put(SoundType.MUD, "mud");
        NAMES.put(SoundType.PACKED_MUD, "mud");
        NAMES.put(SoundType.MUD_BRICKS, "stone");
        NAMES.put(SoundType.BONE_BLOCK, "stone");
        NAMES.put(SoundType.LODESTONE, "metal");
        NAMES.put(SoundType.ANCIENT_DEBRIS, "netherite");
    }

    public static String name(SoundType t) { return NAMES.get(t); }

    private SoundTypeNames() {}
}
