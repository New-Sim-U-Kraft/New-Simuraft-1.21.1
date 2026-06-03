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
import client.cn.kafei.simukraft.client.fluid.ClientFluidExtensions;
import client.cn.kafei.simukraft.client.input.SimuKraftKeyMappings;
import client.cn.kafei.simukraft.client.path.NpcPathDebugRenderer;
import client.cn.kafei.simukraft.client.renderer.CitizenRenderer;
import client.cn.kafei.simukraft.client.selection.TwoPointSelectionManager;
import client.cn.kafei.simukraft.client.selection.TwoPointSelectionRenderer;
import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.registry.ModEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@SuppressWarnings("null")
@EventBusSubscriber(modid = SimuKraft.MOD_ID)
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void registerModBusEvents(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterRenderers);
        modEventBus.addListener(ClientFluidExtensions::register);
        modEventBus.addListener(SimuKraftKeyMappings::register);
        NeoForge.EVENT_BUS.addListener(BuildingBoundsRenderer::onRender);
        NeoForge.EVENT_BUS.addListener(TwoPointSelectionRenderer::onRender);
        NeoForge.EVENT_BUS.addListener(NpcPathDebugRenderer::onRender);
        // 注册 NeoForge 内置配置屏，让模组列表里"模拟大都市"的配置按钮能打开 GUI 配置页（含规划师计费等）。
        ModLoadingContext.get().getActiveContainer().registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        ClientHUDOverlay.render(event);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SimuMapManager.shutdownIfPresent();
        SimuMapManager.getInstance().init();
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
        TwoPointSelectionManager.clear();
        NpcPathDebugRenderer.clear();
        client.cn.kafei.simukraft.client.farmland.FarmlandHoverPreview.clear();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (SimuMapManager.isAvailable()) {
            SimuMapManager.getInstance().tick();
        }
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.CITIZEN.get(), CitizenRenderer::new);
    }
}
