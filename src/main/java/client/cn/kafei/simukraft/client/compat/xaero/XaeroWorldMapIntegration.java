package client.cn.kafei.simukraft.client.compat.xaero;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class XaeroWorldMapIntegration {
    private static volatile boolean registeredOnce;

    private XaeroWorldMapIntegration() {
    }

    /** registerHighlighter: 向 Xaero World Map 注册城市区块高亮器。 */
    public static void registerHighlighter(List<AbstractHighlighter> highlighters) {
        if (highlighters == null) {
            return;
        }
        if (containsSimuKraftHighlighter(highlighters)) {
            refreshCityHighlights();
            return;
        }
        highlighters.add(new SimuKraftCityHighlighter());
        if (!registeredOnce) {
            registeredOnce = true;
            SimuKraft.LOGGER.info("Simukraft: Registered city chunk highlighter for Xaero's World Map.");
        }
        refreshCityHighlights();
    }

    /** refreshCityHighlights: 城市区块同步后刷新 Xaero 已缓存的高亮区域。 */
    public static void refreshCityHighlights() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(XaeroWorldMapIntegration::refreshCityHighlights);
            return;
        }
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null || !session.isUsable()) {
                return;
            }
            MapProcessor processor = session.getMapProcessor();
            if (processor == null) {
                return;
            }
            MapWorld mapWorld = processor.getMapWorld();
            if (mapWorld == null) {
                return;
            }
            mapWorld.clearAllCachedHighlightHashes();
            refreshLoadedRegions(processor, mapWorld.getCurrentDimension());
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to refresh Xaero city chunk highlights.", exception);
        }
    }

    /** refreshLoadedRegions: 将 Xaero 已加载的叶子地图区域重新加入刷新队列。 */
    private static void refreshLoadedRegions(MapProcessor processor, MapDimension dimension) {
        if (processor == null || dimension == null) {
            return;
        }
        List<MapRegion> loadedMapRegions = new ArrayList<>();
        List<LeveledRegion<?>> loadedRegions = dimension.getLayeredMapRegions().getLoadedListUnsynced();
        synchronized (loadedRegions) {
            for (LeveledRegion<?> region : loadedRegions) {
                if (region instanceof MapRegion mapRegion) {
                    loadedMapRegions.add(mapRegion);
                }
            }
        }
        for (MapRegion mapRegion : loadedMapRegions) {
            mapRegion.requestRefresh(processor);
        }
    }

    /** containsSimuKraftHighlighter: 防止 Xaero 会话重复初始化时重复添加高亮器。 */
    private static boolean containsSimuKraftHighlighter(List<AbstractHighlighter> highlighters) {
        for (AbstractHighlighter highlighter : highlighters) {
            if (highlighter instanceof SimuKraftCityHighlighter) {
                return true;
            }
        }
        return false;
    }
}
