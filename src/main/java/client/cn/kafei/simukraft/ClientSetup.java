package client.cn.kafei.simukraft;

import client.cn.kafei.simukraft.client.ClientHUDOverlay;
import client.cn.kafei.simukraft.client.ClientSimukraftData;
import client.cn.kafei.simukraft.client.buildbox.BuildingCacheService;
import client.cn.kafei.simukraft.client.buildbox.BuildingBoundsRenderer;
import client.cn.kafei.simukraft.client.buildbox.BuildingPreviewManager;
import client.cn.kafei.simukraft.client.city.ClientCityChunkCache;
import client.cn.kafei.simukraft.client.city.ClientCityMapTerrainCache;
import client.cn.kafei.simukraft.client.city.map.SimuMapManager;
import client.cn.kafei.simukraft.client.freecamera.FreeCameraManager;
import client.cn.kafei.simukraft.client.path.NpcPathDebugRenderer;
import client.cn.kafei.simukraft.client.renderer.CitizenRenderer;
import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.registry.ModEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

@SuppressWarnings("null")
@EventBusSubscriber(modid = SimuKraft.MOD_ID)
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void registerModBusEvents(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterRenderers);
        NeoForge.EVENT_BUS.addListener(BuildingBoundsRenderer::onRender);
        NeoForge.EVENT_BUS.addListener(NpcPathDebugRenderer::onRender);
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        ClientHUDOverlay.render(event);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BuildingCacheService.ensureInitialized();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BuildingPreviewManager.clearPreview();
        BuildingBoundsRenderer.clearAll();
        ClientCityChunkCache.getInstance().clearAllWorlds();
        ClientCityMapTerrainCache.getInstance().clear();
        ClientSimukraftData.resetAllClientState();
        ClientHUDOverlay.resetCache();
        SimuMapManager.shutdownIfPresent();
        FreeCameraManager.deactivate();
        NpcPathDebugRenderer.clear();
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.CITIZEN.get(), CitizenRenderer::new);
    }
}
