package common.cn.kafei.simukraft.storage;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SimuSqliteStorage {
    private static final ConcurrentMap<Path, SimuSqliteStorage> STORAGES = new ConcurrentHashMap<>();

    private final CitySqliteRepository cities;
    private final CityChunkSqliteRepository cityChunks;
    private final CityPoiSqliteRepository cityPois;
    private final CitizenSqliteRepository citizens;
    private final BuildingTaskSqliteRepository buildingTasks;
    private final FarmlandBoxSqliteRepository farmlandBoxes;
    private final PlanningTaskSqliteRepository planningTasks;
    private final IndustrialBoxSqliteRepository industrialBoxes;
    private final CommercialSqliteRepository commercial;

    private SimuSqliteStorage(SimuSqliteDatabase database) {
        this.cities = new CitySqliteRepository(database);
        this.cityChunks = new CityChunkSqliteRepository(database);
        this.cityPois = new CityPoiSqliteRepository(database);
        this.citizens = new CitizenSqliteRepository(database);
        this.buildingTasks = new BuildingTaskSqliteRepository(database);
        this.farmlandBoxes = new FarmlandBoxSqliteRepository(database);
        this.planningTasks = new PlanningTaskSqliteRepository(database);
        this.industrialBoxes = new IndustrialBoxSqliteRepository(database);
        this.commercial = new CommercialSqliteRepository(database);
    }

    public static SimuSqliteStorage open(MinecraftServer server) {
        Path databasePath = SimuSqliteDatabase.databasePath(server).toAbsolutePath().normalize();
        return STORAGES.computeIfAbsent(databasePath, ignored -> new SimuSqliteStorage(SimuSqliteDatabase.open(server)));
    }

    public static void clearServerCache(MinecraftServer server) {
        if (server == null) {
            return;
        }
        STORAGES.remove(SimuSqliteDatabase.databasePath(server).toAbsolutePath().normalize());
    }

    private static SimuSqliteStorage openSafely(ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            return open(level.getServer());
        } catch (RuntimeException exception) {
            // 数据库不可用时不让游戏崩溃，当前 tick 退回内存/SavedData 状态。
            SimuKraft.LOGGER.error("SQLite storage is unavailable. Falling back to in-memory/SavedData state for this operation.", exception);
            return null;
        }
    }

    public static CompoundTag loadCities(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.cities.loadAll() : null;
    }

    public static void saveCities(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && tag != null) {
            storage.cities.saveAll(tag);
        }
    }

    public static void saveCity(ServerLevel level, CompoundTag cityTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && cityTag != null) {
            storage.cities.upsert(cityTag);
        }
    }

    public static void deleteCity(ServerLevel level, UUID cityId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && cityId != null) {
            storage.cities.delete(cityId);
        }
    }

    public static CompoundTag loadCityChunks(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.cityChunks.loadAll() : null;
    }

    public static void saveCityChunks(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null) {
            storage.cityChunks.saveAll(tag);
        }
    }

    public static void saveCityChunk(ServerLevel level, UUID cityId, long chunkLong) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && cityId != null) {
            storage.cityChunks.upsert(cityId, chunkLong);
        }
    }

    public static void deleteCityChunks(ServerLevel level, UUID cityId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && cityId != null) {
            storage.cityChunks.deleteCity(cityId);
        }
    }

    public static CompoundTag loadCityPois(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.cityPois.loadAll() : null;
    }

    public static void saveCityPois(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && tag != null) {
            storage.cityPois.saveAll(tag);
        }
    }

    public static void saveCityPoi(ServerLevel level, CompoundTag poiTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && poiTag != null) {
            storage.cityPois.upsert(poiTag);
        }
    }

    public static void deleteCityPois(ServerLevel level, UUID cityId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && cityId != null) {
            storage.cityPois.deleteCity(cityId);
        }
    }

    public static CompoundTag loadCitizens(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.citizens.loadAll() : null;
    }

    public static void saveCitizens(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null) {
            storage.citizens.saveAll(tag);
        }
    }

    public static void saveCitizen(ServerLevel level, CompoundTag citizenTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && citizenTag != null) {
            storage.citizens.upsert(citizenTag);
        }
    }

    public static void deleteCitizen(ServerLevel level, UUID citizenId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && citizenId != null) {
            storage.citizens.delete(citizenId);
        }
    }

    public static void clearCitizenEmployment(ServerLevel level, UUID citizenId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && citizenId != null) {
            storage.citizens.clearEmployment(citizenId);
        }
    }

    public static void saveBuildingTask(ServerLevel level, common.cn.kafei.simukraft.building.BuildingTaskData task) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && task != null) {
            storage.buildingTasks.upsert(task);
        }
    }

    public static common.cn.kafei.simukraft.building.BuildingTaskData loadBuildingTask(ServerLevel level, UUID citizenId) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null && citizenId != null ? storage.buildingTasks.findByCitizen(citizenId) : null;
    }

    public static List<common.cn.kafei.simukraft.building.BuildingTaskData> loadBuildingTasks(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage == null) {
            return List.of();
        }
        return storage.buildingTasks.findByDimension(level.dimension().location().toString());
    }

    public static void deleteBuildingTask(ServerLevel level, UUID citizenId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && citizenId != null) {
            storage.buildingTasks.deleteByCitizen(citizenId);
        }
    }

    public static CompoundTag loadFarmlandBoxes(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.farmlandBoxes.loadAll() : null;
    }

    public static void saveFarmlandBoxes(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && tag != null) {
            storage.farmlandBoxes.saveAll(tag);
        }
    }

    public static void saveFarmlandBox(ServerLevel level, CompoundTag boxTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && boxTag != null) {
            storage.farmlandBoxes.upsert(boxTag);
        }
    }

    public static void deleteFarmlandBox(ServerLevel level, long boxPosLong) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null) {
            storage.farmlandBoxes.delete(boxPosLong);
        }
    }

    public static CompoundTag loadIndustrialBoxes(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.industrialBoxes.loadAll() : null;
    }

    public static void saveIndustrialBoxes(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && tag != null) {
            storage.industrialBoxes.saveAll(tag);
        }
    }

    public static void saveIndustrialBox(ServerLevel level, CompoundTag boxTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && boxTag != null) {
            storage.industrialBoxes.upsert(boxTag);
        }
    }

    public static void deleteIndustrialBox(ServerLevel level, long boxPosLong) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null) {
            storage.industrialBoxes.delete(boxPosLong);
        }
    }

    public static CompoundTag loadCommercialBoxes(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.commercial.loadBoxes() : null;
    }

    public static void saveCommercialBoxes(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && tag != null) {
            storage.commercial.saveBoxes(tag);
        }
    }

    public static void saveCommercialBox(ServerLevel level, CompoundTag boxTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && boxTag != null) {
            storage.commercial.upsertBox(boxTag);
        }
    }

    public static void deleteCommercialBox(ServerLevel level, long boxPosLong) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null) {
            storage.commercial.deleteBox(boxPosLong);
        }
    }

    public static CompoundTag loadCommercialStock(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        return storage != null ? storage.commercial.loadStock() : null;
    }

    public static void saveCommercialStock(ServerLevel level, CompoundTag tag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && tag != null) {
            storage.commercial.saveStock(tag);
        }
    }

    public static void saveCommercialStockEntry(ServerLevel level, CompoundTag stockTag) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && stockTag != null) {
            storage.commercial.upsertStockEntry(stockTag);
        }
    }

    public static void deleteCommercialStockAtBox(ServerLevel level, long boxPosLong) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null) {
            storage.commercial.deleteStockAtBox(boxPosLong);
        }
    }

    public static void savePlanningTask(ServerLevel level, common.cn.kafei.simukraft.planner.PlanningTaskData task) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && task != null) {
            storage.planningTasks.upsert(task);
        }
    }

    public static List<common.cn.kafei.simukraft.planner.PlanningTaskData> loadPlanningTasks(ServerLevel level) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage == null) {
            return List.of();
        }
        return storage.planningTasks.findByDimension(level.dimension().location().toString());
    }

    public static void deletePlanningTask(ServerLevel level, UUID citizenId) {
        SimuSqliteStorage storage = openSafely(level);
        if (storage != null && citizenId != null) {
            storage.planningTasks.deleteByCitizen(citizenId);
        }
    }

}
