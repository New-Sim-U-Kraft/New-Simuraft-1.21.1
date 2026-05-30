package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("null")
public final class CitizenHousingService {
    private CitizenHousingService() {
    }

    public static int fillVacantHomes(ServerLevel level, UUID cityId) {
        return fillVacantHomes(level, cityId, Integer.MAX_VALUE);
    }

    public static int fillVacantHomes(ServerLevel level, UUID cityId, int maxAssignments) {
        if (level == null || cityId == null || maxAssignments <= 0) {
            return 0;
        }
        List<CityPoiData> vacantHomes = vacantHomes(level, cityId);
        if (vacantHomes.isEmpty()) {
            return 0;
        }
        CityPoiManager poiManager = CityPoiManager.get(level);
        List<CitizenData> homelessCitizens = CitizenManager.get(level).allCitizens().stream()
                .filter(citizen -> !citizen.dead())
                .filter(citizen -> cityId.equals(citizen.cityId()) && !hasValidHome(poiManager, cityId, citizen.homeId()))
                .sorted(Comparator.comparing(CitizenData::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int assigned = 0;
        int limit = Math.min(Math.min(vacantHomes.size(), homelessCitizens.size()), maxAssignments);
        for (int index = 0; index < limit; index++) {
            CitizenService.setHome(level, homelessCitizens.get(index).uuid(), vacantHomes.get(index).poiId());
            assigned++;
        }
        return assigned;
    }

    public static int spawnCitizensForVacantHomes(ServerLevel level, UUID cityId, BlockPos spawnPos, int maxSpawns) {
        if (level == null || cityId == null || spawnPos == null || maxSpawns <= 0) {
            return 0;
        }
        int spawned = 0;
        while (spawned < maxSpawns) {
            List<CityPoiData> vacantHomes = vacantHomes(level, cityId);
            if (vacantHomes.isEmpty()) {
                break;
            }
            CityPoiData home = vacantHomes.getFirst();
            Vec3 spawnTarget = resolveNewResidentSpawnTarget(level, home, spawnPos);
            var citizen = CitizenService.spawnCitizen(level, spawnTarget, cityId, true);
            if (citizen.isEmpty()) {
                break;
            }
            CitizenData data = CitizenService.ensureCitizen(level, citizen.get());
            if (data != null) {
                CitizenService.setHome(level, data.uuid(), home.poiId());
                spawned++;
            }
        }
        return spawned;
    }

    public static int vacantHomeCount(ServerLevel level, UUID cityId) {
        return vacantHomes(level, cityId).size();
    }

    private static Vec3 resolveNewResidentSpawnTarget(ServerLevel level, CityPoiData home, BlockPos fallbackPos) {
        if (home != null) {
            Vec3 homeTarget = CitizenHomeRestService.resolveHomeTarget(level, home.pos());
            if (homeTarget != null) {
                return homeTarget;
            }
        }
        return Vec3.atBottomCenterOf(fallbackPos).add(0.0D, 1.0D, 0.0D);
    }

    private static List<CityPoiData> vacantHomes(ServerLevel level, UUID cityId) {
        CityPoiManager poiManager = CityPoiManager.get(level);
        Set<UUID> occupiedHomes = CitizenManager.get(level).allCitizens().stream()
                .filter(citizen -> !citizen.dead())
                .filter(citizen -> cityId.equals(citizen.cityId()) && hasValidHome(poiManager, cityId, citizen.homeId()))
                .map(CitizenData::homeId)
                .collect(Collectors.toSet());
        return poiManager.getCityPois(cityId, CityPoiType.RESIDENTIAL).stream()
                .filter(CityPoiData::active)
                .filter(poi -> !occupiedHomes.contains(poi.poiId()))
                .sorted(Comparator.comparing(poi -> poi.pos().asLong()))
                .toList();
    }

    private static boolean hasValidHome(CityPoiManager poiManager, UUID cityId, UUID homeId) {
        if (poiManager == null || cityId == null || homeId == null) {
            return false;
        }
        CityPoiData home = poiManager.getPoi(homeId);
        return home != null && home.active() && home.type() == CityPoiType.RESIDENTIAL && cityId.equals(home.cityId());
    }
}
