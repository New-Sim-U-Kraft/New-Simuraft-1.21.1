package common.cn.kafei.simukraft.job;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("null")
public final class CityJobMobilityService {
    private CityJobMobilityService() {
    }

    public static CityJobType resolveHireRole(String role) {
        if (role == null || role.isBlank()) {
            return CityJobType.OTHER;
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "builder" -> CityJobType.BUILDER;
            case "planner" -> CityJobType.PLANNER;
            case "farmer" -> CityJobType.FARMER;
            case "guard" -> CityJobType.GUARD;
            case "gatherer" -> CityJobType.GATHERER;
            case "commercial_worker", "commercial" -> CityJobType.COMMERCIAL_WORKER;
            case "industrial_worker", "industrial" -> CityJobType.INDUSTRIAL_WORKER;
            case "logistics_worker", "logistics" -> CityJobType.LOGISTICS_WORKER;
            case "storage_worker", "storage" -> CityJobType.STORAGE_WORKER;
            default -> CityJobType.OTHER;
        };
    }

    public static void teleportCitizenToWorkplace(ServerLevel level, UUID citizenId, BlockPos workplacePos, CityJobType jobType, CitizenWorkStatus workStatus, String statusLabel) {
        if (level == null || citizenId == null || workplacePos == null) {
            return;
        }
        CitizenEntity citizenEntity = findCitizenEntity(level, citizenId);
        if (citizenEntity == null) {
            SimuKraft.LOGGER.warn("Simukraft: Unable to teleport hired citizen {}, entity not found near workplace {}", citizenId, workplacePos);
            return;
        }
        Vec3 target = Vec3.atBottomCenterOf(workplacePos).add(0.0D, 1.0D, 0.0D);
        boolean moving = CitizenNavigationService.requestMove(level, citizenId, target, MovementIntent.WORK);
        if (!moving) {
            CitizenTeleportService.teleportCitizen(level, citizenId, target);
        }
        syncCitizenEntityState(citizenEntity, jobType, workStatus, statusLabel);
    }

    public static void resetCitizenAfterFire(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return;
        }
        CitizenEntity citizenEntity = CitizenTeleportService.findCitizenEntity(level, citizenId);
        if (citizenEntity == null) {
            return;
        }
        CitizenNavigationService.stop(level, citizenId);
        citizenEntity.getNavigation().stop();
        citizenEntity.setDeltaMovement(Vec3.ZERO);
        syncCitizenEntityState(citizenEntity, CityJobType.UNEMPLOYED, CitizenWorkStatus.IDLE, "");
    }

    public static void syncCitizenEntityState(CitizenEntity citizenEntity, CityJobType jobType, CitizenWorkStatus workStatus, String statusLabel) {
        if (citizenEntity == null) {
            return;
        }
        citizenEntity.setJob((jobType != null ? jobType : CityJobType.OTHER).name().toLowerCase(Locale.ROOT));
        citizenEntity.setWorkStatus((workStatus != null ? workStatus : CitizenWorkStatus.WORKING).translationKey());
        citizenEntity.setStatusLabel(statusLabel != null ? statusLabel : "");
    }

    private static CitizenEntity findCitizenEntity(ServerLevel level, UUID citizenId) {
        return CitizenTeleportService.findCitizenEntity(level, citizenId);
    }
}
