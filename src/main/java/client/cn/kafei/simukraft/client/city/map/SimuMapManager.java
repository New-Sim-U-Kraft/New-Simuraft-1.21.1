package client.cn.kafei.simukraft.client.city.map;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simukraft 地图管理器。
 * 管理地图 region 的创建、扫描、渲染、持久化和生命周期。
 */
public class SimuMapManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SCAN_RADIUS = 32;

    private static SimuMapManager instance;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SimuMap-Render");
        t.setDaemon(true);
        return t;
    });

    // region 数据按 512x512 方块分片管理，只保留近期访问区域以控制显存/内存。
    private final Map<Long, SimuMapRegion> regions = new ConcurrentHashMap<>();
    // 防止同一个 region 在上一次异步渲染完成前重复提交。
    private final Set<Long> renderingKeys = ConcurrentHashMap.newKeySet();
    // 旧版机制：围绕玩家螺旋扫描附近已加载 chunk。
    private int scanRadius = 12;
    private int chunksPerTick = 4;
    private long tickCount = 0;

    private int scanCursorDX = 0;
    private int scanCursorDZ = 0;
    private int scanSpiralLeg = 1;
    private int scanSpiralStep = 0;
    private int scanSpiralDirection = 0;

    private boolean initialized = false;
    private int loadGeneration = 0;

    /** 当前地图作用域的维度 key，用于检测世界或维度切换。 */
    @Nullable
    private ResourceKey<Level> currentDimension = null;

    /** 当前存档的唯一标识，由 {@link SimuMapStorage#getCurrentWorldId()} 提供。 */
    @Nullable
    private String currentWorldId = null;

    /**
     * 活跃地图界面计数器。
     * 大于 0 时使用前台扫描预算并渲染脏 region。
     */
    private int activeConsumers = 0;

    private SimuMapManager() {
    }

    /** 注册一个活跃地图界面。 */
    public void acquireConsumer() {
        activeConsumers++;
    }

    /** 注销一个活跃地图界面。 */
    public void releaseConsumer() {
        activeConsumers = Math.max(0, activeConsumers - 1);
    }

    /** 是否存在活跃地图界面。 */
    public boolean hasActiveConsumer() {
        return activeConsumers > 0;
    }

    /** 获取地图管理器单例。 */
    public static SimuMapManager getInstance() {
        if (instance == null) {
            instance = new SimuMapManager();
        }
        return instance;
    }

    /** 检查地图系统是否已初始化。 */
    public static boolean isAvailable() {
        return instance != null && instance.initialized;
    }

    // 仅关闭已存在的地图管理器，退出存档时保存并释放当前地图缓存。
    public static void shutdownIfPresent() {
        if (instance != null) {
            instance.shutdown();
        }
    }

    /** 初始化地图系统，并从磁盘恢复当前存档和维度的历史扫描数据。 */
    public void init() {
        if (initialized) return;
        initialized = true;

        SimuBlockColors.getInstance().init();
        resetScanCursor();

        Minecraft mc = Minecraft.getInstance();
        currentWorldId = SimuMapStorage.getCurrentWorldId();
        Level level = mc.level;
        if (level != null) {
            currentDimension = level.dimension();
        } else {
            currentDimension = null;
        }

        if (currentDimension != null) {
            queueRegionLoad(currentWorldId, currentDimension);
            LOGGER.info("Simukraft: Map system initialization queued for world={} dim={}.",
                    currentWorldId, SimuMapStorage.dimensionToDir(currentDimension));
        } else {
            LOGGER.info("Simukraft: Map system initialized (dimension not yet known).");
        }
    }

    /** 关闭地图系统，保存当前数据并释放内存/纹理资源。 */
    public void shutdown() {
        if (!initialized) return;
        initialized = false;

        persistRegionsAsync(currentWorldId, currentDimension, List.copyOf(regions.values()), "shutdown");

        currentDimension = null;
        currentWorldId = null;
        loadGeneration++;

        regions.clear();
        renderingKeys.clear();
        renderExecutor.shutdownNow();

        instance = null;
        LOGGER.info("Simukraft: Map rendering system shut down.");
    }

    /** 获取或创建指定坐标的地图 region。 */
    public SimuMapRegion getOrCreateRegion(int regionX, int regionZ) {
        long key = regionKey(regionX, regionZ);
        return regions.computeIfAbsent(key, k -> new SimuMapRegion(regionX, regionZ));
    }

    /** 获取指定坐标的地图 region，可能返回 null。 */
    @Nullable
    public SimuMapRegion getRegion(int regionX, int regionZ) {
        return regions.get(regionKey(regionX, regionZ));
    }

    /** 获取当前已缓存的全部地图 region。 */
    public Collection<SimuMapRegion> getAllRegions() {
        return regions.values();
    }

    /** 客户端 tick 调用，后台缓存已加载 chunk，前台打开地图时渲染脏 region。 */
    public void tick() {
        if (!initialized) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        ResourceKey<Level> dim = level.dimension();
        String worldId = SimuMapStorage.getCurrentWorldId();
        ResourceKey<Level> previousDimension = currentDimension;
        String previousWorldId = currentWorldId;
        if (!worldId.equals(previousWorldId) || !dim.equals(previousDimension)) {
            // 进入新存档或新维度时先保存旧 region，再清空内存缓存。
            if (previousWorldId != null && previousDimension != null) {
                persistRegionsAsync(previousWorldId, previousDimension, List.copyOf(regions.values()), "world_or_dimension_change");
                LOGGER.info("Simukraft: Map scope changed from world={} dim={} to world={} dim={}, queued async save for {} regions.",
                        previousWorldId, SimuMapStorage.dimensionToDir(previousDimension), worldId, SimuMapStorage.dimensionToDir(dim), regions.size());
                regions.clear();
                renderingKeys.clear();
                resetScanCursor();
            } else if (previousDimension == null) {
                LOGGER.info("Simukraft: First dimension acquired: {}.", SimuMapStorage.dimensionToDir(dim));
            }
            currentWorldId = worldId;
            currentDimension = dim;
            queueRegionLoad(currentWorldId, currentDimension);
        }

        tickCount++;

        boolean active = activeConsumers > 0;
        int scanInterval = active ? 2 : 8;
        int scanBudget = active ? chunksPerTick : 1;
        if (tickCount % scanInterval == 0) {
            incrementalScan(scanBudget);
        }

        if (active && tickCount % 10 == 0) {
            renderDirtyRegions();
        }

        if (tickCount % 600 == 0) {
            releaseStaleRegions(60_000L);
        }
    }

    /** 强制扫描指定半径内的客户端已加载 chunk。 */
    public void forceScanArea(int centerChunkX, int centerChunkZ, int radius) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int cx = centerChunkX + dx;
                int cz = centerChunkZ + dz;

                if (!SimuChunkScanner.isChunkLoaded(level, cx, cz)) continue;

                int regionX = cx >> 5;
                int regionZ = cz >> 5;
                SimuMapRegion region = getOrCreateRegion(regionX, regionZ);

                try {
                    SimuChunkScanner.scanChunk(cx, cz, region);
                } catch (Exception e) {
                    LOGGER.debug("Simukraft: Force scan failed for ({}, {}): {}", cx, cz, e.getMessage());
                }
            }
        }
    }

    /** 强制重新渲染所有已有数据的 region。 */
    public void forceRenderAll() {
        for (SimuMapRegion region : regions.values()) {
            SimuMapRegionData data = region.getData();
            if (data != null) {
                data.markDirty();
            }
        }
        renderDirtyRegions();
    }

    private void incrementalScan(int maxChunks) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        int playerCX = player.chunkPosition().x;
        int playerCZ = player.chunkPosition().z;
        int activeScanRadius = getEffectiveScanRadius();

        int scanned = 0;
        int maxAttempts = Math.max(maxChunks * 8, 16);
        int attempts = 0;

        while (scanned < maxChunks && attempts < maxAttempts) {
            int cx = playerCX + scanCursorDX;
            int cz = playerCZ + scanCursorDZ;

            advanceSpiralCursor();
            attempts++;

            if (Math.abs(scanCursorDX) > activeScanRadius || Math.abs(scanCursorDZ) > activeScanRadius) {
                resetScanCursor();
                break;
            }

            if (!SimuChunkScanner.isChunkLoaded(level, cx, cz)) continue;

            int regionX = cx >> 5;
            int regionZ = cz >> 5;
            SimuMapRegion region = getOrCreateRegion(regionX, regionZ);

            try {
                if (SimuChunkScanner.scanChunk(cx, cz, region)) {
                    scanned++;
                }
            } catch (Exception e) {
                LOGGER.debug("Simukraft: Incremental scan failed for ({}, {})", cx, cz);
            }
        }
    }

    private void advanceSpiralCursor() {
        switch (scanSpiralDirection) {
            case 0 -> scanCursorDX++;
            case 1 -> scanCursorDZ++;
            case 2 -> scanCursorDX--;
            case 3 -> scanCursorDZ--;
        }

        scanSpiralStep++;
        if (scanSpiralStep >= scanSpiralLeg) {
            scanSpiralStep = 0;
            scanSpiralDirection = (scanSpiralDirection + 1) % 4;
            if (scanSpiralDirection == 0 || scanSpiralDirection == 2) {
                scanSpiralLeg++;
            }
        }
    }

    private void resetScanCursor() {
        scanCursorDX = 0;
        scanCursorDZ = 0;
        scanSpiralLeg = 1;
        scanSpiralStep = 0;
        scanSpiralDirection = 0;
    }

    private void renderDirtyRegions() {
        for (Map.Entry<Long, SimuMapRegion> entry : regions.entrySet()) {
            long key = entry.getKey();
            SimuMapRegion region = entry.getValue();
            SimuMapRegionData data = region.getData();
            if (data != null && data.isDirty() && renderingKeys.add(key)) {
                // 地图纹理生成放到单线程队列，避免多线程同时操作同一 region 数据。
                renderExecutor.execute(() -> {
                    try {
                        SimuMapRenderer.renderRegion(region);
                    } catch (Exception e) {
                        LOGGER.error("Simukraft: Failed to render region {}", region, e);
                    } finally {
                        renderingKeys.remove(key);
                    }
                });
            }
        }
    }

    private void releaseStaleRegions(long maxAge) {
        long now = System.currentTimeMillis();
        regions.entrySet().removeIf(entry -> {
            SimuMapRegion region = entry.getValue();
            if (now - region.getLastAccessTime() > maxAge) {
                SimuMapRegionData data = region.getData();
                if (data == null && !region.isImageLoaded()) {
                    return true;
                }
                if (region.isImageLoaded()) {
                    // 只释放 GPU 纹理，CPU 侧地形数据继续用于渲染未加载 chunk。
                    region.releaseTexture();
                    if (data != null) {
                        data.markDirty();
                    }
                }
            }
            return false;
        });
    }

    private void persistRegionsAsync(@Nullable String worldId, @Nullable ResourceKey<Level> dimension,
                                     List<SimuMapRegion> regionSnapshot, String reason) {
        if (regionSnapshot.isEmpty()) {
            return;
        }

        for (SimuMapRegion region : regionSnapshot) {
            region.releaseTexture();
        }

        if (worldId != null && dimension != null) {
            SimuMapStorage.saveAllAsync(worldId, dimension, regionSnapshot, reason);
            return;
        }

        for (SimuMapRegion region : regionSnapshot) {
            region.discardData();
        }
    }

    private void queueRegionLoad(String worldId, ResourceKey<Level> dimension) {
        int currentLoadGeneration = ++loadGeneration;
        SimuMapStorage.loadAllAsync(worldId, dimension, loadedRegions -> {
            // 异步加载返回时可能已经切换维度，用 generation 丢弃过期回调。
            if (!initialized || currentLoadGeneration != loadGeneration) {
                return;
            }
            if (currentDimension == null || !currentDimension.equals(dimension)) {
                return;
            }
            regions.putAll(loadedRegions);
            LOGGER.info("Simukraft: Async-loaded {} regions for world={} dim={}.",
                    loadedRegions.size(), worldId, SimuMapStorage.dimensionToDir(dimension));
        });
    }

    /** 设置后台扫描半径，单位为 chunk。 */
    public void setScanRadius(int radius) {
        this.scanRadius = Math.max(1, Math.min(radius, MAX_SCAN_RADIUS));
    }

    /** 获取后台扫描半径，单位为 chunk。 */
    public int getScanRadius() {
        return scanRadius;
    }

    /** 获取当前实际扫描半径，至少覆盖客户端视距内的已加载 chunk。 */
    public int getEffectiveScanRadius() {
        Minecraft mc = Minecraft.getInstance();
        int clientRadius = mc.options == null ? scanRadius : mc.options.getEffectiveRenderDistance() + 1;
        return Math.max(scanRadius, Math.min(clientRadius, MAX_SCAN_RADIUS));
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }
}
