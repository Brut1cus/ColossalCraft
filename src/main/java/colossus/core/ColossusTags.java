package colossus.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Block tags the mod reads. Packs and other mods add to them. */
public final class ColossusTags {
    /** Never picked up by a Hull Core scan (natural terrain, bedrock, other mods' ship blocks). */
    public static final TagKey<Block> NON_ASSEMBLABLE = tag("non_assemblable");
    /** Feet never step here. */
    public static final TagKey<Block> UNSTEPPABLE = tag("unsteppable");
    /** Never crushed by ground pressure. */
    public static final TagKey<Block> UNCRUSHABLE = tag("uncrushable");

    private static TagKey<Block> tag(String path) { return TagKey.create(Registries.BLOCK, Colossus.id(path)); }

    private ColossusTags() {}
}
