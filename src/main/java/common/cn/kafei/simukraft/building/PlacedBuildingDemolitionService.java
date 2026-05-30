package common.cn.kafei.simukraft.building;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("null")
public final class PlacedBuildingDemolitionService {
    private PlacedBuildingDemolitionService() {
    }

    public static boolean demolish(ServerLevel level, PlacedBuildingRecord building) {
        if (level == null || building == null) {
            return false;
        }
        Set<UUID> deactivatedPois = deactivateBuildingPois(level, building);
        releaseResidents(level, deactivatedPois);
        building.blocks().forEach(block -> {
            BlockPos worldPos = resolveWorldPos(building, block.relativePos());
            if (!PlacedBuildingService.isOccupiedByOtherBuilding(level, building.buildingId(), worldPos)) {
                level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), 3);
            }
        });
        ResidentialBedPoiService.removeRecordedBeds(level, building);
        PlacedBuildingService.unregister(level, building.buildingId());
        return true;
    }

    private static BlockPos resolveWorldPos(PlacedBuildingRecord building, BlockPos storedPos) {
        if (isInside(storedPos, building.minPos(), building.maxPos())) {
            return storedPos;
        }
        return building.worldOrigin().offset(storedPos);
    }

    private static boolean isInside(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= Math.min(min.getX(), max.getX()) && pos.getX() <= Math.max(min.getX(), max.getX())
                && pos.getY() >= Math.min(min.getY(), max.getY()) && pos.getY() <= Math.max(min.getY(), max.getY())
                && pos.getZ() >= Math.min(min.getZ(), max.getZ()) && pos.getZ() <= Math.max(min.getZ(), max.getZ());
    }

    private static Set<UUID> deactivateBuildingPois(ServerLevel level, PlacedBuildingRecord building) {
        CityPoiManager manager = CityPoiManager.get(level);
        Set<UUID> deactivated = new HashSet<>();
        for (BuildingPoiInstance poi : building.poiInstances()) {
            CityPoiData registeredPoi = manager.getPoiAt(poi.worldPos());
            if (registeredPoi != null && manager.deactivatePoi(registeredPoi.poiId())) {
                deactivated.add(registeredPoi.poiId());
            }
        }
        return deactivated;
    }

    private static void releaseResidents(ServerLevel level, Set<UUID> homePoiIds) {
        if (homePoiIds.isEmpty()) {
            return;
        }
        CitizenManager.get(level).allCitizens().stream()
                .filter(citizen -> !citizen.dead())
                .filter(citizen -> homePoiIds.contains(citizen.homeId()))
                .map(CitizenData::uuid)
                .toList()
                .forEach(citizenId -> CitizenService.setHome(level, citizenId, null));
    }
}
