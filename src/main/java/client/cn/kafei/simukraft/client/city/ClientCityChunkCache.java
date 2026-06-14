package client.cn.kafei.simukraft.client.city;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import client.cn.kafei.simukraft.client.city.map.SimuMapStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class ClientCityChunkCache {
    private static final ClientCityChunkCache INSTANCE = new ClientCityChunkCache();
    private final Map<CacheScope, ScopeCache> scopedCaches = new ConcurrentHashMap<>();

    private ClientCityChunkCache() {
    }

    public static ClientCityChunkCache getInstance() {
        return INSTANCE;
    }

    public synchronized void updateCurrentCity(UUID cityId, Set<Long> chunks, BlockPos corePos, String cityName) {
        ScopeCache cache = currentScopeCache();
        cache.currentCityId = cityId;
        cache.allCityChunks.put(cityId, Set.copyOf(chunks));
        cache.allCityCores.put(cityId, new CityCoreEntry(corePos.immutable(), cityName));
        cache.rebuildChunkOwnerIndex();
    }

    public synchronized void updateAllCityChunks(UUID cityId, Map<UUID, Set<Long>> chunks) {
        ScopeCache cache = currentScopeCache();
        cache.currentCityId = cityId;
        cache.allCityChunks.clear();
        chunks.forEach((id, cityChunks) -> cache.allCityChunks.put(id, Set.copyOf(cityChunks)));
        cache.rebuildChunkOwnerIndex();
    }

    public synchronized void updateAllCityCores(Map<UUID, CityCoreEntry> cores) {
        ScopeCache cache = currentScopeCache();
        cache.allCityCores.clear();
        cache.allCityCores.putAll(cores);
    }

    public Set<Long> getCurrentCityChunks() {
        ScopeCache cache = currentScopeCache();
        UUID cityId = cache.currentCityId;
        if (cityId == null) {
            return Collections.emptySet();
        }
        return cache.allCityChunks.getOrDefault(cityId, Collections.emptySet());
    }

    public Map<UUID, Set<Long>> getAllCityChunks() {
        return snapshotChunks(currentScopeCache().allCityChunks);
    }

    public Map<UUID, CityCoreEntry> getAllCityCores() {
        return Map.copyOf(currentScopeCache().allCityCores);
    }

    public CityCoreEntry getCityCore(UUID cityId) {
        return cityId != null ? currentScopeCache().allCityCores.get(cityId) : null;
    }

    public UUID getCurrentCityId() {
        return currentScopeCache().currentCityId;
    }

    public boolean isChunkInCurrentCity(long chunkLong) {
        ScopeCache cache = currentScopeCache();
        UUID cityId = cache.currentCityId;
        return cityId != null && cache.allCityChunks.getOrDefault(cityId, Collections.emptySet()).contains(chunkLong);
    }

    public boolean isChunkOwned(long chunkLong) {
        return currentScopeCache().chunkOwnerIndex.containsKey(chunkLong);
    }

    public UUID getChunkOwner(long chunkLong) {
        return currentScopeCache().chunkOwnerIndex.get(chunkLong);
    }

    public int getDataVersion() {
        return currentScopeCache().dataVersion;
    }

    public boolean regionHasOwnedChunks(int regionX, int regionZ) {
        long key = (long) regionX << 32 | Integer.toUnsignedLong(regionZ);
        return currentScopeCache().regionWithChunks.contains(key);
    }

    public synchronized void clear() {
        scopedCaches.remove(currentScope());
    }

    public synchronized void clearAllWorlds() {
        scopedCaches.clear();
    }

    private ScopeCache currentScopeCache() {
        return scopedCaches.computeIfAbsent(currentScope(), scope -> new ScopeCache());
    }

    private CacheScope currentScope() {
        Minecraft minecraft = Minecraft.getInstance();
        String worldId = SimuMapStorage.getCurrentWorldId();
        Level level = minecraft.level;
        String dimensionId = level == null ? "unknown" : dimensionToId(level.dimension());
        return new CacheScope(worldId, dimensionId);
    }

    private static String dimensionToId(ResourceKey<Level> dimension) {
        return dimension.location().getNamespace() + ":" + dimension.location().getPath();
    }

    private static Map<UUID, Set<Long>> snapshotChunks(Map<UUID, Set<Long>> chunks) {
        Map<UUID, Set<Long>> snapshot = new HashMap<>();
        chunks.forEach((cityId, cityChunks) -> snapshot.put(cityId, Set.copyOf(new HashSet<>(cityChunks))));
        return Map.copyOf(snapshot);
    }

    private record CacheScope(String worldId, String dimensionId) {
    }

    private static final class ScopeCache {
        private final Map<UUID, Set<Long>> allCityChunks = new ConcurrentHashMap<>();
        private final Map<Long, UUID> chunkOwnerIndex = new ConcurrentHashMap<>();
        private final Map<UUID, CityCoreEntry> allCityCores = new ConcurrentHashMap<>();
        private volatile UUID currentCityId;
        private volatile int dataVersion;
        private volatile Set<Long> regionWithChunks = Set.of();

        private void rebuildChunkOwnerIndex() {
            chunkOwnerIndex.clear();
            allCityChunks.forEach((cityId, chunks) -> chunks.forEach(chunk -> chunkOwnerIndex.put(chunk, cityId)));
            Set<Long> regions = new HashSet<>();
            chunkOwnerIndex.keySet().forEach(chunk -> regions.add(
                    (long)(ChunkPos.getX(chunk) >> 5) << 32 | Integer.toUnsignedLong(ChunkPos.getZ(chunk) >> 5)));
            regionWithChunks = Set.copyOf(regions);
            dataVersion++;
        }
    }

    public record CityCoreEntry(BlockPos pos, String cityName) {
    }
}
