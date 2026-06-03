package client.cn.kafei.simukraft.client.city.map;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 地图数据持久化管理器。
 * 
 * <p>存储目录结构：</p>
 * <pre>
 * simukraft_mapdata/
 *   &lt;存档标识&gt;/
 *     &lt;维度命名空间&gt;_&lt;维度路径&gt;/
 *       &lt;regionX&gt;_&lt;regionZ&gt;.smr
 * </pre>
 * 
 * <p>.smr 文件按 magic、version、height、color、flags 顺序写入。</p>
 */
public class SimuMapStorage {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAGIC = 0x534D5200;
    private static final short VERSION = 1;

    /** 根存储目录，位于 MC 游戏目录下。 */
    private static final String ROOT_DIR = "simukraft_mapdata";

    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SimuMap-Save");
        thread.setDaemon(true);
        return thread;
    });

    private static final ExecutorService LOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SimuMap-Load");
        thread.setDaemon(true);
        return thread;
    });

    private SimuMapStorage() {
    }

    /**
     * 获取当前存档的地图缓存标识。
     * 
     * @return 单人返回存档名，多人返回服务器地址标识，无法识别时返回 unknown
     */
    public static String getCurrentWorldId() {
        Minecraft mc = Minecraft.getInstance();
        var singleplayerServer = mc.getSingleplayerServer();
        if (singleplayerServer != null) {
            // 单人世界使用存档名，多人服务器使用地址，避免地图缓存互相覆盖。
            String levelId = singleplayerServer.getWorldData().getLevelName();
            return sanitize(levelId);
        }
        var currentServer = mc.getCurrentServer();
        if (currentServer != null) {
            String host = currentServer.ip;
            return "mp_" + sanitize(host);
        }
        return "unknown";
    }

    /**
     * 将维度 key 转换为合法目录名。
     * 
     * @param dimension 维度资源键
     * @return 形如 minecraft_overworld 的目录名
     */
    public static String dimensionToDir(ResourceKey<Level> dimension) {
        String ns = dimension.location().getNamespace();
        String path = dimension.location().getPath();
        return sanitize(ns + "_" + path);
    }

    /**
     * 获取指定存档和维度的 region 文件目录。
     * 
     * @param worldId 存档标识
     * @param dimension 维度资源键
     * @return region 目录路径
     */
    public static Path getRegionDir(String worldId, ResourceKey<Level> dimension) {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve(ROOT_DIR).resolve(worldId).resolve(dimensionToDir(dimension));
    }

    /**
     * 获取单个 region 文件路径。
     * 
     * @param worldId 存档标识
     * @param dimension 维度资源键
     * @param regionX region X 坐标
     * @param regionZ region Z 坐标
     * @return region 文件路径
     */
    public static Path getRegionFile(String worldId, ResourceKey<Level> dimension, int regionX, int regionZ) {
        return getRegionDir(worldId, dimension).resolve(regionX + "_" + regionZ + ".smr");
    }

    /**
     * 保存单个 region 数据到磁盘。
     * 空数据不会写入。
     * 
     * @param worldId 存档标识
     * @param dimension 维度资源键
     * @param region 待保存的 region
     */
    public static void saveRegion(String worldId, ResourceKey<Level> dimension, SimuMapRegion region) {
        SimuMapRegionData data = region.getData();
        if (data == null || data.isEmpty()) return;

        Path file = getRegionFile(worldId, dimension, region.regionX, region.regionZ);
        try {
            // .smr 是固定长度二进制文件，按 height/color/flags 三个数组顺序写入。
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(MAGIC);
                out.writeShort(VERSION);
                for (short h : data.height) {
                    out.writeShort(h);
                }
                for (int c : data.color) {
                    out.writeInt(c);
                }
                for (short f : data.flags) {
                    out.writeShort(f);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Simukraft: Failed to save map region ({}, {}) for world={} dim={}",
                    region.regionX, region.regionZ, worldId, dimensionToDir(dimension), e);
        }
    }

    /**
     * 批量保存 region 数据到磁盘。
     * 
     * @param worldId 存档标识
     * @param dimension 维度资源键
     * @param regions 待保存的 region 集合
     */
    public static void saveAll(String worldId, ResourceKey<Level> dimension,
                               Collection<SimuMapRegion> regions) {
        for (SimuMapRegion region : regions) {
            saveRegion(worldId, dimension, region);
        }
        LOGGER.debug("Simukraft: Saved {} regions for world={} dim={}",
                regions.size(), worldId, dimensionToDir(dimension));
    }

    /** 异步保存 region 数据，避免阻塞客户端主线程。 */
    public static void saveAllAsync(String worldId, ResourceKey<Level> dimension,
                                    Collection<SimuMapRegion> regions, String reason) {
        List<SimuMapRegion> regionSnapshot = new ArrayList<>(regions);
        if (regionSnapshot.isEmpty()) {
            return;
        }

        SAVE_EXECUTOR.execute(() -> {
            // 保存完成后丢弃 CPU 侧地形数据，纹理和文件仍可用于地图显示/恢复。
            saveAll(worldId, dimension, regionSnapshot);
            for (SimuMapRegion region : regionSnapshot) {
                region.discardData();
            }
            LOGGER.info("Simukraft: Async-saved {} regions for world={} dim={} reason={}",
                    regionSnapshot.size(), worldId, dimensionToDir(dimension), reason);
        });
    }

    /**
     * 从磁盘加载指定存档和维度下的全部 region 数据。
     * 格式非法的文件会被跳过并记录日志。
     * 
     * @param worldId 存档标识
     * @param dimension 维度资源键
     * @param regions 目标 Map，key 为 region 坐标编码，value 为 region
     */
    public static void loadAll(String worldId, ResourceKey<Level> dimension,
                               Map<Long, SimuMapRegion> regions) {
        Path dir = getRegionDir(worldId, dimension);
        if (!Files.isDirectory(dir)) return;

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".smr")).forEach(file -> {
                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - 4);
                String[] parts = name.split("_", 2);
                if (parts.length != 2) return;
                try {
                    int rx = Integer.parseInt(parts[0]);
                    int rz = Integer.parseInt(parts[1]);
                    SimuMapRegionData data = readRegionFile(file);
                    if (data != null) {
                        SimuMapRegion region = new SimuMapRegion(rx, rz);
                        region.setData(data);
                        data.markDirty();
                        long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                        regions.put(key, region);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warn("Simukraft: Skipping malformed region file: {}", file.getFileName());
                }
            });
        } catch (IOException e) {
            LOGGER.error("Simukraft: Failed to list region files for world={} dim={}",
                    worldId, dimensionToDir(dimension), e);
        }

        LOGGER.debug("Simukraft: Loaded {} regions from world={} dim={}",
                regions.size(), worldId, dimensionToDir(dimension));
    }

    /** 异步加载世界 region 缓存。 */
    public static void loadAllAsync(String worldId, ResourceKey<Level> dimension,
                                    Map<Long, SimuMapRegion> regions) {
        LOAD_EXECUTOR.execute(() -> loadAll(worldId, dimension, regions));
    }

    /** 异步加载到临时 Map，再由调用方决定是否合并。 */
    public static void loadAllAsync(String worldId, ResourceKey<Level> dimension,
                                    Consumer<Map<Long, SimuMapRegion>> callback) {
        LOAD_EXECUTOR.execute(() -> {
            Map<Long, SimuMapRegion> loadedRegions = new ConcurrentHashMap<>();
            loadAll(worldId, dimension, loadedRegions);
            // 回调仍在加载线程执行，调用方必须自己处理过期结果和线程安全。
            callback.accept(loadedRegions);
        });
    }

    /**
     * 从单个 .smr 文件读取 region 数据。
     * 
     * @param file 文件路径
     * @return 成功时返回填充好的 region 数据，失败时返回 null
     */
    private static SimuMapRegionData readRegionFile(Path file) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                LOGGER.warn("Simukraft: Invalid magic in {}", file.getFileName());
                return null;
            }
            short version = in.readShort();
            if (version != VERSION) {
                LOGGER.warn("Simukraft: Unsupported version {} in {}", version, file.getFileName());
                return null;
            }
            String name = file.getFileName().toString();
            name = name.substring(0, name.length() - 4);
            String[] parts = name.split("_", 2);
            int rx = Integer.parseInt(parts[0]);
            int rz = Integer.parseInt(parts[1]);

            SimuMapRegionData data = new SimuMapRegionData(rx, rz);
            for (int i = 0; i < SimuMapRegionData.AREA; i++) {
                data.height[i] = in.readShort();
            }
            for (int i = 0; i < SimuMapRegionData.AREA; i++) {
                data.color[i] = in.readInt();
            }
            for (int i = 0; i < SimuMapRegionData.AREA; i++) {
                data.flags[i] = in.readShort();
            }
            return data;
        } catch (IOException e) {
            LOGGER.error("Simukraft: Failed to read region file {}", file.getFileName(), e);
            return null;
        }
    }

    /**
     * 将字符串中不合法的文件系统字符替换为下划线。
     * 
     * @param s 原始字符串
     * @return 清理后的字符串
     */
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
