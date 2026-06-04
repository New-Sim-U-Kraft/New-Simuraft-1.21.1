package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.CitizenJobVisualService;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenSkillSnapshot;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class IndustrialWorkService {
    private static final ConcurrentMap<String, LevelRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final long IDLE_RETRY_TICKS = 40L;
    private static final long MOVE_RETRY_TICKS = 8L;

    private IndustrialWorkService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        IndustrialBoxManager manager = IndustrialBoxManager.get(level);
        LevelRuntime runtime = runtime(level);
        long gameTime = level.getGameTime();
        for (IndustrialBoxData data : manager.all()) {
            BoxRuntime boxRuntime = runtime.boxes.computeIfAbsent(data.boxPos(), ignored -> new BoxRuntime());
            if (!data.running()) {
                boxRuntime.reset();
                continue;
            }
            if (gameTime < boxRuntime.nextTick) {
                continue;
            }
            tickBox(level, manager, data, boxRuntime, gameTime);
        }
    }

    public static void flush(ServerLevel level) {
        if (level != null) {
            IndustrialBoxManager.get(level).saveToSqlite(level);
        }
    }

    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server).toLowerCase(Locale.ROOT);
        RUNTIMES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    private static void tickBox(ServerLevel level, IndustrialBoxManager manager, IndustrialBoxData data, BoxRuntime boxRuntime, long gameTime) {
        if (!IndustrialControlBoxService.isIndustrialControlBox(level, data.boxPos())) {
            manager.remove(data.boxPos());
            boxRuntime.reset();
            return;
        }
        PlacedBuildingRecord building = IndustrialControlBoxService.resolveBuilding(level, data.boxPos());
        IndustrialDefinitionLoader.LoadResult loadResult = IndustrialDefinitionLoader.loadForBuilding(building);
        IndustrialDefinition definition = loadResult.definition();
        CitizenData worker = IndustrialControlBoxService.findAssignedWorker(level, data.boxPos());
        if (building == null) {
            pause(manager, data, boxRuntime, "gui.simukraft.industrial.status.no_building", "");
            return;
        }
        if (!loadResult.valid() || definition == null) {
            pause(manager, data, boxRuntime, "gui.simukraft.industrial.status.invalid_definition", String.join(",", loadResult.errors()));
            return;
        }
        if (worker == null) {
            pause(manager, data, boxRuntime, "gui.simukraft.industrial.status.no_worker", "");
            return;
        }
        IndustrialEntitySpawnService.ensureSpawned(level, manager, data, building, definition);
        if (CitizenHomeRestService.isRestTime(level)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.resting", "");
            CitizenJobVisualService.clearMainHandOverride(worker.uuid());
            boxRuntime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, worker.uuid());
        if (entity == null) {
            boxRuntime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        IndustrialDefinition.RecipeDefinition recipe = definition.recipeById(data.selectedRecipeId());
        if (recipe == null || recipe.steps().isEmpty()) {
            pause(manager, data, boxRuntime, "gui.simukraft.industrial.status.no_recipe", "");
            return;
        }
        if (data.currentStep() >= recipe.steps().size()) {
            data.setCurrentStep(0);
            manager.persist(data);
        }
        IndustrialDefinition.StepDefinition step = recipe.steps().get(data.currentStep());
        StepResult result = executeStep(level, manager, data, boxRuntime, building, definition, recipe, worker, entity, step, gameTime);
        if (result == StepResult.PROGRESSED) {
            advanceStep(manager, data, recipe, boxRuntime);
            boxRuntime.nextTick = gameTime + 1L;
        } else if (result == StepResult.WAITING_MOVE) {
            boxRuntime.nextTick = gameTime + MOVE_RETRY_TICKS;
        } else if (result == StepResult.WAITING_RETRY) {
            boxRuntime.nextTick = gameTime + IDLE_RETRY_TICKS;
        }
    }

    private static StepResult executeStep(ServerLevel level,
                                          IndustrialBoxManager manager,
                                          IndustrialBoxData data,
                                          BoxRuntime boxRuntime,
                                          PlacedBuildingRecord building,
                                          IndustrialDefinition definition,
                                          IndustrialDefinition.RecipeDefinition recipe,
                                          CitizenData worker,
                                          CitizenEntity entity,
                                          IndustrialDefinition.StepDefinition step,
                                          long gameTime) {
        String type = step.type().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "set_held_item" -> {
                String itemId = !step.item().isBlank() ? step.item() : recipe.effectiveHeldItem(definition.heldItem());
                CitizenJobVisualService.setMainHandOverride(worker.uuid(), IndustrialInventoryService.stackForItem(itemId, 1));
                yield StepResult.PROGRESSED;
            }
            case "move_to" -> moveTo(level, data, boxRuntime, building, definition, worker, entity, step);
            case "move_to_container", "move_to_chest" -> moveToContainer(level, data, boxRuntime, building, definition, worker, entity, step);
            case "look_at" -> lookAt(building, definition, entity, step);
            case "look_at_container", "look_at_chest" -> lookAtContainer(level, data, building, definition, entity, step);
            case "require_inputs" -> requireInputs(level, manager, data, building, definition, recipe, step);
            case "require_output_space" -> requireOutputSpace(level, manager, data, building, definition, recipe, step);
            case "use_item" -> useItem(entity, boxRuntime, step, gameTime);
            case "craft_recipe" -> craftRecipe(level, manager, data, building, definition, recipe, worker, step, false);
            case "craft_available_recipe", "craft_all_recipe" -> craftRecipe(level, manager, data, building, definition, recipe, worker, step, true);
            case "real_machine_recipe" -> realMachineRecipe(level, manager, data, building, definition, recipe, worker, entity, step, gameTime);
            case "inspect_container", "open_container" -> inspectContainer(level, manager, data, boxRuntime, building, definition, step, gameTime);
            case "breed_entities", "breed_animals" -> entityAction(manager, data,
                    IndustrialEntityActionService.breed(level, building, definition, step),
                    "gui.simukraft.industrial.status.breeding");
            case "slaughter_entities", "slaughter_animals" -> entityAction(manager, data,
                    IndustrialEntityActionService.slaughter(level, building, definition, step, entity),
                    "gui.simukraft.industrial.status.slaughtering");
            case "require_drops", "require_drop_items", "has_drops" -> entityAction(manager, data,
                    IndustrialEntityActionService.requireDrops(level, building, definition, step, entity),
                    "gui.simukraft.industrial.status.collecting_drops");
            case "collect_drops" -> entityAction(manager, data,
                    IndustrialEntityActionService.collectDrops(level, building, definition, step, entity),
                    "gui.simukraft.industrial.status.collecting_drops");
            case "place_block", "set_block" -> blockAction(manager, data,
                    IndustrialBlockActionService.placeBlock(level, building, definition, step, entity),
                    "gui.simukraft.industrial.status.placing_block", step);
            case "place_fluid", "place_liquid" -> blockAction(manager, data,
                    IndustrialBlockActionService.placeFluid(level, building, definition, step, entity),
                    "gui.simukraft.industrial.status.placing_fluid", step);
            case "destroy_block", "break_block", "remove_block" -> blockAction(manager, data,
                    IndustrialBlockActionService.destroyBlock(level, building, definition, step, entity),
                    "gui.simukraft.industrial.status.destroying_block", step);
            case "require_block", "wait_for_block", "find_block", "check_block" -> requireBlock(manager, data,
                    IndustrialBlockActionService.requireBlock(level, building, definition, step), step);
            case "insert_item", "store_item", "put_item" -> insertItem(level, manager, data, boxRuntime, building, definition, step, gameTime);
            case "fill_item", "fill_slot", "refill_item", "refill_slot" -> fillItem(manager, data, entity, step,
                    IndustrialItemFillService.fill(level, building, definition, step, entity.position()));
            case "set_status" -> {
                setStatus(manager, data,
                        !step.statusKey().isBlank() ? step.statusKey() : "gui.simukraft.industrial.status.running",
                        step.statusText());
                yield StepResult.PROGRESSED;
            }
            default -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.invalid_step", type);
                yield StepResult.WAITING_RETRY;
            }
        };
    }

    private static StepResult fillItem(IndustrialBoxManager manager,
                                       IndustrialBoxData data,
                                       CitizenEntity entity,
                                       IndustrialDefinition.StepDefinition step,
                                       IndustrialItemFillService.ActionResult result) {
        return switch (result) {
            case SUCCESS -> {
                if (step.swing()) {
                    entity.triggerWorkSwing(InteractionHand.MAIN_HAND);
                }
                setStatus(manager, data, "gui.simukraft.industrial.status.running", "");
                yield StepResult.PROGRESSED;
            }
            case MISSING_TARGET -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_point", step.point());
                yield StepResult.WAITING_RETRY;
            }
            case INVALID_STEP -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.invalid_step", step.type());
                yield StepResult.WAITING_RETRY;
            }
            case MISSING_INPUTS -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", step.item());
                yield StepResult.WAITING_RETRY;
            }
            case TARGET_BLOCKED -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.machine_input_blocked", step.point());
                yield StepResult.WAITING_RETRY;
            }
        };
    }

    private static StepResult requireBlock(IndustrialBoxManager manager,
                                           IndustrialBoxData data,
                                           IndustrialBlockActionService.ActionResult result,
                                           IndustrialDefinition.StepDefinition step) {
        return switch (result) {
            case SUCCESS -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.running", "");
                yield StepResult.PROGRESSED;
            }
            case MISSING_TARGET -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_point", step.point());
                yield StepResult.WAITING_RETRY;
            }
            case INVALID_BLOCK, INVALID_FLUID -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.invalid_step", step.type());
                yield StepResult.WAITING_RETRY;
            }
            case MISSING_INPUTS -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", "");
                yield StepResult.WAITING_RETRY;
            }
            case BLOCKED -> {
                String detail = !step.statusText().isBlank() ? step.statusText() : step.block();
                setStatus(manager, data, "gui.simukraft.industrial.status.waiting_block", detail);
                yield StepResult.WAITING_RETRY;
            }
        };
    }

    private static StepResult insertItem(ServerLevel level,
                                         IndustrialBoxManager manager,
                                         IndustrialBoxData data,
                                         BoxRuntime boxRuntime,
                                         PlacedBuildingRecord building,
                                         IndustrialDefinition definition,
                                         IndustrialDefinition.StepDefinition step,
                                         long gameTime) {
        String containerId = containerName(step.container(), step.output(), "output");
        List<BlockPos> containers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerId);
        ItemStack stack = IndustrialInventoryService.stackForItem(step.item(), Math.max(1, step.count()));
        if (containers.isEmpty()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_container", containerId);
            return StepResult.WAITING_RETRY;
        }
        if (stack.isEmpty()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.invalid_step", step.type());
            return StepResult.WAITING_RETRY;
        }
        int stepKey = Objects.hash(data.currentStep(), step.type(), containerId, step.item(), step.count());
        if (boxRuntime.activeStep != stepKey) {
            boxRuntime.activeStep = stepKey;
            boxRuntime.stepStartedAt = gameTime;
            setContainersOpen(level, containers, true);
            setStatus(manager, data, "gui.simukraft.industrial.status.inspecting_container", "");
            return StepResult.WAITING;
        }
        int openTicks = step.ticks() > 1 ? step.ticks() : 12;
        if (gameTime - boxRuntime.stepStartedAt < openTicks) {
            return StepResult.WAITING;
        }
        if (!IndustrialInventoryService.hasOutputSpace(level, containers, List.of(stack.copy()))
                || !IndustrialInventoryService.insertItem(level, containers, stack)) {
            setContainersOpen(level, containers, false);
            setStatus(manager, data, "gui.simukraft.industrial.status.output_full", "");
            return StepResult.WAITING_RETRY;
        }
        setContainersOpen(level, containers, false);
        setStatus(manager, data, "gui.simukraft.industrial.status.running", "");
        return StepResult.PROGRESSED;
    }

    private static StepResult blockAction(IndustrialBoxManager manager,
                                          IndustrialBoxData data,
                                          IndustrialBlockActionService.ActionResult result,
                                          String successStatusKey,
                                          IndustrialDefinition.StepDefinition step) {
        return switch (result) {
            case SUCCESS -> {
                setStatus(manager, data, successStatusKey, "");
                yield StepResult.PROGRESSED;
            }
            case MISSING_TARGET -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_point", step.point());
                yield StepResult.WAITING_RETRY;
            }
            case INVALID_BLOCK, INVALID_FLUID -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.invalid_step", step.type());
                yield StepResult.WAITING_RETRY;
            }
            case MISSING_INPUTS -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", "");
                yield StepResult.WAITING_RETRY;
            }
            case BLOCKED -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.block_action_blocked", step.type());
                yield StepResult.WAITING_RETRY;
            }
        };
    }

    private static StepResult moveTo(ServerLevel level,
                                     IndustrialBoxData data,
                                     BoxRuntime boxRuntime,
                                     PlacedBuildingRecord building,
                                     IndustrialDefinition definition,
                                     CitizenData worker,
                                     CitizenEntity entity,
                                     IndustrialDefinition.StepDefinition step) {
        BlockPos target = IndustrialControlBoxService.resolvePoint(building, definition, step.point(), entity.position());
        if (target == null) {
            setStatus(IndustrialBoxManager.get(level), data, "gui.simukraft.industrial.status.missing_point", step.point());
            return StepResult.WAITING_RETRY;
        }
        double range = Math.max(0.2D, step.range());
        Vec3 targetCenter = Vec3.atBottomCenterOf(target);
        if (entity.position().distanceToSqr(targetCenter) <= range * range) {
            CitizenNavigationService.stop(level, worker.uuid());
            return StepResult.PROGRESSED;
        }
        setStatus(IndustrialBoxManager.get(level), data, "gui.simukraft.industrial.status.moving", step.point());
        CitizenNavigationService.requestMove(level, worker.uuid(), targetCenter, MovementIntent.WORK);
        boxRuntime.resetStep(data.currentStep());
        return StepResult.WAITING_MOVE;
    }

    /**
     * moveToContainer: 让 NPC 走到指定容器旁边的可站立格。
     */
    private static StepResult moveToContainer(ServerLevel level,
                                              IndustrialBoxData data,
                                              BoxRuntime boxRuntime,
                                              PlacedBuildingRecord building,
                                              IndustrialDefinition definition,
                                              CitizenData worker,
                                              CitizenEntity entity,
                                              IndustrialDefinition.StepDefinition step) {
        String containerId = targetContainerName(step, "input");
        List<BlockPos> containers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerId);
        if (containers.isEmpty()) {
            setStatus(IndustrialBoxManager.get(level), data, "gui.simukraft.industrial.status.missing_container", containerId);
            return StepResult.WAITING_RETRY;
        }
        double range = Math.max(0.2D, step.range());
        ContainerMoveTarget target = containerMoveTarget(level, containers, entity.position());
        double arrivalRange = target.hasStandTarget() ? Math.min(range, 0.65D) : range;
        if (entity.position().distanceToSqr(target.position()) <= arrivalRange * arrivalRange
                || (!target.hasStandTarget() && isNearContainer(entity.position(), containers, range))) {
            CitizenNavigationService.stop(level, worker.uuid());
            return StepResult.PROGRESSED;
        }
        setStatus(IndustrialBoxManager.get(level), data, "gui.simukraft.industrial.status.moving", containerId);
        CitizenNavigationService.requestMove(level, worker.uuid(), target.position(), MovementIntent.WORK);
        boxRuntime.resetStep(data.currentStep());
        return StepResult.WAITING_MOVE;
    }

    private static StepResult lookAt(PlacedBuildingRecord building, IndustrialDefinition definition, CitizenEntity entity, IndustrialDefinition.StepDefinition step) {
        BlockPos target = IndustrialControlBoxService.resolvePoint(building, definition, step.point(), entity.position());
        if (target == null) {
            return StepResult.WAITING_RETRY;
        }
        Vec3 center = Vec3.atCenterOf(target);
        entity.getLookControl().setLookAt(center.x, center.y, center.z);
        return StepResult.PROGRESSED;
    }

    /**
     * lookAtContainer: 让 NPC 面朝指定容器，配合开箱/放入产物表现。
     */
    private static StepResult lookAtContainer(ServerLevel level,
                                              IndustrialBoxData data,
                                              PlacedBuildingRecord building,
                                              IndustrialDefinition definition,
                                              CitizenEntity entity,
                                              IndustrialDefinition.StepDefinition step) {
        String containerId = targetContainerName(step, "input");
        BlockPos target = IndustrialControlBoxService.resolveContainerPosition(building, definition, containerId, entity.position());
        if (target == null) {
            setStatus(IndustrialBoxManager.get(level), data, "gui.simukraft.industrial.status.missing_container", containerId);
            return StepResult.WAITING_RETRY;
        }
        Vec3 center = Vec3.atCenterOf(target);
        entity.getLookControl().setLookAt(center.x, center.y, center.z);
        return StepResult.PROGRESSED;
    }

    private static StepResult requireInputs(ServerLevel level,
                                            IndustrialBoxManager manager,
                                            IndustrialBoxData data,
                                            PlacedBuildingRecord building,
                                            IndustrialDefinition definition,
                                            IndustrialDefinition.RecipeDefinition recipe,
                                            IndustrialDefinition.StepDefinition step) {
        List<BlockPos> containers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.container(), step.input(), "input"));
        if (containers.isEmpty() || !IndustrialInventoryService.hasInputs(level, containers, recipe.inputs())) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", "");
            return StepResult.WAITING_RETRY;
        }
        return StepResult.PROGRESSED;
    }

    private static StepResult requireOutputSpace(ServerLevel level,
                                                 IndustrialBoxManager manager,
                                                 IndustrialBoxData data,
                                                 PlacedBuildingRecord building,
                                                 IndustrialDefinition definition,
                                                 IndustrialDefinition.RecipeDefinition recipe,
                                                 IndustrialDefinition.StepDefinition step) {
        List<BlockPos> containers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.container(), step.output(), "output"));
        List<ItemStack> worstCaseOutputs = recipe.outputs().stream()
                .map(output -> IndustrialInventoryService.stackForItem(output.itemId(), output.potionId(), output.baseAmount() + Math.max(0, output.randomRange())))
                .filter(stack -> !stack.isEmpty())
                .toList();
        if (containers.isEmpty() || !IndustrialInventoryService.hasOutputSpace(level, containers, worstCaseOutputs)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.output_full", "");
            return StepResult.WAITING_RETRY;
        }
        return StepResult.PROGRESSED;
    }

    private static StepResult useItem(CitizenEntity entity, BoxRuntime boxRuntime, IndustrialDefinition.StepDefinition step, long gameTime) {
        if (boxRuntime.activeStep != boxRuntimeStepKey(entity, step)) {
            boxRuntime.activeStep = boxRuntimeStepKey(entity, step);
            boxRuntime.stepStartedAt = gameTime;
            boxRuntime.swingDone = false;
        }
        if (step.swing() && !boxRuntime.swingDone) {
            entity.triggerWorkSwing(InteractionHand.MAIN_HAND);
            boxRuntime.swingDone = true;
        }
        return gameTime - boxRuntime.stepStartedAt >= Math.max(1, step.ticks()) ? StepResult.PROGRESSED : StepResult.WAITING;
    }

    private static StepResult inspectContainer(ServerLevel level,
                                               IndustrialBoxManager manager,
                                               IndustrialBoxData data,
                                               BoxRuntime boxRuntime,
                                               PlacedBuildingRecord building,
                                               IndustrialDefinition definition,
                                               IndustrialDefinition.StepDefinition step,
                                               long gameTime) {
        List<BlockPos> containers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.container(), step.input(), "input"));
        if (containers.isEmpty()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_container", "");
            return StepResult.WAITING_RETRY;
        }
        int stepKey = Objects.hash(step.type(), step.container(), step.input(), step.ticks());
        if (boxRuntime.activeStep != stepKey) {
            boxRuntime.activeStep = stepKey;
            boxRuntime.stepStartedAt = gameTime;
            setContainersOpen(level, containers, true);
            setStatus(manager, data, "gui.simukraft.industrial.status.inspecting_container", "");
            return StepResult.WAITING;
        }
        if (gameTime - boxRuntime.stepStartedAt < Math.max(1, step.ticks())) {
            return StepResult.WAITING;
        }
        setContainersOpen(level, containers, false);
        return StepResult.PROGRESSED;
    }

    private static StepResult craftRecipe(ServerLevel level,
                                          IndustrialBoxManager manager,
                                          IndustrialBoxData data,
                                          PlacedBuildingRecord building,
                                          IndustrialDefinition definition,
                                          IndustrialDefinition.RecipeDefinition recipe,
                                          CitizenData worker,
                                          IndustrialDefinition.StepDefinition step,
                                          boolean craftAllAvailable) {
        List<BlockPos> inputContainers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.input(), step.container(), "input"));
        List<BlockPos> outputContainers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.output(), step.container(), "output"));
        CitizenSkillSnapshot skill = CitizenLevelService.snapshot(worker, CityJobType.INDUSTRIAL_WORKER);
        double multiplier = 1.0D + Math.max(0, skill.level() - 1) * 0.05D;
        IndustrialDefinition.RecipeDefinition effectiveRecipe = stepRecipe(recipe, step);
        boolean crafted = craftAllAvailable
                ? IndustrialInventoryService.craftAvailableRecipe(level, inputContainers, outputContainers, effectiveRecipe, multiplier, level.random)
                : IndustrialInventoryService.craftRecipe(level, inputContainers, outputContainers, effectiveRecipe, multiplier, level.random);
        if (!crafted) {
            setStatus(manager, data, "gui.simukraft.industrial.status.craft_blocked", "");
            return StepResult.WAITING_RETRY;
        }
        CitizenLevelService.addExperience(level, worker.uuid(), CityJobType.INDUSTRIAL_WORKER, 2);
        setCitizenStatus(level, worker, "gui.simukraft.industrial.status.running", "");
        setStatus(manager, data, "gui.simukraft.industrial.status.running", "");
        return StepResult.PROGRESSED;
    }

    private static IndustrialDefinition.RecipeDefinition stepRecipe(IndustrialDefinition.RecipeDefinition recipe, IndustrialDefinition.StepDefinition step) {
        if (!step.inputsOverride() && !step.outputsOverride()) {
            return recipe;
        }
        return new IndustrialDefinition.RecipeDefinition(
                recipe.id(),
                recipe.name(),
                recipe.heldItem(),
                step.inputsOverride() ? step.inputs() : recipe.inputs(),
                step.outputsOverride() ? step.outputs() : recipe.outputs(),
                recipe.steps()
        );
    }

    private static StepResult realMachineRecipe(ServerLevel level,
                                                IndustrialBoxManager manager,
                                                IndustrialBoxData data,
                                                PlacedBuildingRecord building,
                                                IndustrialDefinition definition,
                                                IndustrialDefinition.RecipeDefinition recipe,
                                                CitizenData worker,
                                                CitizenEntity entity,
                                                IndustrialDefinition.StepDefinition step,
                                                long gameTime) {
        IndustrialMachineOperationService.Result result = IndustrialMachineOperationService.execute(level, manager, data, building, definition, recipe, worker, entity, step, gameTime);
        return switch (result) {
            case PROGRESSED -> StepResult.PROGRESSED;
            case WAITING -> StepResult.WAITING;
            case WAITING_RETRY -> StepResult.WAITING_RETRY;
            case NEEDS_INPUT -> {
                IndustrialStepRewindService.rewindForMachineInput(manager, data, recipe, step);
                yield StepResult.WAITING_RETRY;
            }
        };
    }

    private static StepResult entityAction(IndustrialBoxManager manager, IndustrialBoxData data, IndustrialEntityActionService.ActionResult result, String successStatusKey) {
        return switch (result) {
            case SUCCESS -> {
                setStatus(manager, data, successStatusKey, "");
                yield StepResult.PROGRESSED;
            }
            case MISSING_ENTITIES -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_entities", "");
                yield StepResult.WAITING_RETRY;
            }
            case MISSING_DROPS -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_drops", "");
                yield StepResult.WAITING_RETRY;
            }
            case MISSING_INPUTS -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", "");
                yield StepResult.WAITING_RETRY;
            }
            case OUTPUT_FULL -> {
                setStatus(manager, data, "gui.simukraft.industrial.status.output_full", "");
                yield StepResult.WAITING_RETRY;
            }
        };
    }

    private static void setContainersOpen(ServerLevel level, List<BlockPos> containers, boolean open) {
        for (BlockPos container : containers) {
            if (container == null || !level.isLoaded(container)) {
                continue;
            }
            BlockState state = level.getBlockState(container);
            if (state.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(container, state.setValue(BarrelBlock.OPEN, open), 3);
            }
            level.blockEvent(container, state.getBlock(), 1, open ? 1 : 0);
        }
    }

    private static void advanceStep(IndustrialBoxManager manager, IndustrialBoxData data, IndustrialDefinition.RecipeDefinition recipe, BoxRuntime boxRuntime) {
        int next = data.currentStep() + 1;
        data.setCurrentStep(next >= recipe.steps().size() ? 0 : next);
        data.setMachineState("");
        boxRuntime.reset();
        manager.persist(data);
    }

    private static void pause(IndustrialBoxManager manager, IndustrialBoxData data, BoxRuntime boxRuntime, String statusKey, String statusText) {
        data.setRunning(false);
        data.setMachineState("");
        data.setStatusKey(statusKey);
        data.setStatusText(statusText);
        manager.persist(data);
        boxRuntime.reset();
    }

    private static void setStatus(IndustrialBoxManager manager, IndustrialBoxData data, String statusKey, String statusText) {
        String safeText = statusText != null ? statusText : "";
        if (Objects.equals(data.statusKey(), statusKey) && Objects.equals(data.statusText(), safeText)) {
            return;
        }
        data.setStatusKey(statusKey);
        data.setStatusText(safeText);
        manager.persist(data);
    }

    private static void setCitizenStatus(ServerLevel level, CitizenData worker, String statusKey, String needDetail) {
        if (worker == null) {
            return;
        }
        worker.setWorkStatus(CitizenWorkStatus.WORKING);
        worker.setStatusLabel(statusKey);
        worker.setWorkNeedDetail(needDetail != null ? needDetail : "");
        CitizenService.save(level, worker.uuid());
    }

    private static String containerName(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }

    /**
     * targetContainerName: 解析移动/朝向类步骤中的容器名。
     */
    private static String targetContainerName(IndustrialDefinition.StepDefinition step, String fallback) {
        if (step == null) {
            return fallback;
        }
        if (step.container() != null && !step.container().isBlank()) {
            return step.container();
        }
        if (step.input() != null && !step.input().isBlank()) {
            return step.input();
        }
        if (step.output() != null && !step.output().isBlank()) {
            return step.output();
        }
        return fallback;
    }

    /**
     * isNearContainer: 判断 NPC 是否已经处于任意目标容器的交互距离内。
     */
    private static boolean isNearContainer(Vec3 position, List<BlockPos> containers, double range) {
        if (position == null || containers == null || containers.isEmpty()) {
            return false;
        }
        double maxDistanceSqr = range * range;
        for (BlockPos container : containers) {
            if (distanceToBlockBoxSqr(position, container) <= maxDistanceSqr) {
                return true;
            }
        }
        return false;
    }

    /**
     * containerMoveTarget: 优先让 NPC 走到容器旁可站点，找不到时回退到最近容器中心。
     */
    private static ContainerMoveTarget containerMoveTarget(ServerLevel level, List<BlockPos> containers, Vec3 origin) {
        BlockPos bestStand = null;
        double bestStandDistance = Double.MAX_VALUE;
        BlockPos bestContainer = null;
        double bestContainerDistance = Double.MAX_VALUE;
        for (BlockPos container : containers) {
            double containerDistance = origin != null ? distanceToBlockBoxSqr(origin, container) : 0.0D;
            if (bestContainer == null || containerDistance < bestContainerDistance) {
                bestContainer = container;
                bestContainerDistance = containerDistance;
            }
            BlockPos stand = containerStandTarget(level, container, origin);
            if (stand == null) {
                continue;
            }
            double standDistance = origin != null ? Vec3.atBottomCenterOf(stand).distanceToSqr(origin) : 0.0D;
            if (bestStand == null || standDistance < bestStandDistance) {
                bestStand = stand;
                bestStandDistance = standDistance;
            }
        }
        if (bestStand != null) {
            return new ContainerMoveTarget(Vec3.atBottomCenterOf(bestStand), true);
        }
        return new ContainerMoveTarget(Vec3.atBottomCenterOf(bestContainer), false);
    }

    /**
     * containerStandTarget: 从容器周围向下查找可站立格，不向上搜索屋顶。
     */
    private static BlockPos containerStandTarget(ServerLevel level, BlockPos container, Vec3 origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 1; radius <= 2; radius++) {
            for (int yOffset = 0; yOffset >= -3; yOffset--) {
                for (int xOffset = -radius; xOffset <= radius; xOffset++) {
                    for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                        if (Math.max(Math.abs(xOffset), Math.abs(zOffset)) != radius) {
                            continue;
                        }
                        BlockPos candidate = container.offset(xOffset, yOffset, zOffset);
                        if (!CitizenTeleportService.isSafeLandingPosition(level, candidate)) {
                            continue;
                        }
                        double distance = origin != null ? Vec3.atBottomCenterOf(candidate).distanceToSqr(origin) : 0.0D;
                        if (best == null || distance < bestDistance) {
                            best = candidate.immutable();
                            bestDistance = distance;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * distanceToBlockBoxSqr: 计算实体位置到方块包围盒的最近距离平方。
     */
    private static double distanceToBlockBoxSqr(Vec3 position, BlockPos blockPos) {
        if (position == null || blockPos == null) {
            return Double.MAX_VALUE;
        }
        double dx = axisDistance(position.x, blockPos.getX(), blockPos.getX() + 1.0D);
        double dy = axisDistance(position.y, blockPos.getY(), blockPos.getY() + 1.0D);
        double dz = axisDistance(position.z, blockPos.getZ(), blockPos.getZ() + 1.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * axisDistance: 计算一个坐标轴上点到区间的最近距离。
     */
    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    private static int boxRuntimeStepKey(CitizenEntity entity, IndustrialDefinition.StepDefinition step) {
        return Objects.hash(entity.getUUID(), step.type(), step.ticks(), step.swing());
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(SaveScopedCacheKey.levelKey(level).toLowerCase(Locale.ROOT), ignored -> new LevelRuntime());
    }

    private enum StepResult {
        PROGRESSED,
        WAITING,
        WAITING_MOVE,
        WAITING_RETRY
    }

    private static final class LevelRuntime {
        private final ConcurrentMap<BlockPos, BoxRuntime> boxes = new ConcurrentHashMap<>();
    }

    private static final class BoxRuntime {
        private long nextTick;
        private int activeStep = Integer.MIN_VALUE;
        private long stepStartedAt;
        private boolean swingDone;

        private void reset() {
            activeStep = Integer.MIN_VALUE;
            stepStartedAt = 0L;
            swingDone = false;
        }

        private void resetStep(int currentStep) {
            if (activeStep != currentStep) {
                reset();
            }
        }
    }

    private record ContainerMoveTarget(Vec3 position, boolean hasStandTarget) {
    }

}
