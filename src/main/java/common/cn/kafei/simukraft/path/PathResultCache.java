package common.cn.kafei.simukraft.path;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PathResultCache {
    private static final int MAX_ENTRIES = 512;
    private final ConcurrentMap<PathCacheKey, CacheEntry> entries = new ConcurrentHashMap<>();

    PathResult get(PathCacheKey key, long gameTime) {
        CacheEntry entry = entries.get(key);
        if (entry == null || entry.expiresAt < gameTime) {
            if (entry != null) {
                entries.remove(key, entry);
            }
            return null;
        }
        return entry.result;
    }

    void put(PathCacheKey key, PathResult result, long gameTime, int ttlTicks) {
        if (key == null || result == null || !result.success() || ttlTicks <= 0) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            trim(gameTime);
        }
        entries.put(key, new CacheEntry(result, gameTime + ttlTicks));
    }

    void clear() {
        entries.clear();
    }

    void cleanup(long gameTime) {
        for (Iterator<Map.Entry<PathCacheKey, CacheEntry>> iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<PathCacheKey, CacheEntry> entry = iterator.next();
            if (entry.getValue().expiresAt < gameTime) {
                iterator.remove();
            }
        }
    }

    private void trim(long gameTime) {
        cleanup(gameTime);
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        int removed = 0;
        for (Iterator<PathCacheKey> iterator = entries.keySet().iterator(); iterator.hasNext() && removed < 64;) {
            iterator.next();
            iterator.remove();
            removed++;
        }
    }

    private record CacheEntry(PathResult result, long expiresAt) {
    }
}
