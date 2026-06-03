package client.cn.kafei.simukraft.client.city;

import client.cn.kafei.simukraft.client.city.map.SimuChunkScanner;
import client.cn.kafei.simukraft.client.city.map.SimuMapStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.MapColor;

import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public final class ClientCityMapTerrainCache {
    private static final ClientCityMapTerrainCache INSTANCE = new ClientCityMapTerrainCache();
    private static final int MAX_CACHED_COLUMNS = 262144;
    private static final int MAX_CACHED_SAMPLES = 131072;
    private static final int MAX_CHUNKS_SCANNED_PER_FRAME = 3;
    private final Map<Long, TerrainColumn> columns = new ConcurrentHashMap<>();
    private final Map<Long, Integer> sampledColors = new ConcurrentHashMap<>();
    private final PriorityQueue<PendingChunk> pendingChunks = new PriorityQueue<>(Comparator.comparingInt(PendingChunk::priority));
    private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    // 客户端地形采样缓存必须按存档和维度隔离，避免切换世界后地图颜色串档。
    private volatile String currentScope = "";

    private ClientCityMapTerrainCache() {
    }

    public static ClientCityMapTerrainCache getInstance() {
        return INSTANCE;
    }

    public int getColor(int worldX, int worldZ) {
        ensureCurrentScope();
        TerrainColumn cached = getCachedColumn(worldX, worldZ);
        return cached == null ? 0x00000000 : cached.color();
    }

    public void queueVisibleChunks(int startChunkX, int endChunkX, int startChunkZ, int endChunkZ) {
        ensureCurrentScope();
        int centerChunkX = (startChunkX + endChunkX) / 2;
        int centerChunkZ = (startChunkZ + endChunkZ) / 2;
        for (int chunkZ = startChunkZ; chunkZ <= endChunkZ; chunkZ++) {
            for (int chunkX = startChunkX; chunkX <= endChunkX; chunkX++) {
                queueChunk(chunkX, chunkZ, Math.abs(chunkX - centerChunkX) + Math.abs(chunkZ - centerChunkZ));
            }
        }
    }

    public void processScanBudget() {
        ensureCurrentScope();
        int scanned = 0;
        while (scanned < MAX_CHUNKS_SCANNED_PER_FRAME) {
            PendingChunk pendingChunk;
            synchronized (pendingChunks) {
                pendingChunk = pendingChunks.poll();
            }
            if (pendingChunk == null) {
                return;
            }
            long chunkLong = pendingChunk.chunkLong();
            queuedChunks.remove(chunkLong);
            if (scannedChunks.contains(chunkLong)) {
                continue;
            }
            int chunkX = ChunkPos.getX(chunkLong);
            int chunkZ = ChunkPos.getZ(chunkLong);
            if (scanChunkInternal(chunkX, chunkZ)) {
                // 每帧最多扫描少量 chunk，地图逐步补全但不会明显拖慢 GUI。
                scannedChunks.add(chunkLong);
                scanned++;
            }
        }
    }

    private void queueChunk(int chunkX, int chunkZ, int priority) {
        long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
        if (scannedChunks.contains(chunkLong) || !queuedChunks.add(chunkLong)) {
            return;
        }
        synchronized (pendingChunks) {
            pendingChunks.offer(new PendingChunk(chunkLong, priority));
        }
    }

    public void scanChunk(int chunkX, int chunkZ) {
        ensureCurrentScope();
        scanChunkInternal(chunkX, chunkZ);
    }

    private boolean scanChunkInternal(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        ChunkAccess chunk = level == null ? null : SimuChunkScanner.getLoadedChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                scanAndCacheColumn(baseX + localX, baseZ + localZ);
            }
        }
        return true;
    }

    public synchronized void clear() {
        clearInternal();
        currentScope = "";
    }

    // 清理当前活动地图地形缓存，不修改作用域标识。
    private void clearInternal() {
        columns.clear();
        sampledColors.clear();
        synchronized (pendingChunks) {
            pendingChunks.clear();
        }
        queuedChunks.clear();
        scannedChunks.clear();
    }

    private TerrainColumn scanAndCacheColumn(int worldX, int worldZ) {
        long key = BlockPos.asLong(worldX, 0, worldZ);
        TerrainColumn cached = columns.get(key);
        if (cached != null) {
            return cached;
        }
        TerrainColumn scanned = scanColumn(worldX, worldZ);
        if (scanned != null) {
            if (columns.size() > MAX_CACHED_COLUMNS) {
                columns.clear();
                sampledColors.clear();
                scannedChunks.clear();
            }
            columns.put(key, scanned);
        }
        return scanned;
    }

    private TerrainColumn scanColumn(int worldX, int worldZ) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return null;
        }
        int chunkX = SectionCoordinate.blockToSectionCoord(worldX);
        int chunkZ = SectionCoordinate.blockToSectionCoord(worldZ);
        ChunkAccess chunk = SimuChunkScanner.getLoadedChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        int topY = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, localX, localZ);
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, topY, worldZ);
        BlockState state = level.getBlockState(pos);
        int y = topY;
        // 跳过花草等非地表主体方块，地图颜色更接近实际地面。
        while ((state.isAir() || shouldSkipSurfaceBlock(state)) && y > minY) {
            y--;
            pos.setY(y);
            state = level.getBlockState(pos);
        }
        int color = getBlockColor(state, level, pos);
        return new TerrainColumn((short) y, color);
    }

    private boolean shouldSkipSurfaceBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TallGrassBlock
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.DANDELION)
                || state.is(Blocks.POPPY)
                || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.ALLIUM)
                || state.is(Blocks.AZURE_BLUET)
                || state.is(Blocks.RED_TULIP)
                || state.is(Blocks.ORANGE_TULIP)
                || state.is(Blocks.WHITE_TULIP)
                || state.is(Blocks.PINK_TULIP)
                || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.CORNFLOWER)
                || state.is(Blocks.LILY_OF_THE_VALLEY)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.TORCHFLOWER)
                || state.is(Blocks.PITCHER_PLANT)
                || state.is(Blocks.SUNFLOWER)
                || state.is(Blocks.LILAC)
                || state.is(Blocks.ROSE_BUSH)
                || state.is(Blocks.PEONY);
    }

    private int getBlockColor(BlockState state, Level level, BlockPos pos) {
        Block block = state.getBlock();
        if (state.isAir()) {
            return 0x00000000;
        }
        if (block == Blocks.WATER || block instanceof LiquidBlock) {
            return 0xFF3F76E4;
        }
        if (block instanceof GrassBlock) {
            return 0xFF58A84A;
        }
        if (state.is(BlockTags.LEAVES)) {
            return 0xFF3F8F3A;
        }
        try {
            BlockColors blockColors = Minecraft.getInstance().getBlockColors();
            int tintColor = blockColors.getColor(state, (BlockAndTintGetter) level, pos, 0);
            if (tintColor != -1 && tintColor != 0) {
                return 0xFF000000 | tintColor;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            MapColor mapColor = state.getMapColor(level, pos);
            if (mapColor != MapColor.NONE) {
                return 0xFF000000 | mapColor.col;
            }
        } catch (RuntimeException ignored) {
        }
        return 0xFF7F7F7F;
    }

    public int shadedColor(int worldX, int worldZ) {
        int center = getColor(worldX, worldZ);
        if ((center >>> 24) == 0) {
            return 0xFF1F1F1F;
        }
        int west = getColor(worldX - 1, worldZ);
        int north = getColor(worldX, worldZ - 1);
        int brightness = 0;
        if ((west >>> 24) != 0 && west != center) {
            brightness += 4;
        }
        if ((north >>> 24) != 0 && north != center) {
            brightness -= 4;
        }
        return adjustBrightness(center, brightness);
    }

    public int sampledMapColor(int worldX, int worldZ, int sampleSize) {
        ensureCurrentScope();
        long sampleKey = sampleKey(worldX, worldZ, sampleSize);
        Integer cachedSample = sampledColors.get(sampleKey);
        if (cachedSample != null) {
            return cachedSample;
        }
        int color = computeSampledMapColor(worldX, worldZ, sampleSize);
        if (sampledColors.size() > MAX_CACHED_SAMPLES) {
            sampledColors.clear();
        }
        sampledColors.put(sampleKey, color);
        return color;
    }

    private int computeSampledMapColor(int worldX, int worldZ, int sampleSize) {
        if (sampleSize <= 1) {
            return shadedColor(worldX, worldZ);
        }
        // 缩小时抽样混色，不逐像素扫描，控制城市地图大范围显示成本。
        int samples = 0;
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        int step = Math.max(1, sampleSize / 2);
        for (int z = 0; z < sampleSize; z += step) {
            for (int x = 0; x < sampleSize; x += step) {
                TerrainColumn column = getCachedColumn(worldX + x, worldZ + z);
                if (column == null) {
                    continue;
                }
                int color = column.color();
                if ((color >>> 24) == 0) {
                    continue;
                }
                alpha += (color >>> 24) & 0xFF;
                red += (color >>> 16) & 0xFF;
                green += (color >>> 8) & 0xFF;
                blue += color & 0xFF;
                samples++;
            }
        }
        if (samples == 0) {
            return 0xFF1F1F1F;
        }
        int color = ((alpha / samples) << 24) | ((red / samples) << 16) | ((green / samples) << 8) | (blue / samples);
        return adjustBrightness(color, terrainShade(worldX, worldZ, sampleSize));
    }

    private long sampleKey(int worldX, int worldZ, int sampleSize) {
        int sampleX = Math.floorDiv(worldX, sampleSize);
        int sampleZ = Math.floorDiv(worldZ, sampleSize);
        long posKey = (((long) sampleX) << 32) ^ (sampleZ & 0xffffffffL);
        return posKey ^ (((long) sampleSize) << 56);
    }

    private int terrainShade(int worldX, int worldZ, int sampleSize) {
        TerrainColumn center = getCachedColumn(worldX, worldZ);
        TerrainColumn north = getCachedColumn(worldX, worldZ - sampleSize);
        TerrainColumn west = getCachedColumn(worldX - sampleSize, worldZ);
        if (center == null || north == null || west == null) {
            return 0;
        }
        int brightness = 0;
        if (center.height() > north.height() || center.height() > west.height()) {
            brightness += 6;
        }
        if (center.height() < north.height() || center.height() < west.height()) {
            brightness -= 6;
        }
        return brightness;
    }

    private TerrainColumn getCachedColumn(int worldX, int worldZ) {
        return columns.get(BlockPos.asLong(worldX, 0, worldZ));
    }

    // 检查当前存档/维度是否变化，变化时丢弃旧地形缓存。
    private void ensureCurrentScope() {
        String scope = currentScope();
        if (scope.equals(currentScope)) {
            return;
        }
        synchronized (this) {
            if (!scope.equals(currentScope)) {
                clearInternal();
                currentScope = scope;
            }
        }
    }

    // 生成客户端当前存档加维度作用域。
    private String currentScope() {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        String dimensionId = level == null ? "unknown_dimension" : dimensionToId(level.dimension());
        return SimuMapStorage.getCurrentWorldId() + "|" + dimensionId;
    }

    // 将维度键转换为稳定字符串，参与客户端缓存隔离。
    private static String dimensionToId(ResourceKey<Level> dimension) {
        return dimension.location().getNamespace() + ":" + dimension.location().getPath();
    }

    private static int adjustBrightness(int argb, int delta) {
        int a = argb & 0xFF000000;
        int r = Mth.clamp(((argb >> 16) & 0xFF) + delta, 0, 255);
        int g = Mth.clamp(((argb >> 8) & 0xFF) + delta, 0, 255);
        int b = Mth.clamp((argb & 0xFF) + delta, 0, 255);
        return a | (r << 16) | (g << 8) | b;
    }

    private record TerrainColumn(short height, int color) {
    }

    private record PendingChunk(long chunkLong, int priority) {
    }

    private static final class SectionCoordinate {
        private static int blockToSectionCoord(int blockCoord) {
            return Math.floorDiv(blockCoord, 16);
        }
    }
}
