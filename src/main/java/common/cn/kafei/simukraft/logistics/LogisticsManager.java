package common.cn.kafei.simukraft.logistics;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class LogisticsManager extends SavedData {
    private static final String DATA_NAME = SimuKraft.MOD_ID + "_logistics";
    private static final Factory<LogisticsManager> FACTORY = new Factory<>(LogisticsManager::new, LogisticsManager::load, null);

    private final ConcurrentMap<UUID, LogisticsWarehouseData> warehouses = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, UUID> warehouseByPos = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> warehousesByCity = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, LogisticsClientData> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, UUID> clientByPos = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> clientsByCity = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, LogisticsChannelData> channels = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> channelsByWarehouse = new ConcurrentHashMap<>();
    private volatile long channelVersion;
    private volatile boolean sqliteLoaded;
    private volatile ServerLevel level;

    public static LogisticsManager get(ServerLevel level) {
        LogisticsManager manager = level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        manager.level = level;
        manager.loadFromSqlite(level);
        return manager;
    }

    private static LogisticsManager load(CompoundTag tag, HolderLookup.Provider registries) {
        LogisticsManager manager = new LogisticsManager();
        ListTag warehouseTags = tag.getList("Warehouses", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < warehouseTags.size(); i++) {
            manager.putLoadedWarehouse(LogisticsWarehouseData.fromTag(warehouseTags.getCompound(i)));
        }
        ListTag clientTags = tag.getList("Clients", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < clientTags.size(); i++) {
            manager.putLoadedClient(LogisticsClientData.fromTag(clientTags.getCompound(i)));
        }
        ListTag channelTags = tag.getList("Channels", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < channelTags.size(); i++) {
            manager.putLoadedChannel(LogisticsChannelData.fromTag(channelTags.getCompound(i)));
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag warehouseTags = new ListTag();
        warehouses.values().stream()
                .sorted(Comparator.comparing(data -> data.boxPos().asLong()))
                .forEach(data -> warehouseTags.add(data.toTag()));
        ListTag clientTags = new ListTag();
        clients.values().stream()
                .filter(client -> !client.automatic())
                .sorted(Comparator.comparing(data -> data.boxPos().asLong()))
                .forEach(data -> clientTags.add(data.toTag()));
        ListTag channelTags = new ListTag();
        channels.values().stream()
                .sorted(Comparator.comparing(LogisticsChannelData::updatedAt))
                .forEach(data -> channelTags.add(data.toTag()));
        tag.put("Warehouses", warehouseTags);
        tag.put("Clients", clientTags);
        tag.put("Channels", channelTags);
        return tag;
    }

    public synchronized void saveToSqlite(ServerLevel level) {
        if (level != null) {
            SimuSqliteStorage.saveLogistics(level, save(new CompoundTag(), level.registryAccess()));
        }
    }

    public synchronized void reloadFromSqlite(ServerLevel level) {
        clearIndexes();
        sqliteLoaded = false;
        loadFromSqlite(level);
    }

    private synchronized void loadFromSqlite(ServerLevel level) {
        if (sqliteLoaded) {
            return;
        }
        sqliteLoaded = true;
        CompoundTag sqliteTag = SimuSqliteStorage.loadLogistics(level);
        if (sqliteTag == null || sqliteTag.isEmpty()) {
            return;
        }
        LogisticsManager loaded = load(sqliteTag, level.registryAccess());
        clearIndexes();
        loaded.warehouses.values().forEach(this::putLoadedWarehouse);
        loaded.clients.values().forEach(this::putLoadedClient);
        loaded.channels.values().forEach(this::putLoadedChannel);
    }

    public synchronized LogisticsWarehouseData getOrCreateWarehouse(BlockPos boxPos, UUID cityId, String dimensionId, long gameTime) {
        UUID existingId = warehouseByPos.get(boxPos.immutable());
        if (existingId != null && warehouses.containsKey(existingId)) {
            return warehouses.get(existingId);
        }
        LogisticsWarehouseData data = new LogisticsWarehouseData(UUID.randomUUID(), boxPos, cityId, dimensionId, List.of(), gameTime);
        putLoadedWarehouse(data);
        persistWarehouse(data);
        setDirty();
        return data;
    }

    public synchronized LogisticsClientData getOrCreateClient(BlockPos boxPos, UUID cityId, String dimensionId, long gameTime) {
        UUID existingId = clientByPos.get(boxPos.immutable());
        if (existingId != null && clients.containsKey(existingId)) {
            return clients.get(existingId);
        }
        LogisticsClientData data = new LogisticsClientData(UUID.randomUUID(), boxPos, cityId, dimensionId, "", false,
                LogisticsConstants.MANUAL_CLIENT_SOURCE_TYPE, boxPos.toShortString(), List.of(), gameTime);
        putLoadedClient(data);
        persistClient(data);
        setDirty();
        return data;
    }

    public LogisticsWarehouseData warehouseAt(BlockPos boxPos) {
        UUID id = boxPos != null ? warehouseByPos.get(boxPos.immutable()) : null;
        return id != null ? warehouses.get(id) : null;
    }

    public LogisticsClientData clientAt(BlockPos boxPos) {
        UUID id = boxPos != null ? clientByPos.get(boxPos.immutable()) : null;
        return id != null ? clients.get(id) : null;
    }

    public LogisticsWarehouseData warehouse(UUID warehouseId) {
        return warehouseId != null ? warehouses.get(warehouseId) : null;
    }

    public LogisticsClientData manualClient(UUID clientId) {
        return clientId != null ? clients.get(clientId) : null;
    }

    public LogisticsChannelData channel(UUID channelId) {
        return channelId != null ? channels.get(channelId) : null;
    }

    public Collection<LogisticsWarehouseData> warehouses() {
        return List.copyOf(warehouses.values());
    }

    public List<LogisticsWarehouseData> warehouses(UUID cityId) {
        Set<UUID> ids = cityId != null ? warehousesByCity.get(cityId) : null;
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(warehouses::get).filter(data -> data != null).toList();
    }

    public List<LogisticsClientData> manualClients(UUID cityId) {
        Set<UUID> ids = cityId != null ? clientsByCity.get(cityId) : null;
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(clients::get).filter(data -> data != null && !data.automatic()).toList();
    }

    public List<LogisticsChannelData> channels(UUID warehouseId) {
        Set<UUID> ids = warehouseId != null ? channelsByWarehouse.get(warehouseId) : null;
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(channels::get).filter(data -> data != null).toList();
    }

    public List<LogisticsChannelData> allChannels() {
        return List.copyOf(channels.values());
    }

    public synchronized void updateWarehouse(LogisticsWarehouseData data) {
        replaceWarehouse(warehouses.get(data.warehouseId()), data);
        persistWarehouse(data);
        setDirty();
    }

    public synchronized void updateClient(LogisticsClientData data) {
        replaceClient(clients.get(data.clientId()), data);
        persistClient(data);
        setDirty();
    }

    public synchronized void updateChannel(LogisticsChannelData data) {
        putLoadedChannel(data);
        persistChannel(data);
        setDirty();
    }

    public synchronized void removeWarehouse(BlockPos boxPos) {
        LogisticsWarehouseData data = warehouseAt(boxPos);
        if (data == null) {
            return;
        }
        removeWarehouseIndexes(data);
        List<UUID> removedChannels = new ArrayList<>();
        for (LogisticsChannelData channel : channels.values()) {
            if (data.warehouseId().equals(channel.warehouseId())) {
                removedChannels.add(channel.channelId());
            }
        }
        removedChannels.forEach(this::removeChannelInMemory);
        SimuSqliteStorage.deleteLogisticsWarehouse(level, data.warehouseId());
        setDirty();
    }

    public synchronized void removeClient(BlockPos boxPos) {
        LogisticsClientData data = clientAt(boxPos);
        if (data == null) {
            return;
        }
        removeClientIndexes(data);
        List<UUID> removedChannels = new ArrayList<>();
        for (LogisticsChannelData channel : channels.values()) {
            if (data.clientId().equals(channel.clientId())) {
                removedChannels.add(channel.channelId());
            }
        }
        removedChannels.forEach(this::removeChannelInMemory);
        SimuSqliteStorage.deleteLogisticsClient(level, data.clientId());
        setDirty();
    }

    public synchronized void removeChannel(UUID channelId) {
        removeChannelInMemory(channelId);
        SimuSqliteStorage.deleteLogisticsChannel(level, channelId);
        setDirty();
    }

    public synchronized void releaseCity(UUID cityId) {
        List.copyOf(warehouses(cityId)).forEach(w -> updateWarehouse(w.withNoCityId()));
        Set<UUID> cids = clientsByCity.get(cityId);
        if (cids != null) {
            Set.copyOf(cids).stream().map(clients::get).filter(c -> c != null)
                    .forEach(c -> updateClient(c.withNoCityId()));
        }
    }

    private void persistWarehouse(LogisticsWarehouseData data) {
        if (level != null && data != null) {
            SimuSqliteStorage.saveLogisticsWarehouse(level, data.toTag());
        }
    }

    private void persistClient(LogisticsClientData data) {
        if (level != null && data != null && !data.automatic()) {
            SimuSqliteStorage.saveLogisticsClient(level, data.toTag());
        }
    }

    private void persistChannel(LogisticsChannelData data) {
        if (level != null && data != null) {
            SimuSqliteStorage.saveLogisticsChannel(level, data.toTag());
        }
    }

    private void putLoadedWarehouse(LogisticsWarehouseData data) {
        replaceWarehouse(warehouses.get(data.warehouseId()), data);
    }

    private void putLoadedClient(LogisticsClientData data) {
        replaceClient(clients.get(data.clientId()), data);
    }

    public long getChannelVersion() {
        return channelVersion;
    }

    private void putLoadedChannel(LogisticsChannelData data) {
        LogisticsChannelData old = channels.put(data.channelId(), data);
        channelVersion++;
        if (old != null && old.warehouseId() != null) {
            Set<UUID> oldIds = channelsByWarehouse.get(old.warehouseId());
            if (oldIds != null) {
                oldIds.remove(old.channelId());
            }
        }
        if (data.warehouseId() != null) {
            channelsByWarehouse.computeIfAbsent(data.warehouseId(), ignored -> ConcurrentHashMap.newKeySet()).add(data.channelId());
        }
    }

    private void replaceWarehouse(LogisticsWarehouseData oldData, LogisticsWarehouseData newData) {
        if (oldData != null) {
            removeWarehouseIndexes(oldData);
        }
        UUID oldIdAtPos = warehouseByPos.get(newData.boxPos().immutable());
        if (oldIdAtPos != null && !oldIdAtPos.equals(newData.warehouseId())) {
            LogisticsWarehouseData oldAtPos = warehouses.get(oldIdAtPos);
            if (oldAtPos != null) {
                removeWarehouseIndexes(oldAtPos);
                removeChannelsForWarehouse(oldAtPos.warehouseId());
            }
        }
        warehouses.put(newData.warehouseId(), newData);
        warehouseByPos.put(newData.boxPos().immutable(), newData.warehouseId());
        if (newData.cityId() != null) {
            warehousesByCity.computeIfAbsent(newData.cityId(), ignored -> ConcurrentHashMap.newKeySet()).add(newData.warehouseId());
        }
    }

    private void replaceClient(LogisticsClientData oldData, LogisticsClientData newData) {
        if (oldData != null) {
            removeClientIndexes(oldData);
        }
        UUID oldIdAtPos = clientByPos.get(newData.boxPos().immutable());
        if (oldIdAtPos != null && !oldIdAtPos.equals(newData.clientId())) {
            LogisticsClientData oldAtPos = clients.get(oldIdAtPos);
            if (oldAtPos != null) {
                removeClientIndexes(oldAtPos);
                removeChannelsForClient(oldAtPos.clientId());
            }
        }
        clients.put(newData.clientId(), newData);
        clientByPos.put(newData.boxPos().immutable(), newData.clientId());
        if (newData.cityId() != null) {
            clientsByCity.computeIfAbsent(newData.cityId(), ignored -> ConcurrentHashMap.newKeySet()).add(newData.clientId());
        }
    }

    private void removeWarehouseIndexes(LogisticsWarehouseData data) {
        warehouses.remove(data.warehouseId());
        warehouseByPos.remove(data.boxPos(), data.warehouseId());
        if (data.cityId() != null) {
            Set<UUID> ids = warehousesByCity.get(data.cityId());
            if (ids != null) {
                ids.remove(data.warehouseId());
            }
        }
        channelsByWarehouse.remove(data.warehouseId());
    }

    private void removeClientIndexes(LogisticsClientData data) {
        clients.remove(data.clientId());
        clientByPos.remove(data.boxPos(), data.clientId());
        if (data.cityId() != null) {
            Set<UUID> ids = clientsByCity.get(data.cityId());
            if (ids != null) {
                ids.remove(data.clientId());
            }
        }
    }

    private void removeChannelInMemory(UUID channelId) {
        LogisticsChannelData removed = channels.remove(channelId);
        channelVersion++;
        if (removed != null && removed.warehouseId() != null) {
            Set<UUID> ids = channelsByWarehouse.get(removed.warehouseId());
            if (ids != null) {
                ids.remove(channelId);
            }
        }
    }

    private void removeChannelsForWarehouse(UUID warehouseId) {
        if (warehouseId == null) {
            return;
        }
        channels.values().stream()
                .filter(channel -> warehouseId.equals(channel.warehouseId()))
                .map(LogisticsChannelData::channelId)
                .toList()
                .forEach(this::removeChannelInMemory);
    }

    private void removeChannelsForClient(UUID clientId) {
        if (clientId == null) {
            return;
        }
        channels.values().stream()
                .filter(channel -> clientId.equals(channel.clientId()))
                .map(LogisticsChannelData::channelId)
                .toList()
                .forEach(this::removeChannelInMemory);
    }

    private void clearIndexes() {
        warehouses.clear();
        warehouseByPos.clear();
        warehousesByCity.clear();
        clients.clear();
        clientByPos.clear();
        clientsByCity.clear();
        channels.clear();
        channelsByWarehouse.clear();
    }
}
