package common.cn.kafei.simukraft.job;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CityJobAssignmentService {
    // 职位占用统计按存档+城市缓存，分配/解雇后通过 invalidate 重建。
    private static final ConcurrentMap<AssignmentCacheKey, AssignmentIndex> INDICES = new ConcurrentHashMap<>();

    private CityJobAssignmentService() {
    }

    public static JobAssignmentResult assignFirstAvailable(ServerLevel level, UUID citizenId, CityJobType jobType) {
        if (level == null || citizenId == null || jobType == null || jobType == CityJobType.UNEMPLOYED || jobType == CityJobType.RESIDENT) {
            return JobAssignmentResult.INVALID_JOB;
        }
        Optional<CitizenData> citizen = CitizenService.findCitizen(level, citizenId);
        if (citizen.isEmpty() || citizen.get().dead()) {
            return JobAssignmentResult.CITIZEN_NOT_FOUND;
        }
        UUID cityId = citizen.get().cityId();
        if (cityId == null) {
            return JobAssignmentResult.NO_CITY;
        }
        AssignmentIndex index = index(level, cityId);
        return CityJobType.primaryPoiType(jobType)
                .flatMap(poiType -> firstAvailablePoi(level, cityId, poiType, index))
                .map(poi -> assign(level, citizen.get(), cityId, jobType, poi, index))
                .orElse(JobAssignmentResult.NO_CAPACITY);
    }

    public static JobAssignmentResult clearAssignment(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return JobAssignmentResult.CITIZEN_NOT_FOUND;
        }
        Optional<CitizenData> citizen = CitizenService.findCitizen(level, citizenId);
        if (citizen.isEmpty() || citizen.get().dead()) {
            return JobAssignmentResult.CITIZEN_NOT_FOUND;
        }
        CitizenData data = citizen.get();
        UUID cityId = data.cityId();
        CitizenService.clearEmployment(level, data.uuid());
        if (cityId != null) {
            invalidate(cityId);
        }
        return JobAssignmentResult.ASSIGNED;
    }

    public static List<CityJobAssignment> getAssignments(ServerLevel level, UUID cityId) {
        if (level == null || cityId == null) {
            return List.of();
        }
        List<CityJobAssignment> assignments = new ArrayList<>();
        CitizenService.listCitizensByCity(level, cityId).forEach(data -> assignments.add(new CityJobAssignment(data.uuid(), cityId, data.jobType(), data.workplaceId(), 0L)));
        return List.copyOf(assignments);
    }

    public static int getAssignedCount(ServerLevel level, UUID cityId, CityJobType jobType) {
        if (level == null || cityId == null || jobType == null) {
            return 0;
        }
        AssignmentIndex index = index(level, cityId);
        return index.assignedByJob.getOrDefault(jobType, 0);
    }

    public static void invalidate(UUID cityId) {
        if (cityId != null) {
            INDICES.keySet().removeIf(key -> cityId.equals(key.cityId()));
        }
    }

    // 清理指定存档的职业容量索引，避免玩家 UUID/城市 UUID 碰撞时串档。
    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server);
        INDICES.keySet().removeIf(key -> serverKey.equals(key.serverKey()));
    }

    private static JobAssignmentResult assign(ServerLevel level, CitizenData citizen, UUID cityId, CityJobType jobType, CityPoiData poi, AssignmentIndex index) {
        if (!CitizenService.belongsToCity(citizen, cityId)) {
            return JobAssignmentResult.CITIZEN_NOT_IN_CITY;
        }
        int assignedAtPoi = index.assignedByPoi.getOrDefault(poi.poiId(), 0);
        if (assignedAtPoi >= poi.capacity()) {
            return JobAssignmentResult.NO_CAPACITY;
        }
        CitizenService.applyEmployment(level, citizen.uuid(), jobType, poi.poiId(), poi.pos(), citizen.statusLabel());
        invalidate(cityId);
        return JobAssignmentResult.ASSIGNED;
    }

    private static Optional<CityPoiData> firstAvailablePoi(ServerLevel level, UUID cityId, CityPoiType poiType, AssignmentIndex index) {
        CityPoiManager manager = CityPoiManager.get(level);
        return manager.getCityPois(cityId, poiType).stream()
                .filter(poi -> index.assignedByPoi.getOrDefault(poi.poiId(), 0) < poi.capacity())
                .findFirst();
    }

    private static AssignmentIndex index(ServerLevel level, UUID cityId) {
        AssignmentCacheKey key = new AssignmentCacheKey(SaveScopedCacheKey.serverKey(level.getServer()), cityId);
        return INDICES.computeIfAbsent(key, ignored -> buildIndex(level, cityId));
    }

    private static AssignmentIndex buildIndex(ServerLevel level, UUID cityId) {
        AssignmentIndex index = new AssignmentIndex();
        CityPoiManager poiManager = CityPoiManager.get(level);
        // 以居民当前 workplaceId 为准重建索引，自动跳过已删除或跨城市的 POI。
        CitizenService.listCitizensByCity(level, cityId).stream()
                .filter(data -> data.workplaceId() != null)
                .forEach(data -> {
                    CityPoiData poi = poiManager.getPoi(data.workplaceId());
                    if (poi == null || !poi.active() || !cityId.equals(poi.cityId())) {
                        return;
                    }
                    CityJobType jobType = data.jobType();
                    index.assignedByPoi.merge(poi.poiId(), 1, Integer::sum);
                    index.assignedByJob.merge(jobType, 1, Integer::sum);
                });
        return index;
    }

    private static final class AssignmentIndex {
        private final Map<UUID, Integer> assignedByPoi = new ConcurrentHashMap<>();
        private final Map<CityJobType, Integer> assignedByJob = new EnumMap<>(CityJobType.class);
    }

    private record AssignmentCacheKey(String serverKey, UUID cityId) {
    }
}
