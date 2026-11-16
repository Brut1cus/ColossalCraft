package colossus.core;

import colossus.compat.CompatManager;
import colossus.core.data.DataLoaders;
import colossus.net.ColossusNet;
import colossus.net.StructureSync;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * ColossalCraft (mod id {@code colossus}). One self-contained jar whose only requirement is NeoForge.
 * Every other mod is optional and only touched from {@code colossus.compat.*}.
 */
@Mod(Colossus.MODID)
public final class Colossus {
    public static final String MODID = "colossus";
    public static final Logger LOG = LogUtils.getLogger();

    public Colossus(IEventBus modBus, ModContainer container) {
        ColossusRegistries.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, ColossusConfig.SPEC);
        modBus.addListener(ColossusNet::register);
        modBus.addListener((FMLCommonSetupEvent e) -> CompatManager.init());

        NeoForge.EVENT_BUS.addListener(DataLoaders::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(ColossusCommands::register);
        NeoForge.EVENT_BUS.addListener(ColossusManager::onLevelTick);
        NeoForge.EVENT_BUS.addListener(ColossusManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(StructureSync::onLogin);
        NeoForge.EVENT_BUS.addListener(StructureSync::onChangedDimension);
        NeoForge.EVENT_BUS.addListener(StructureSync::onRespawn);
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MODID, path); }
}
