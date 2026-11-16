package colossus.compat;

import colossus.core.Colossus;
import net.neoforged.fml.ModList;

/**
 * Detects optional mods. Rule (blueprint "Independence rule"): no class outside {@code colossus.compat.*} may import
 * another mod's code, and each compat module loads only when its mod is present. Phase 1 only detects and logs;
 * the Create kinetic adapter, Sodium/Iris render paths, Jade tooltips etc. arrive as sub-packages here.
 */
public final class CompatManager {
    public static boolean create, sodium, iris, flywheel, valkyrienSkies, mekanism, ccTweaked, jade;

    public static void init() {
        ModList mods = ModList.get();
        create = mods.isLoaded("create");
        sodium = mods.isLoaded("sodium");
        iris = mods.isLoaded("iris");
        flywheel = mods.isLoaded("flywheel");
        valkyrienSkies = mods.isLoaded("valkyrienskies");
        mekanism = mods.isLoaded("mekanism");
        ccTweaked = mods.isLoaded("computercraft");
        jade = mods.isLoaded("jade");
        Colossus.LOG.info("Colossus compat: create={} sodium={} iris={} flywheel={} valkyrienskies={} mekanism={} computercraft={} jade={}",
                create, sodium, iris, flywheel, valkyrienSkies, mekanism, ccTweaked, jade);
        if (valkyrienSkies) {
            Colossus.LOG.warn("Valkyrien Skies is installed: do not assemble the same blocks with both mods (shared-block detection arrives with the compat module).");
        }
    }

    private CompatManager() {}
}
