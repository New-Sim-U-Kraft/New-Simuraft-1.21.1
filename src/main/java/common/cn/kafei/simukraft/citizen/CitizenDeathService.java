package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import common.cn.kafei.simukraft.building.ResidentialBedPoiService;
import common.cn.kafei.simukraft.building.controlbox.ResidentialControlBoxService;
import common.cn.kafei.simukraft.building.controlbox.ResidentialControlBoxView;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobAssignmentService;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxViewUpdatePacket;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

@SuppressWarnings("null")
public final class CitizenDeathService {
    private CitizenDeathService() {
    }

    public static void handleDeath(ServerLevel level, CitizenEntity entity) {
        if (level == null || entity == null) {
            return;
        }
        CitizenData data = CitizenService.findCitizen(level, entity.getUUID()).orElse(null);
        if (data == null) {
            return;
        }
        if (data.dead()) {
            return;
        }
        UUID oldHomeId = data.homeId();
        UUID cityId = data.cityId();
        BuilderConstructionService.interruptTask(level, data.uuid(), "citizen_died");
        CitizenNavigationService.stop(level, data.uuid());
        CitizenManager.get(level).markCitizenDead(data.uuid(), level.getDayTime() / 24000L + 1L);
        if (cityId != null) {
            CityJobAssignmentService.invalidate(cityId);
        }
        syncResidentialControlBox(level, oldHomeId);
        level.players().forEach(player -> HudSyncService.syncToPlayer(player, true));
    }

    private static void syncResidentialControlBox(ServerLevel level, UUID oldHomeId) {
        if (oldHomeId == null) {
            return;
        }
        CityPoiData homePoi = CityPoiManager.get(level).getPoi(oldHomeId);
        if (homePoi == null) {
            return;
        }
        PlacedBuildingRecord building = PlacedBuildingService.findByPoi(level, oldHomeId);
        if (building == null) {
            return;
        }
        ResidentialBedPoiService.syncBuildingBounds(level, building);
        BlockPos controlBoxPos = ResidentialBedPoiService.resolveControlBoxPos(level, building);
        if (controlBoxPos == null) {
            return;
        }
        ResidentialControlBoxView view = ResidentialControlBoxService.buildView(level, controlBoxPos);
        PacketDistributor.sendToPlayersNear(
                level,
                null,
                controlBoxPos.getX() + 0.5D,
                controlBoxPos.getY() + 0.5D,
                controlBoxPos.getZ() + 0.5D,
                64.0D,
                ResidentialControlBoxViewUpdatePacket.from(view)
        );
    }
}
