package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class CitizenService {
    private CitizenService() {
    }

    public static CitizenData ensureCitizen(ServerLevel level, CitizenEntity entity) {
        if (level == null || entity == null) {
            return null;
        }
        if (entity.isRemoved() || !entity.isAlive() || entity.getHealth() <= 0.0F) {
            return null;
        }
        return CitizenManager.get(level).getOrCreate(entity);
    }

    public static void syncEntity(ServerLevel level, CitizenEntity entity) {
        if (level == null || entity == null) {
            return;
        }
        CitizenManager.get(level).syncEntity(entity);
    }

    public static void updateProfile(ServerLevel level, UUID citizenId, String name, String statusLabel, String skinPath) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenManager manager = CitizenManager.get(level);
        manager.getCitizen(citizenId).ifPresent(data -> {
            data.setName(name);
            data.setStatusLabel(statusLabel);
            data.setSkinPath(skinPath);
            manager.saveCitizenNow(citizenId);
        });
    }

    public static void setCity(ServerLevel level, UUID citizenId, UUID cityId) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenManager manager = CitizenManager.get(level);
        manager.getCitizen(citizenId).ifPresent(data -> {
            if (data.dead()) {
                return;
            }
            data.setCityId(cityId);
            manager.saveCitizenNow(citizenId);
        });
    }

    public static void setHome(ServerLevel level, UUID citizenId, UUID homeId) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenManager manager = CitizenManager.get(level);
        manager.getCitizen(citizenId).ifPresent(data -> {
            if (data.dead()) {
                return;
            }
            data.setHomeId(homeId);
            manager.saveCitizenNow(citizenId);
        });
    }

    public static void setWorkplace(ServerLevel level, UUID citizenId, UUID workplaceId) {
        setWorkplace(level, citizenId, workplaceId, null);
    }

    // setWorkplace：保存岗位 UUID 和可选坐标，非 POI 岗位需要坐标才能第二天回岗。
    public static void setWorkplace(ServerLevel level, UUID citizenId, UUID workplaceId, BlockPos workplacePos) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenManager manager = CitizenManager.get(level);
        manager.getCitizen(citizenId).ifPresent(data -> {
            if (data.dead()) {
                return;
            }
            data.setWorkplaceId(workplaceId);
            data.setWorkplacePos(workplacePos);
            manager.saveCitizenNow(citizenId);
        });
    }

    public static void save(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenManager.get(level).saveCitizenNow(citizenId);
    }

    public static void applyEmployment(ServerLevel level, UUID citizenId, CityJobType jobType, UUID workplaceId, String statusLabel) {
        applyEmployment(level, citizenId, jobType, workplaceId, null, statusLabel);
    }

    // applyEmployment：雇佣时同时记录岗位坐标，保障建造箱这类非 POI 岗位可恢复移动。
    public static void applyEmployment(ServerLevel level, UUID citizenId, CityJobType jobType, UUID workplaceId, BlockPos workplacePos, String statusLabel) {
        if (level == null || citizenId == null || workplaceId == null) {
            return;
        }
        CitizenManager manager = CitizenManager.get(level);
        manager.getCitizen(citizenId).ifPresent(data -> {
            if (data.dead()) {
                return;
            }
            data.setJobType(jobType != null ? jobType : CityJobType.OTHER);
            data.setWorkStatus(CitizenWorkStatus.WORKING);
            data.setWorkplaceId(workplaceId);
            data.setWorkplacePos(workplacePos);
            data.setStatusLabel(statusLabel != null ? statusLabel : "");
            manager.saveCitizenNow(citizenId);
        });
    }

    public static void clearEmployment(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenManager manager = CitizenManager.get(level);
        manager.getCitizen(citizenId).ifPresent(data -> {
            if (data.dead()) {
                return;
            }
            data.setJobType(CityJobType.UNEMPLOYED);
            data.setWorkStatus(CitizenWorkStatus.IDLE);
            data.setWorkplaceId(null);
            data.setWorkplacePos(null);
            data.setStatusLabel("");
            data.setWorkNeedDetail("");
            manager.saveCitizenNow(citizenId);
        });
    }

    public static Optional<CitizenData> findCitizen(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return Optional.empty();
        }
        return CitizenManager.get(level).getCitizen(citizenId);
    }

    public static boolean isHireable(CitizenData data) {
        return data != null
                && !data.dead()
                && !data.child()
                && data.jobType() == CityJobType.UNEMPLOYED
                && data.workplaceId() == null;
    }

    public static UUID findAssignedCitizen(ServerLevel level, UUID workplaceId) {
        if (level == null || workplaceId == null) {
            return null;
        }
        return CitizenManager.get(level).allCitizens().stream()
                .filter(data -> !data.dead())
                .filter(data -> workplaceId.equals(data.workplaceId()))
                .map(CitizenData::uuid)
                .findFirst()
                .orElse(null);
    }

    public static List<CitizenData> listHireableCitizens(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        return CitizenManager.get(level).allCitizens().stream()
                .filter(CitizenService::isHireable)
                .sorted(Comparator.comparing(CitizenData::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static List<CitizenData> listCitizensByCity(ServerLevel level, UUID cityId) {
        if (level == null || cityId == null) {
            return List.of();
        }
        return CitizenManager.get(level).allCitizens().stream()
                .filter(data -> !data.dead())
                .filter(data -> cityId.equals(data.cityId()))
                .toList();
    }

    public static boolean belongsToCity(CitizenData data, UUID cityId) {
        return data != null && !data.dead() && cityId != null && cityId.equals(data.cityId());
    }

    public static boolean canAddCitizen(ServerLevel level, UUID cityId) {
        if (level == null || cityId == null) {
            return false;
        }
        return CitizenHousingService.vacantHomeCount(level, cityId) > 0;
    }

    public static Optional<CitizenEntity> spawnCitizen(ServerLevel level, BlockPos pos, UUID cityId) {
        return spawnCitizen(level, pos, cityId, false);
    }

    public static Optional<CitizenEntity> spawnCitizen(ServerLevel level, BlockPos pos, UUID cityId, boolean ignoreHousingCapacity) {
        if (level == null || pos == null || cityId == null) {
            return Optional.empty();
        }
        Vec3 target = Vec3.atBottomCenterOf(pos).add(0.0D, 1.0D, 0.0D);
        return spawnCitizen(level, target, cityId, ignoreHousingCapacity);
    }

    // spawnCitizen：按实体脚底坐标生成市民，用于床边、安全点等精确落点。
    public static Optional<CitizenEntity> spawnCitizen(ServerLevel level, Vec3 target, UUID cityId, boolean ignoreHousingCapacity) {
        if (level == null || target == null || cityId == null) {
            return Optional.empty();
        }
        if (!ignoreHousingCapacity && !canAddCitizen(level, cityId)) {
            return Optional.empty();
        }
        CitizenEntity entity = ModEntities.CITIZEN.get().create(level);
        if (entity == null) {
            return Optional.empty();
        }
        entity.moveTo(target.x, target.y, target.z, level.random.nextFloat() * 360.0F, 0.0F);
        entity.setPersistenceRequired();
        CitizenData data = ensureCitizen(level, entity);
        if (data != null) {
            data.setCityId(cityId);
            CitizenManager manager = CitizenManager.get(level);
            manager.saveCitizenNow(data.uuid());
            manager.syncEntity(entity);
            manager.markChanged();
        }
        level.addFreshEntity(entity);
        return Optional.of(entity);
    }
}
