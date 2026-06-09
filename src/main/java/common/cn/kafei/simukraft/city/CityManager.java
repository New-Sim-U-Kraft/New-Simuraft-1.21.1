package common.cn.kafei.simukraft.city;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CityManager extends SavedData {
    private static final String DATA_NAME = SimuKraft.MOD_ID + "_cities";
    private static final Factory<CityManager> FACTORY = new Factory<>(CityManager::new, CityManager::load, null);

    private final ConcurrentMap<UUID, CityData> cities = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> playerCityIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockPos, UUID> corePosIndex = new ConcurrentHashMap<>();
    private volatile boolean sqliteLoaded;
    private volatile ServerLevel level;

    public static CityManager get(ServerLevel level) {
        ServerLevel storageLevel = storageLevel(level);
        CityManager manager = storageLevel.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        manager.level = storageLevel;
        manager.loadFromSqlite(storageLevel);
        return manager;
    }

    /**
     * storageLevel: 城市数据是服务器全局数据，统一挂在主世界，避免多维度副本互相覆盖 SQLite。
     */
    private static ServerLevel storageLevel(ServerLevel level) {
        return level.getServer() != null ? level.getServer().overworld() : level;
    }

    private static CityManager load(CompoundTag tag, HolderLookup.Provider registries) {
        CityManager manager = new CityManager();
        ListTag cityTags = tag.getList("Cities", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < cityTags.size(); i++) {
            CityData city = CityData.fromTag(cityTags.getCompound(i));
            manager.cities.put(city.cityId(), city);
            manager.corePosIndex.put(city.cityCorePos().immutable(), city.cityId());
            city.members().forEach(member -> manager.playerCityIndex.put(member.playerId(), city.cityId()));
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag cityTags = new ListTag();
        cities.values().forEach(city -> cityTags.add(city.toTag()));
        tag.put("Cities", cityTags);
        return tag;
    }

    public synchronized void saveToSqlite(ServerLevel level) {
        if (level == null) {
            return;
        }
        SimuSqliteStorage.saveCities(level, save(new CompoundTag(), level.registryAccess()));
    }

    private synchronized void loadFromSqlite(ServerLevel level) {
        if (sqliteLoaded) {
            return;
        }
        sqliteLoaded = true;
        CompoundTag sqliteTag = SimuSqliteStorage.loadCities(level);
        if (sqliteTag == null || sqliteTag.isEmpty()) {
            return;
        }
        CityManager loaded = load(sqliteTag, level.registryAccess());
        cities.clear();
        playerCityIndex.clear();
        corePosIndex.clear();
        cities.putAll(loaded.cities);
        playerCityIndex.putAll(loaded.playerCityIndex);
        corePosIndex.putAll(loaded.corePosIndex);
    }

    private void saveCityIncremental(CityData city) {
        ServerLevel targetLevel = level;
        if (targetLevel != null && city != null) {
            SimuSqliteStorage.saveCity(targetLevel, city.toTag());
        }
    }

    private void deleteCityIncremental(UUID cityId) {
        ServerLevel targetLevel = level;
        if (targetLevel != null && cityId != null) {
            SimuSqliteStorage.deleteCity(targetLevel, cityId);
        }
    }

    public CityData createCity(String cityName, UUID mayorId, String mayorName, BlockPos cityCorePos) {
        UUID cityId = UUID.randomUUID();
        CityData city = new CityData(cityId, cityName, mayorId, mayorName, cityCorePos);
        cities.put(cityId, city);
        playerCityIndex.put(mayorId, cityId);
        corePosIndex.put(city.cityCorePos().immutable(), cityId);
        saveCityIncremental(city);
        setDirty();
        return city;
    }

    public Optional<CityData> getCity(UUID cityId) {
        return Optional.ofNullable(cities.get(cityId));
    }

    public Optional<CityData> getPlayerCity(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        UUID cityId = playerCityIndex.get(playerId);
        Optional<CityData> indexedCity = cityId != null ? getCity(cityId) : Optional.empty();
        if (indexedCity.isPresent() && indexedCity.get().member(playerId).isPresent()) {
            return indexedCity;
        }
        if (cityId != null) {
            playerCityIndex.remove(playerId, cityId);
        }
        for (CityData city : cities.values()) {
            if (city.member(playerId).isPresent()) {
                playerCityIndex.put(playerId, city.cityId());
                return Optional.of(city);
            }
        }
        return Optional.empty();
    }

    // 查找玩家可管理的城市，用于便携式城市核心等远程管理入口。
    public Optional<CityData> getManagedPlayerCity(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        Optional<CityData> indexedCity = getPlayerCity(playerId)
                .filter(city -> city.hasPermission(playerId, CityPermissionLevel.OFFICIAL));
        if (indexedCity.isPresent()) {
            return indexedCity;
        }
        for (CityData city : cities.values()) {
            if (city.hasPermission(playerId, CityPermissionLevel.OFFICIAL)) {
                playerCityIndex.put(playerId, city.cityId());
                return Optional.of(city);
            }
        }
        return Optional.empty();
    }

    public Optional<CityData> getCityByCorePos(BlockPos cityCorePos) {
        if (cityCorePos == null) {
            return Optional.empty();
        }
        UUID cityId = corePosIndex.get(cityCorePos.immutable());
        return cityId != null ? getCity(cityId) : Optional.empty();
    }

    public boolean hasCityAtCorePos(BlockPos cityCorePos) {
        return cityCorePos != null && corePosIndex.containsKey(cityCorePos.immutable());
    }

    public boolean hasCityNamed(String cityName) {
        return hasCityNamedExcept(cityName, null);
    }

    public boolean hasCityNamedExcept(String cityName, UUID ignoredCityId) {
        if (cityName == null || cityName.isBlank()) {
            return false;
        }
        String normalizedName = cityName.trim();
        return cities.values().stream().anyMatch(city -> !city.cityId().equals(ignoredCityId) && city.cityName().equalsIgnoreCase(normalizedName));
    }

    public Collection<CityData> allCities() {
        return cities.values();
    }

    public boolean renameCity(UUID cityId, UUID operatorId, String cityName) {
        CityData city = cities.get(cityId);
        if (city == null || !city.hasPermission(operatorId, CityPermissionLevel.MAYOR) || hasCityNamedExcept(cityName, cityId)) {
            return false;
        }
        city.setCityName(cityName);
        setDirty();
        return true;
    }

    public boolean deleteCity(UUID cityId, UUID operatorId, CityChunkManager chunkManager) {
        return deleteCity(cityId, operatorId, chunkManager, null);
    }

    public boolean deleteCity(UUID cityId, UUID operatorId, CityChunkManager chunkManager, CityPoiManager poiManager) {
        CityData city = cities.get(cityId);
        if (city == null || !city.hasPermission(operatorId, CityPermissionLevel.MAYOR)) {
            return false;
        }
        cities.remove(cityId);
        corePosIndex.remove(city.cityCorePos().immutable());
        city.members().forEach(member -> playerCityIndex.remove(member.playerId(), cityId));
        if (chunkManager != null) {
            chunkManager.releaseCity(cityId);
        }
        if (poiManager != null) {
            poiManager.releaseCity(cityId);
        }
        deleteCityIncremental(cityId);
        setDirty();
        return true;
    }

    public Optional<CityMemberData> getMember(UUID cityId, UUID playerId) {
        CityData city = cities.get(cityId);
        return city == null ? Optional.empty() : city.member(playerId);
    }

    public Collection<CityMemberData> getMembers(UUID cityId) {
        CityData city = cities.get(cityId);
        return city == null ? Set.of() : city.members();
    }

    public boolean addPlayerToCity(UUID cityId, UUID operatorId, UUID targetId, String targetName, CityPermissionLevel permissionLevel) {
        CityData city = cities.get(cityId);
        if (city == null || !city.hasPermission(operatorId, CityPermissionLevel.OFFICIAL)) {
            return false;
        }
        if (permissionLevel == CityPermissionLevel.MAYOR) {
            return false;
        }
        Optional<CityMemberData> existingMember = city.member(targetId);
        if (existingMember.map(CityMemberData::permissionLevel).orElse(CityPermissionLevel.CITIZEN) == CityPermissionLevel.MAYOR) {
            return false;
        }
        if (permissionLevel == CityPermissionLevel.OFFICIAL && !city.hasPermission(operatorId, CityPermissionLevel.MAYOR)) {
            return false;
        }
        UUID oldCityId = playerCityIndex.get(targetId);
        if (oldCityId != null && !oldCityId.equals(cityId)) {
            return false;
        }
        city.addOrUpdateMember(targetId, targetName, permissionLevel);
        playerCityIndex.put(targetId, cityId);
        saveCityIncremental(city);
        setDirty();
        return true;
    }

    public boolean removePlayerFromCity(UUID cityId, UUID operatorId, UUID targetId) {
        CityData city = cities.get(cityId);
        if (city == null || !city.hasPermission(operatorId, CityPermissionLevel.OFFICIAL)) {
            return false;
        }
        if (operatorId.equals(targetId)) {
            return false;
        }
        Optional<CityMemberData> targetMember = city.member(targetId);
        if (targetMember.map(CityMemberData::permissionLevel).orElse(CityPermissionLevel.CITIZEN).atLeast(CityPermissionLevel.OFFICIAL)
                && !city.hasPermission(operatorId, CityPermissionLevel.MAYOR)) {
            return false;
        }
        boolean removed = city.removeMember(targetId);
        if (removed) {
            playerCityIndex.remove(targetId);
            saveCityIncremental(city);
            setDirty();
        }
        return removed;
    }

    public boolean setPlayerPermission(UUID cityId, UUID operatorId, UUID targetId, CityPermissionLevel permissionLevel) {
        CityData city = cities.get(cityId);
        if (city == null || !city.hasPermission(operatorId, CityPermissionLevel.MAYOR)) {
            return false;
        }
        boolean changed = city.setPermission(targetId, permissionLevel);
        if (changed) {
            saveCityIncremental(city);
            setDirty();
        }
        return changed;
    }

    // transferMayor: 校验城市归属后持久化市长转让结果。
    public boolean transferMayor(UUID cityId, UUID operatorId, UUID targetId, String targetName) {
        CityData city = cities.get(cityId);
        if (city == null || !city.hasPermission(operatorId, CityPermissionLevel.MAYOR)) {
            return false;
        }
        UUID oldCityId = playerCityIndex.get(targetId);
        if (oldCityId != null && !oldCityId.equals(cityId)) {
            return false;
        }
        boolean changed = city.transferMayor(operatorId, targetId, targetName);
        if (changed) {
            playerCityIndex.put(targetId, cityId);
            saveCityIncremental(city);
            setDirty();
        }
        return changed;
    }

    public boolean hasPermission(UUID cityId, UUID playerId, CityPermissionLevel required) {
        CityData city = cities.get(cityId);
        return city != null && city.hasPermission(playerId, required);
    }

    public boolean depositFunds(UUID cityId, double amount) {
        CityData city = cities.get(cityId);
        if (city == null || !city.depositFunds(amount)) {
            return false;
        }
        saveCityIncremental(city);
        setDirty();
        return true;
    }

    public boolean withdrawFunds(UUID cityId, double amount) {
        CityData city = cities.get(cityId);
        if (city == null || !city.withdrawFunds(amount)) {
            return false;
        }
        saveCityIncremental(city);
        setDirty();
        return true;
    }

    public boolean setFunds(UUID cityId, double funds) {
        CityData city = cities.get(cityId);
        if (city == null) {
            return false;
        }
        city.setFunds(funds);
        saveCityIncremental(city);
        setDirty();
        return true;
    }

    public boolean addFinanceTransaction(UUID cityId, FinanceTransactionData transaction, int maxRecords) {
        CityData city = cities.get(cityId);
        if (city == null) {
            return false;
        }
        city.addFinanceTransaction(transaction, maxRecords);
        saveCityIncremental(city);
        setDirty();
        return true;
    }
}
