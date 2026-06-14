package common.cn.kafei.simukraft.commercial;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CommercialStockManager extends SavedData {
    private static final String DATA_NAME = SimuKraft.MOD_ID + "_commercial_stock";
    private static final Factory<CommercialStockManager> FACTORY = new Factory<>(CommercialStockManager::new, CommercialStockManager::load, null);

    private final ConcurrentMap<BlockPos, ConcurrentMap<String, CommercialStockData>> stock = new ConcurrentHashMap<>();
    private volatile boolean sqliteLoaded;
    private volatile ServerLevel level;

    /** get: 获取当前维度的商业库存管理器。 */
    public static CommercialStockManager get(ServerLevel level) {
        CommercialStockManager manager = level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        manager.level = level;
        manager.loadFromSqlite(level);
        return manager;
    }

    private static CommercialStockManager load(CompoundTag tag, HolderLookup.Provider registries) {
        CommercialStockManager manager = new CommercialStockManager();
        ListTag list = tag.getList("Stock", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CommercialStockData data = CommercialStockData.fromTag(list.getCompound(i));
            manager.stock.computeIfAbsent(data.boxPos(), ignored -> new ConcurrentHashMap<>()).put(data.itemId(), data);
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        stock.values().forEach(map -> map.values().forEach(data -> list.add(data.toTag())));
        tag.put("Stock", list);
        return tag;
    }

    /** saveToSqlite: 将商业库存写入 SQLite。 */
    public synchronized void saveToSqlite(ServerLevel level) {
        if (level != null) {
            SimuSqliteStorage.saveCommercialStock(level, save(new CompoundTag(), level.registryAccess()));
        }
    }

    public synchronized void reloadFromSqlite(ServerLevel level) {
        stock.clear();
        sqliteLoaded = false;
        loadFromSqlite(level);
    }

    private synchronized void loadFromSqlite(ServerLevel level) {
        if (sqliteLoaded) {
            return;
        }
        sqliteLoaded = true;
        CompoundTag sqliteTag = SimuSqliteStorage.loadCommercialStock(level);
        if (sqliteTag == null || sqliteTag.isEmpty()) {
            return;
        }
        CommercialStockManager loaded = load(sqliteTag, level.registryAccess());
        stock.clear();
        stock.putAll(loaded.stock);
    }

    /** get: 获取指定库存条目。 */
    public CommercialStockData get(BlockPos boxPos, String itemId) {
        if (boxPos == null || itemId == null) {
            return null;
        }
        Map<String, CommercialStockData> map = stock.get(boxPos.immutable());
        return map != null ? map.get(itemId) : null;
    }

    /** getOrCreate: 获取或创建指定库存条目。 */
    public CommercialStockData getOrCreate(BlockPos boxPos, CommercialOffer.StockRule rule, long gameTime) {
        return stock.computeIfAbsent(boxPos.immutable(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(rule.itemId(), ignored -> new CommercialStockData(boxPos, rule.itemId(), rule.initial(), rule.max(), gameTime));
    }

    /** persistBatch: 批量持久化多个库存条目（单次事务写入）。 */
    public void persistBatch(List<CommercialStockData> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<net.minecraft.nbt.CompoundTag> tags = new ArrayList<>(entries.size());
        for (CommercialStockData data : entries) {
            data.touch();
            stock.computeIfAbsent(data.boxPos(), ignored -> new ConcurrentHashMap<>()).put(data.itemId(), data);
            tags.add(data.toTag());
        }
        setDirty();
        if (level != null) {
            common.cn.kafei.simukraft.storage.SimuSqliteStorage.saveCommercialStockEntries(level, tags);
        }
    }

    /** persist: 持久化单个库存条目。 */
    public void persist(CommercialStockData data) {
        if (data == null) {
            return;
        }
        data.touch();
        stock.computeIfAbsent(data.boxPos(), ignored -> new ConcurrentHashMap<>()).put(data.itemId(), data);
        setDirty();
        if (level != null) {
            SimuSqliteStorage.saveCommercialStockEntry(level, data.toTag());
        }
    }

    /** removeBox: 删除指定商业箱的所有库存。 */
    public void removeBox(BlockPos boxPos) {
        if (boxPos == null) {
            return;
        }
        BlockPos key = boxPos.immutable();
        if (stock.remove(key) != null) {
            setDirty();
            if (level != null) {
                SimuSqliteStorage.deleteCommercialStockAtBox(level, key.asLong());
            }
        }
    }

    /** allAt: 返回指定商业箱的库存快照。 */
    public Map<String, CommercialStockData> allAt(BlockPos boxPos) {
        Map<String, CommercialStockData> map = boxPos != null ? stock.get(boxPos.immutable()) : null;
        return map != null ? Map.copyOf(map) : Map.of();
    }

    /** all: 返回全部库存条目快照。 */
    public List<CommercialStockData> all() {
        return stock.values().stream().flatMap(map -> map.values().stream()).toList();
    }
}
