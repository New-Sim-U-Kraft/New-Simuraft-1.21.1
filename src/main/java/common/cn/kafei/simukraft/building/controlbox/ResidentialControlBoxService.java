package common.cn.kafei.simukraft.building.controlbox;

import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.building.BuildingPoiInstance;
import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("null")
public final class ResidentialControlBoxService {
    private ResidentialControlBoxService() {
    }

    public static ResidentialControlBoxView buildView(ServerLevel level, BlockPos controlBoxPos) {
        PlacedBuildingRecord building = resolveBuilding(level, controlBoxPos);
        List<CityPoiData> bedPois = resolveBedPois(level, building);
        int capacity = bedPois.size();
        List<ResidentialControlBoxView.ResidentEntry> residents = resolveResidents(level, bedPois);
        List<BlockPos> residentialPoiPositions = bedPois.stream()
                .map(poi -> poi.pos().immutable())
                .distinct()
                .toList();
        String buildingName = building != null && !building.displayName().isBlank()
                ? building.displayName()
                : "gui.residential_control_box.unknown_building";
        BlockPos min = building != null ? building.minPos() : BlockPos.ZERO;
        BlockPos max = building != null ? building.maxPos() : BlockPos.ZERO;
        return new ResidentialControlBoxView(
                controlBoxPos.immutable(),
                buildingName,
                "gui.residential_control_box.building_type",
                residents.size(),
                capacity,
                residents,
                building != null,
                min,
                max,
                residentialPoiPositions
        );
    }

    public static PlacedBuildingRecord findBuilding(ServerLevel level, BlockPos controlBoxPos) {
        return resolveBuilding(level, controlBoxPos);
    }

    private static PlacedBuildingRecord resolveBuilding(ServerLevel level, BlockPos controlBoxPos) {
        PlacedBuildingRecord byControlBoxPoi = PlacedBuildingService.findByPoiPos(level, controlBoxPos);
        if (byControlBoxPoi != null) {
            return byControlBoxPoi;
        }
        PlacedBuildingRecord byRecordedControlBox = findByRecordedControlBox(level, controlBoxPos);
        if (byRecordedControlBox != null) {
            return byRecordedControlBox;
        }
        return PlacedBuildingService.findByContainedPos(level, controlBoxPos);
    }

    private static PlacedBuildingRecord findByRecordedControlBox(ServerLevel level, BlockPos controlBoxPos) {
        for (PlacedBuildingRecord building : PlacedBuildingService.getBuildings(level)) {
            boolean containsControlBoxBlock = building.blocks().stream()
                    .anyMatch(block -> controlBoxPos.equals(block.relativePos()) && block.state().is(ModBlocks.RESIDENTIAL_CONTROL_BOX.get()));
            if (containsControlBoxBlock) {
                return building;
            }
        }
        return null;
    }

    private static List<CityPoiData> resolveBedPois(ServerLevel level, PlacedBuildingRecord building) {
        if (building == null) {
            return List.of();
        }
        CityPoiManager manager = CityPoiManager.get(level);
        List<CityPoiData> registeredPois = building.poiInstances().stream()
                .filter(instance -> instance.poiType() == CityPoiType.RESIDENTIAL)
                .map(instance -> manager.getPoiAt(instance.worldPos()))
                .filter(registeredPoi -> registeredPoi != null && registeredPoi.active() && registeredPoi.type() == CityPoiType.RESIDENTIAL && isRedBedHead(level.getBlockState(registeredPoi.pos())))
                .toList();
        if (!registeredPois.isEmpty()) {
            return registeredPois;
        }
        return repairMissingBedPois(level, building, manager);
    }

    private static List<CityPoiData> repairMissingBedPois(ServerLevel level, PlacedBuildingRecord building, CityPoiManager manager) {
        if (building.cityId() == null) {
            return List.of();
        }
        List<BuildingPoiInstance> bedPoiInstances = BuilderConstructionService.resolveResidentialBedPois(building);
        if (bedPoiInstances.isEmpty()) {
            return List.of();
        }
        List<CityPoiData> repaired = bedPoiInstances.stream()
                .map(instance -> manager.registerPoi(stablePoiId(instance), building.cityId(), instance.worldPos(), CityPoiType.RESIDENTIAL, instance.capacity()))
                .toList();
        PlacedBuildingService.register(level, new PlacedBuildingRecord(
                building.buildingId(),
                building.cityId(),
                building.dimensionId(),
                building.category(),
                building.buildingFileName(),
                building.displayName(),
                building.amount(),
                building.structureFileName(),
                building.facing(),
                building.worldOrigin(),
                building.structureAnchor(),
                building.minPos(),
                building.maxPos(),
                building.completedAt(),
                building.blocks(),
                building.poiDefinitions(),
                mergePoiInstances(building.poiInstances(), bedPoiInstances)
        ));
        return repaired;
    }

    private static List<BuildingPoiInstance> mergePoiInstances(List<BuildingPoiInstance> existing, List<BuildingPoiInstance> additions) {
        java.util.LinkedHashMap<String, BuildingPoiInstance> merged = new java.util.LinkedHashMap<>();
        existing.forEach(instance -> merged.put(instance.key(), instance));
        additions.forEach(instance -> merged.putIfAbsent(instance.key(), instance));
        return List.copyOf(merged.values());
    }

    private static UUID stablePoiId(BuildingPoiInstance instance) {
        try {
            return UUID.fromString(instance.key());
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes((instance.poiType().name() + "@" + instance.worldPos().toShortString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static List<ResidentialControlBoxView.ResidentEntry> resolveResidents(ServerLevel level, List<CityPoiData> bedPois) {
        Set<UUID> homePoiIds = bedPois.stream().map(CityPoiData::poiId).collect(java.util.stream.Collectors.toSet());
        if (homePoiIds.isEmpty()) {
            return List.of();
        }
        return CitizenManager.get(level).allCitizens().stream()
                .filter(citizen -> !citizen.dead())
                .filter(citizen -> homePoiIds.contains(citizen.homeId()))
                .sorted(Comparator.comparing(citizen -> safeName(citizen.name())))
                .map(citizen -> new ResidentialControlBoxView.ResidentEntry(citizen.uuid(), safeName(citizen.name())))
                .toList();
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "entity.simukraft.citizen" : name;
    }

    private static boolean isRedBedHead(BlockState state) {
        return state.is(Blocks.RED_BED)
                && (!state.hasProperty(BlockStateProperties.BED_PART)
                || state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }
}
