package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

@SuppressWarnings("null")
public final class CitizenWorkplaceMoveService {
    private static final double ARRIVED_DISTANCE_SQR = 4.0D;

    private CitizenWorkplaceMoveService() {
    }

    // returnToWorkplace：让市民恢复上班时优先走寻路，失败或距离过远时由传送兜底。
    public static boolean returnToWorkplace(ServerLevel level, CitizenData citizen) {
        if (level == null || citizen == null || citizen.dead() || citizen.workplaceId() == null || citizen.jobType() == CityJobType.UNEMPLOYED) {
            return false;
        }
        Optional<Vec3> targetOptional = resolveWorkplaceTarget(level, citizen);
        if (targetOptional.isEmpty()) {
            return false;
        }
        Vec3 target = targetOptional.get();
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity != null && entity.position().distanceToSqr(target) <= ARRIVED_DISTANCE_SQR) {
            return true;
        }
        CitizenNavigationService.stop(level, citizen.uuid());
        if (entity != null && CitizenNavigationService.requestMove(level, citizen.uuid(), target, MovementIntent.WORK)) {
            return true;
        }
        return CitizenTeleportService.teleportOrSpawnCitizen(level, citizen, target);
    }

    // resolveWorkplaceTarget：解析普通城市 POI 或建筑师当前施工控制盒的岗位落点。
    public static Optional<Vec3> resolveWorkplaceTarget(ServerLevel level, CitizenData citizen) {
        if (level == null || citizen == null || citizen.workplaceId() == null) {
            return Optional.empty();
        }
        CityPoiData poi = CityPoiManager.get(level).getPoi(citizen.workplaceId());
        if (poi != null) {
            return poi.active() && (citizen.cityId() == null || citizen.cityId().equals(poi.cityId()))
                    ? Optional.of(targetAbove(poi.pos()))
                    : Optional.empty();
        }
        if (citizen.jobType() == CityJobType.BUILDER) {
            BlockPos buildBoxPos = BuilderConstructionService.findBuildBoxPos(level, citizen.uuid());
            if (buildBoxPos != null) {
                return Optional.of(targetAbove(buildBoxPos));
            }
        }
        if (citizen.workplacePos() != null) {
            return Optional.of(targetAbove(citizen.workplacePos()));
        }
        return Optional.empty();
    }

    private static Vec3 targetAbove(BlockPos pos) {
        return Vec3.atBottomCenterOf(pos).add(0.0D, 1.0D, 0.0D);
    }
}
