package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.building.BuildingIntegrityService;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.job.CitizenEmploymentService;
import common.cn.kafei.simukraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("null")
public final class IndustrialControlBoxService {
    private static final int COLOR_WORK_POINT = 0xAA33CCFF;
    private static final int COLOR_MACHINE_POINT = 0xAAFFFF33;
    private static final int COLOR_INPUT_CONTAINER = 0xAA00FF66;
    private static final int COLOR_OUTPUT_CONTAINER = 0xAAFF9900;
    private static final int COLOR_CONTAINER = 0xAAFF55FF;

    private IndustrialControlBoxService() {
    }

    public static IndustrialControlBoxView buildView(ServerLevel level, BlockPos boxPos) {
        IndustrialBoxData data = IndustrialBoxManager.get(level).getOrCreate(boxPos);
        PlacedBuildingRecord building = resolveBuilding(level, boxPos);
        IndustrialDefinitionLoader.LoadResult loadResult = IndustrialDefinitionLoader.loadForBuilding(building);
        IndustrialDefinition definition = loadResult.definition();
        synchronizeBoxMetadata(level, data, building, definition);

        CitizenData worker = findAssignedWorker(level, boxPos);
        synchronizeWorkerMetadata(level, worker, definition);
        String statusKey = resolveStatusKey(data, building, loadResult, worker);
        String statusText = data.statusText();
        BuildingIntegrityService.IntegrityPreview integrity = BuildingIntegrityService.preview(level, building);
        List<IndustrialControlBoxView.RecipeEntry> recipes = definition == null ? List.of() : definition.recipes().stream()
                .map(IndustrialControlBoxService::recipeEntry)
                .toList();
        return new IndustrialControlBoxView(
                boxPos.immutable(),
                building != null,
                building != null ? building.displayName() : "",
                loadResult.valid(),
                definition != null ? definition.name() : "",
                statusKey,
                statusText,
                data.running(),
                selectedRecipeId(data, definition),
                worker != null,
                worker != null ? worker.uuid() : null,
                worker != null ? worker.name() : "",
                building != null,
                building != null ? building.minPos().immutable() : BlockPos.ZERO,
                building != null ? building.maxPos().immutable() : BlockPos.ZERO,
                integrity.available(),
                integrity.percent(),
                integrity.repairableBlocks(),
                integrity.manualRepairBlocks(),
                integrity.repairCost(),
                pointMarkers(building, definition),
                recipes
        );
    }

    public static boolean selectRecipe(ServerLevel level, BlockPos boxPos, String recipeId) {
        IndustrialBoxManager manager = IndustrialBoxManager.get(level);
        IndustrialBoxData data = manager.getOrCreate(boxPos);
        PlacedBuildingRecord building = resolveBuilding(level, boxPos);
        IndustrialDefinition definition = IndustrialDefinitionLoader.loadForBuilding(building).definition();
        if (definition == null || definition.recipeById(recipeId) == null) {
            return false;
        }
        IndustrialMachineOperationService.abort(level, data, "recipe_changed");
        data.setSelectedRecipeId(recipeId);
        data.setCurrentStep(0);
        data.setMachineState("");
        data.setStatusKey("gui.simukraft.industrial.status.recipe_selected");
        data.setStatusText("");
        manager.persist(data);
        return true;
    }

    public static boolean toggleRunning(ServerLevel level, BlockPos boxPos) {
        IndustrialBoxManager manager = IndustrialBoxManager.get(level);
        IndustrialBoxData data = manager.getOrCreate(boxPos);
        if (data.running()) {
            IndustrialMachineOperationService.abort(level, data, "manual_pause");
            data.setRunning(false);
            data.setMachineState("");
            data.setStatusKey("gui.simukraft.industrial.status.paused");
            data.setStatusText("");
            CitizenData worker = findAssignedWorker(level, boxPos);
            if (worker != null) {
                common.cn.kafei.simukraft.citizen.CitizenJobVisualService.clearMainHandOverride(worker.uuid());
            }
            manager.persist(data);
            return true;
        }
        PlacedBuildingRecord building = resolveBuilding(level, boxPos);
        IndustrialDefinitionLoader.LoadResult loadResult = IndustrialDefinitionLoader.loadForBuilding(building);
        IndustrialDefinition definition = loadResult.definition();
        CitizenData worker = findAssignedWorker(level, boxPos);
        if (building == null) {
            setStatus(manager, data, "gui.simukraft.industrial.status.no_building", "");
            return false;
        }
        if (!loadResult.valid()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.invalid_definition", String.join(",", loadResult.errors()));
            return false;
        }
        if (worker == null) {
            setStatus(manager, data, "gui.simukraft.industrial.status.no_worker", "");
            return false;
        }
        if (definition.recipeById(selectedRecipeId(data, definition)) == null) {
            setStatus(manager, data, "gui.simukraft.industrial.status.no_recipe", "");
            return false;
        }
        synchronizeBoxMetadata(level, data, building, definition);
        data.setRunning(true);
        data.setCurrentStep(Math.max(0, data.currentStep()));
        data.setStatusKey("gui.simukraft.industrial.status.running");
        data.setStatusText("");
        manager.persist(data);
        return true;
    }

    public static void fireWorker(ServerLevel level, BlockPos boxPos) {
        CitizenData worker = findAssignedWorker(level, boxPos);
        if (worker != null) {
            common.cn.kafei.simukraft.citizen.CitizenJobVisualService.clearMainHandOverride(worker.uuid());
        }
        IndustrialBoxData data = IndustrialBoxManager.get(level).getOrCreate(boxPos);
        IndustrialMachineOperationService.abort(level, data, "industrial_fired");
        CitizenEmploymentService.fireAssigned(level,
                CitizenEmploymentService.workplaceId(IndustrialConstants.HIRE_SOURCE_TYPE, IndustrialConstants.HIRE_ROLE, boxPos),
                IndustrialConstants.HIRE_SOURCE_TYPE,
                IndustrialConstants.HIRE_ROLE,
                boxPos,
                "industrial_fired");
        data.setRunning(false);
        data.setMachineState("");
        data.setStatusKey("gui.simukraft.industrial.status.worker_fired");
        data.setStatusText("");
        IndustrialBoxManager.get(level).persist(data);
    }

    public static void interrupt(ServerLevel level, UUID citizenId, String reason) {
        if (level == null || citizenId == null) {
            return;
        }
        for (IndustrialBoxData data : IndustrialBoxManager.get(level).all()) {
            UUID assigned = CitizenService.findAssignedCitizen(level, CitizenEmploymentService.workplaceId(IndustrialConstants.HIRE_SOURCE_TYPE, IndustrialConstants.HIRE_ROLE, data.boxPos()));
            if (!citizenId.equals(assigned)) {
                continue;
            }
            IndustrialMachineOperationService.abort(level, data, reason);
            data.setRunning(false);
            data.setMachineState("");
            data.setStatusKey("gui.simukraft.industrial.status.interrupted");
            data.setStatusText(reason != null ? reason : "");
            IndustrialBoxManager.get(level).persist(data);
        }
    }

    public static void onRemoved(ServerLevel level, BlockPos boxPos) {
        if (level == null || boxPos == null) {
            return;
        }
        fireWorker(level, boxPos);
        IndustrialBoxManager.get(level).remove(boxPos);
    }

    public static PlacedBuildingRecord resolveBuilding(ServerLevel level, BlockPos boxPos) {
        PlacedBuildingRecord building = PlacedBuildingService.findByContainedPos(level, boxPos);
        if (building == null) {
            return null;
        }
        String category = building.category() != null ? building.category().toLowerCase(Locale.ROOT) : "";
        if (!"industry".equals(category) && !"industrial".equals(category)) {
            return null;
        }
        return building;
    }

    public static CitizenData findAssignedWorker(ServerLevel level, BlockPos boxPos) {
        return CitizenEmploymentService.findAssigned(level, IndustrialConstants.HIRE_SOURCE_TYPE, IndustrialConstants.HIRE_ROLE, boxPos)
                .orElse(null);
    }

    public static void synchronizeAssignedWorkerMetadata(ServerLevel level, BlockPos boxPos) {
        if (level == null || boxPos == null) {
            return;
        }
        PlacedBuildingRecord building = resolveBuilding(level, boxPos);
        IndustrialDefinition definition = IndustrialDefinitionLoader.loadForBuilding(building).definition();
        synchronizeWorkerMetadata(level, findAssignedWorker(level, boxPos), definition);
    }

    public static List<BlockPos> resolveContainerPositions(PlacedBuildingRecord building, IndustrialDefinition definition, String containerId) {
        if (definition == null || containerId == null || containerId.isBlank()) {
            return List.of();
        }
        IndustrialDefinition.ContainerDefinition container = definition.containers().get(containerId);
        if (container == null || !"structure_pos".equalsIgnoreCase(container.type())) {
            return List.of();
        }
        return IndustrialCoordinateResolver.resolvePositions(building, container.positions());
    }

    /**
     * resolveContainerPosition: 解析容器数组中距离 NPC 最近的一个容器坐标。
     */
    public static BlockPos resolveContainerPosition(PlacedBuildingRecord building, IndustrialDefinition definition, String containerId, Vec3 origin) {
        List<BlockPos> positions = resolveContainerPositions(building, definition, containerId);
        if (positions.isEmpty()) {
            return null;
        }
        if (origin == null) {
            return positions.getFirst();
        }
        return positions.stream()
                .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(origin)))
                .orElse(positions.getFirst());
    }

    public static BlockPos resolvePoint(PlacedBuildingRecord building, IndustrialDefinition definition, String pointId, Vec3 origin) {
        if (definition == null || pointId == null || pointId.isBlank()) {
            return null;
        }
        IndustrialDefinition.PointDefinition point = definition.points().get(pointId);
        if (point == null || !"structure_pos".equalsIgnoreCase(point.type())) {
            return null;
        }
        return IndustrialCoordinateResolver.selectPoint(building, point, origin);
    }

    public static boolean isIndustrialControlBox(ServerLevel level, BlockPos pos) {
        return level != null && pos != null && level.isLoaded(pos) && level.getBlockState(pos).is(ModBlocks.INDUSTRIAL_CONTROL_BOX.get());
    }

    private static void synchronizeBoxMetadata(ServerLevel level, IndustrialBoxData data, PlacedBuildingRecord building, IndustrialDefinition definition) {
        if (data == null) {
            return;
        }
        boolean changed = false;
        if (building != null && !building.buildingId().toString().equals(data.buildingId())) {
            IndustrialMachineOperationService.abort(level, data, "building_changed");
            data.setBuildingId(building.buildingId().toString());
            data.setSpawnEntityDone(false);
            changed = true;
        }
        if (definition != null) {
            if (!definition.id().equals(data.definitionId())) {
                IndustrialMachineOperationService.abort(level, data, "definition_changed");
                data.setDefinitionId(definition.id());
                data.setSpawnEntityDone(false);
                changed = true;
            }
            if (data.selectedRecipeId().isBlank() || definition.recipeById(data.selectedRecipeId()) == null) {
                data.setSelectedRecipeId(definition.defaultRecipeId());
                data.setCurrentStep(0);
                changed = true;
            }
        }
        if (changed && level != null) {
            IndustrialBoxManager.get(level).persist(data);
        }
    }

    private static void synchronizeWorkerMetadata(ServerLevel level, CitizenData worker, IndustrialDefinition definition) {
        if (level == null || worker == null || definition == null || definition.jobType() == null || definition.jobType().isBlank()) {
            return;
        }
        String jobType = definition.jobType().trim();
        if (jobType.equals(worker.jobId())) {
            return;
        }
        worker.setJobIdRaw(jobType);
        CitizenService.save(level, worker.uuid());
    }

    private static String selectedRecipeId(IndustrialBoxData data, IndustrialDefinition definition) {
        if (definition == null) {
            return data.selectedRecipeId();
        }
        IndustrialDefinition.RecipeDefinition recipe = definition.recipeById(data.selectedRecipeId());
        return recipe != null ? recipe.id() : definition.defaultRecipeId();
    }

    private static String resolveStatusKey(IndustrialBoxData data, PlacedBuildingRecord building, IndustrialDefinitionLoader.LoadResult loadResult, CitizenData worker) {
        if (building == null) {
            return "gui.simukraft.industrial.status.no_building";
        }
        if (!loadResult.valid()) {
            return "gui.simukraft.industrial.status.invalid_definition";
        }
        if (worker == null) {
            return "gui.simukraft.industrial.status.no_worker";
        }
        if (!data.statusKey().isBlank()) {
            return data.statusKey();
        }
        return data.running() ? "gui.simukraft.industrial.status.running" : "gui.simukraft.industrial.status.idle";
    }

    private static IndustrialControlBoxView.RecipeEntry recipeEntry(IndustrialDefinition.RecipeDefinition recipe) {
        return new IndustrialControlBoxView.RecipeEntry(
                recipe.id(),
                recipe.name(),
                recipe.inputs().stream()
                        .map(input -> new IndustrialControlBoxView.ItemEntry(input.itemId(), input.potionId(), input.count()))
                        .toList(),
                recipe.outputs().stream()
                        .map(output -> new IndustrialControlBoxView.ItemEntry(output.itemId(), output.potionId(), output.baseAmount()))
                        .toList()
        );
    }

    private static List<IndustrialControlBoxView.PointMarker> pointMarkers(PlacedBuildingRecord building, IndustrialDefinition definition) {
        if (building == null || definition == null) {
            return List.of();
        }
        List<IndustrialControlBoxView.PointMarker> markers = new ArrayList<>();
        for (Map.Entry<String, IndustrialDefinition.PointDefinition> entry : definition.points().entrySet()) {
            IndustrialDefinition.PointDefinition point = entry.getValue();
            if (point == null || !"structure_pos".equalsIgnoreCase(point.type())) {
                continue;
            }
            int color = pointColor(entry.getKey());
            IndustrialCoordinateResolver.resolvePositions(building, point.positions()).forEach(pos ->
                    markers.add(new IndustrialControlBoxView.PointMarker(entry.getKey(), "point", pos, color)));
        }
        for (Map.Entry<String, IndustrialDefinition.ContainerDefinition> entry : definition.containers().entrySet()) {
            IndustrialDefinition.ContainerDefinition container = entry.getValue();
            if (container == null || !"structure_pos".equalsIgnoreCase(container.type())) {
                continue;
            }
            int color = containerColor(entry.getKey());
            IndustrialCoordinateResolver.resolvePositions(building, container.positions()).forEach(pos ->
                    markers.add(new IndustrialControlBoxView.PointMarker(entry.getKey(), "container", pos, color)));
        }
        return List.copyOf(markers);
    }

    private static int pointColor(String id) {
        String normalized = id != null ? id.toLowerCase(Locale.ROOT) : "";
        if (normalized.contains("machine")) {
            return COLOR_MACHINE_POINT;
        }
        return COLOR_WORK_POINT;
    }

    private static int containerColor(String id) {
        String normalized = id != null ? id.toLowerCase(Locale.ROOT) : "";
        if (normalized.contains("input")) {
            return COLOR_INPUT_CONTAINER;
        }
        if (normalized.contains("output")) {
            return COLOR_OUTPUT_CONTAINER;
        }
        return COLOR_CONTAINER;
    }

    private static void setStatus(IndustrialBoxManager manager, IndustrialBoxData data, String statusKey, String statusText) {
        data.setRunning(false);
        data.setStatusKey(statusKey);
        data.setStatusText(statusText);
        manager.persist(data);
    }
}
