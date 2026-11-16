package colossus.client;

import colossus.client.render.SegmentRenderer;
import colossus.core.Colossus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry point: renderer and client state lifecycle. */
@Mod(value = Colossus.MODID, dist = Dist.CLIENT)
public final class ColossusClient {
    public ColossusClient(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(SegmentRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> reset());
        // respawn and dimension change: the server resends every Colossus in the new level
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.Clone e) -> reset());
    }

    private static void reset() {
        ClientColossi.clear();
        SegmentRenderer.clear();
    }
}
