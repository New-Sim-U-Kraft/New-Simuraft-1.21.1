package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings("null")
public final class IndustrialMachineOperationService {
    private static final String DEFAULT_INPUT_CONTAINER = "input";
    private static final String DEFAULT_OUTPUT_CONTAINER = "output";
    private static final List<IndustrialMachineAdapter> BUILTIN_ADAPTERS = List.of(new GenericContainerMachineAdapter());
    private static final CopyOnWriteArrayList<IndustrialMachineAdapter> CUSTOM_ADAPTERS = new CopyOnWriteArrayList<>();

    private IndustrialMachineOperationService() {
    }

    public static void registerAdapter(IndustrialMachineAdapter adapter) {
        if (adapter == null || adapter.id() == null || adapter.id().isBlank()) {
            return;
        }
        CUSTOM_ADAPTERS.removeIf(existing -> adapter.id().equalsIgnoreCase(existing.id()));
        CUSTOM_ADAPTERS.add(adapter);
    }

    public static Result execute(ServerLevel level,
                                 IndustrialBoxManager manager,
                                 IndustrialBoxData data,
                                 PlacedBuildingRecord building,
                                 IndustrialDefinition definition,
                                 IndustrialDefinition.RecipeDefinition recipe,
                                 CitizenData worker,
                                 CitizenEntity entity,
                                 IndustrialDefinition.StepDefinition step,
                                 long gameTime) {
        BlockPos machinePos = IndustrialControlBoxService.resolvePoint(building, definition, step.point(), entity.position());
        if (machinePos == null) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_point", step.point());
            return Result.WAITING_RETRY;
        }
        List<BlockPos> inputContainers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.input(), step.container(), DEFAULT_INPUT_CONTAINER));
        List<BlockPos> outputContainers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.output(), step.container(), DEFAULT_OUTPUT_CONTAINER));
        OutputPolicy outputPolicy = OutputPolicy.from(step.outputPolicy());
        String stepKey = stepKey(data, definition, recipe, step, machinePos, outputPolicy);
        IndustrialMachineState state = IndustrialMachineState.parse(data.machineState());
        if (state == null || !stepKey.equals(state.stepKey())) {
            if (state != null) {
                abort(level, manager, data, "machine_step_changed");
            }
            return start(level, manager, data, building, definition, recipe, worker, entity, step, machinePos, inputContainers, outputContainers, outputPolicy, stepKey, gameTime);
        }
        return tick(level, manager, data, building, definition, recipe, worker, entity, step, machinePos, inputContainers, outputContainers, outputPolicy, state, gameTime);
    }

    public static void abort(ServerLevel level, IndustrialBoxData data, String reason) {
        if (level == null || data == null || data.machineState().isBlank()) {
            return;
        }
        abort(level, IndustrialBoxManager.get(level), data, reason);
    }

    private static void abort(ServerLevel level, IndustrialBoxManager manager, IndustrialBoxData data, String reason) {
        IndustrialMachineState state = IndustrialMachineState.parse(data.machineState());
        if (state != null) {
            IndustrialMachineOperationContext context = resolveContext(level, data, state);
            if (context != null) {
                findAdapter(state.adapterId()).ifPresent(adapter -> adapter.abort(context, reason));
                NeoForge.EVENT_BUS.post(new IndustrialMachineOperationEvent.Abort(context, reason));
            }
        }
        data.setMachineState("");
        manager.persist(data);
    }

    private static Result start(ServerLevel level,
                                IndustrialBoxManager manager,
                                IndustrialBoxData data,
                                PlacedBuildingRecord building,
                                IndustrialDefinition definition,
                                IndustrialDefinition.RecipeDefinition recipe,
                                CitizenData worker,
                                CitizenEntity entity,
                                IndustrialDefinition.StepDefinition step,
                                BlockPos machinePos,
                                List<BlockPos> inputContainers,
                                List<BlockPos> outputContainers,
                                OutputPolicy outputPolicy,
                                String stepKey,
                                long gameTime) {
        List<IndustrialDefinition.ItemRequirement> machineInputs = machineInputs(recipe, step);
        List<IndustrialDefinition.ProductOutput> machineOutputs = machineOutputs(recipe, step);
        if (machineOutputs.isEmpty()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.invalid_step", step.type());
            return Result.WAITING_RETRY;
        }
        if (!machineInputs.isEmpty() && inputContainers.isEmpty()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_container", containerName(step.input(), step.container(), DEFAULT_INPUT_CONTAINER));
            return Result.WAITING_RETRY;
        }
        if (outputPolicy == OutputPolicy.EXTRACT_TO_OUTPUT && outputContainers.isEmpty()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_container", containerName(step.output(), step.container(), DEFAULT_OUTPUT_CONTAINER));
            return Result.WAITING_RETRY;
        }
        if (!level.isLoaded(machinePos)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.machine_missing", step.point());
            return Result.WAITING_RETRY;
        }
        if (!IndustrialInventoryService.hasInputs(level, inputContainers, machineInputs)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", "");
            return Result.WAITING_RETRY;
        }
        IndustrialMachineOperationContext context = new IndustrialMachineOperationContext(level, data, building, definition, recipe, step, worker, entity, machinePos, inputContainers, outputContainers, "");
        IndustrialMachineAdapter adapter = selectAdapter(context);
        if (adapter == null) {
            setStatus(manager, data, "gui.simukraft.industrial.status.machine_no_adapter", step.point());
            return Result.WAITING_RETRY;
        }
        List<ItemStack> inputStacks = inputStacks(machineInputs);
        if (!inputStacks.isEmpty()) {
            if (!adapter.canAcceptInputs(context, inputStacks)) {
                setStatus(manager, data, "gui.simukraft.industrial.status.machine_input_blocked", step.point());
                return Result.WAITING_RETRY;
            }
            List<ItemStack> consumed = consumeInputs(level, inputContainers, machineInputs);
            if (consumed.isEmpty()) {
                setStatus(manager, data, "gui.simukraft.industrial.status.missing_inputs", "");
                return Result.WAITING_RETRY;
            }
            if (!adapter.insertInputs(context, consumed)) {
                rollbackInputs(level, inputContainers, consumed);
                setStatus(manager, data, "gui.simukraft.industrial.status.machine_input_blocked", step.point());
                return Result.WAITING_RETRY;
            }
        }
        if (step.swing()) {
            entity.triggerWorkSwing(InteractionHand.MAIN_HAND);
        }
        Map<String, IndustrialItemStackSpec> expectedOutputs = expectedOutputSpecs(machineOutputs);
        Map<String, Integer> baseline = inputStacks.isEmpty() ? Map.of() : adapter.countOutputs(context, expectedOutputs);
        IndustrialMachineState state = new IndustrialMachineState(
                stepKey,
                adapter.id(),
                machinePos.asLong(),
                outputPolicy.id,
                gameTime,
                gameTime,
                Math.max(1, step.timeoutTicks()),
                Math.max(1, step.pollTicks()),
                baseline
        );
        data.setMachineState(state.toJson());
        data.setStatusKey("gui.simukraft.industrial.status.machine_running");
        data.setStatusText(step.point());
        manager.persist(data);
        return Result.WAITING;
    }

    private static Result tick(ServerLevel level,
                               IndustrialBoxManager manager,
                               IndustrialBoxData data,
                               PlacedBuildingRecord building,
                               IndustrialDefinition definition,
                               IndustrialDefinition.RecipeDefinition recipe,
                               CitizenData worker,
                               CitizenEntity entity,
                               IndustrialDefinition.StepDefinition step,
                               BlockPos machinePos,
                               List<BlockPos> inputContainers,
                               List<BlockPos> outputContainers,
                               OutputPolicy outputPolicy,
                               IndustrialMachineState state,
                               long gameTime) {
        IndustrialMachineAdapter adapter = findAdapter(state.adapterId()).orElse(null);
        if (adapter == null) {
            setStatus(manager, data, "gui.simukraft.industrial.status.machine_no_adapter", state.adapterId());
            return Result.WAITING_RETRY;
        }
        IndustrialMachineOperationContext context = new IndustrialMachineOperationContext(level, data, building, definition, recipe, step, worker, entity, machinePos, inputContainers, outputContainers, data.machineState());
        IndustrialMachineOperationEvent.Tick event = new IndustrialMachineOperationEvent.Tick(context);
        NeoForge.EVENT_BUS.post(event);
        if (event.decision() == IndustrialMachineOperationEvent.TickDecision.COMPLETE) {
            return complete(manager, data, context, List.of());
        }
        if (event.decision() == IndustrialMachineOperationEvent.TickDecision.WAIT_RETRY) {
            setStatus(manager, data, event.statusKey(), event.statusText());
            return Result.WAITING_RETRY;
        }
        if (!level.isLoaded(machinePos)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.machine_missing", step.point());
            return Result.WAITING_RETRY;
        }
        if (gameTime - state.startedAt() > state.timeoutTicks()) {
            setStatus(manager, data, "gui.simukraft.industrial.status.machine_timeout", step.point());
            return Result.WAITING_RETRY;
        }
        if (gameTime - state.lastPollAt() < state.pollTicks()) {
            return Result.WAITING;
        }
        List<IndustrialDefinition.ProductOutput> machineOutputs = machineOutputs(recipe, step);
        Map<String, IndustrialItemStackSpec> expectedOutputs = expectedOutputSpecs(machineOutputs);
        Map<String, Integer> current = adapter.countOutputs(context, expectedOutputs);
        Map<String, Integer> required = expectedOutputCounts(machineOutputs);
        if (!hasReadyOutput(current, state.baseline(), required)) {
            if (adapter.isWaitingForMissingInput(context, expectedOutputs)) {
                abort(level, manager, data, "machine_input_missing");
                setStatus(manager, data, "gui.simukraft.industrial.status.machine_needs_input", step.point());
                return Result.NEEDS_INPUT;
            }
            data.setMachineState(state.withLastPollAt(gameTime).toJson());
            manager.persist(data);
            return Result.WAITING;
        }
        if (outputPolicy == OutputPolicy.KEEP_IN_MACHINE) {
            return complete(manager, data, context, List.of());
        }
        List<ItemStack> preview = adapter.collectOutputs(context, expectedOutputs, state.baseline(), true);
        if (preview.isEmpty()) {
            data.setMachineState(state.withLastPollAt(gameTime).toJson());
            manager.persist(data);
            return Result.WAITING;
        }
        if (!IndustrialInventoryService.hasOutputSpace(level, outputContainers, preview)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.output_full", "");
            return Result.WAITING_RETRY;
        }
        List<ItemStack> outputs = adapter.collectOutputs(context, expectedOutputs, state.baseline(), false);
        if (!IndustrialInventoryService.insertItems(level, outputContainers, outputs)) {
            setStatus(manager, data, "gui.simukraft.industrial.status.output_full", "");
            return Result.WAITING_RETRY;
        }
        return complete(manager, data, context, outputs);
    }

    private static Result complete(IndustrialBoxManager manager, IndustrialBoxData data, IndustrialMachineOperationContext context, List<ItemStack> outputs) {
        data.setMachineState("");
        setStatus(manager, data, "gui.simukraft.industrial.status.running", "");
        if (context.worker() != null) {
            CitizenLevelService.addExperience(context.level(), context.worker().uuid(), CityJobType.INDUSTRIAL_WORKER, 2);
        }
        NeoForge.EVENT_BUS.post(new IndustrialMachineOperationEvent.Complete(context, outputs));
        return Result.PROGRESSED;
    }

    private static IndustrialMachineAdapter selectAdapter(IndustrialMachineOperationContext context) {
        IndustrialMachineOperationEvent.Start event = new IndustrialMachineOperationEvent.Start(context);
        NeoForge.EVENT_BUS.post(event);
        if (event.adapter() != null && event.adapter().matches(context)) {
            return event.adapter();
        }
        for (IndustrialMachineAdapter adapter : CUSTOM_ADAPTERS) {
            if (adapter.matches(context)) {
                return adapter;
            }
        }
        for (IndustrialMachineAdapter adapter : BUILTIN_ADAPTERS) {
            if (adapter.matches(context)) {
                return adapter;
            }
        }
        return null;
    }

    private static java.util.Optional<IndustrialMachineAdapter> findAdapter(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return java.util.Optional.empty();
        }
        for (IndustrialMachineAdapter adapter : CUSTOM_ADAPTERS) {
            if (adapterId.equalsIgnoreCase(adapter.id())) {
                return java.util.Optional.of(adapter);
            }
        }
        for (IndustrialMachineAdapter adapter : BUILTIN_ADAPTERS) {
            if (adapterId.equalsIgnoreCase(adapter.id())) {
                return java.util.Optional.of(adapter);
            }
        }
        return java.util.Optional.empty();
    }

    private static IndustrialMachineOperationContext resolveContext(ServerLevel level, IndustrialBoxData data, IndustrialMachineState state) {
        PlacedBuildingRecord building = IndustrialControlBoxService.resolveBuilding(level, data.boxPos());
        IndustrialDefinition definition = IndustrialDefinitionLoader.loadForBuilding(building).definition();
        if (definition == null) {
            return null;
        }
        IndustrialDefinition.RecipeDefinition recipe = definition.recipeById(data.selectedRecipeId());
        if (recipe == null || data.currentStep() >= recipe.steps().size()) {
            return null;
        }
        IndustrialDefinition.StepDefinition step = recipe.steps().get(data.currentStep());
        CitizenData worker = IndustrialControlBoxService.findAssignedWorker(level, data.boxPos());
        CitizenEntity entity = worker != null ? common.cn.kafei.simukraft.citizen.CitizenTeleportService.findCitizenEntity(level, worker.uuid()) : null;
        BlockPos machinePos = BlockPos.of(state.machinePosLong());
        List<BlockPos> inputContainers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.input(), step.container(), DEFAULT_INPUT_CONTAINER));
        List<BlockPos> outputContainers = IndustrialControlBoxService.resolveContainerPositions(building, definition, containerName(step.output(), step.container(), DEFAULT_OUTPUT_CONTAINER));
        return new IndustrialMachineOperationContext(level, data, building, definition, recipe, step, worker, entity, machinePos, inputContainers, outputContainers, data.machineState());
    }

    private static List<IndustrialDefinition.ItemRequirement> machineInputs(IndustrialDefinition.RecipeDefinition recipe, IndustrialDefinition.StepDefinition step) {
        return step.inputsOverride() ? step.inputs() : recipe.inputs();
    }

    private static List<IndustrialDefinition.ProductOutput> machineOutputs(IndustrialDefinition.RecipeDefinition recipe, IndustrialDefinition.StepDefinition step) {
        return step.outputsOverride() ? step.outputs() : recipe.outputs();
    }

    private static List<ItemStack> inputStacks(List<IndustrialDefinition.ItemRequirement> inputs) {
        List<ItemStack> stacks = new ArrayList<>();
        for (IndustrialDefinition.ItemRequirement input : inputs) {
            ItemStack stack = IndustrialInventoryService.stackForItem(input.itemId(), input.potionId(), input.count());
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return List.copyOf(stacks);
    }

    private static List<ItemStack> consumeInputs(ServerLevel level, List<BlockPos> inputContainers, List<IndustrialDefinition.ItemRequirement> inputs) {
        List<ItemStack> consumed = new ArrayList<>();
        for (IndustrialDefinition.ItemRequirement input : inputs) {
            ItemStack stack = IndustrialInventoryService.stackForItem(input.itemId(), input.potionId(), input.count());
            if (stack.isEmpty()) {
                continue;
            }
            if (!IndustrialInventoryService.consumeInput(level, inputContainers, input.itemId(), input.potionId(), input.count())) {
                rollbackInputs(level, inputContainers, consumed);
                return List.of();
            }
            consumed.add(stack);
        }
        return List.copyOf(consumed);
    }

    private static void rollbackInputs(ServerLevel level, List<BlockPos> inputContainers, List<ItemStack> consumed) {
        if (consumed == null || consumed.isEmpty()) {
            return;
        }
        IndustrialInventoryService.insertItems(level, inputContainers, consumed);
    }

    private static boolean hasReadyOutput(Map<String, Integer> current, Map<String, Integer> baseline, Map<String, Integer> required) {
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int delta = current.getOrDefault(entry.getKey(), 0) - baseline.getOrDefault(entry.getKey(), 0);
            if (delta < entry.getValue()) {
                return false;
            }
        }
        return !required.isEmpty();
    }

    private static Map<String, IndustrialItemStackSpec> expectedOutputSpecs(List<IndustrialDefinition.ProductOutput> outputs) {
        Map<String, IndustrialItemStackSpec> specs = new LinkedHashMap<>();
        for (IndustrialDefinition.ProductOutput output : outputs) {
            specs.putIfAbsent(outputKey(output.itemId(), output.potionId()), IndustrialItemStackSpec.of(output.itemId(), output.potionId()));
        }
        return Map.copyOf(specs);
    }

    private static Map<String, Integer> expectedOutputCounts(List<IndustrialDefinition.ProductOutput> outputs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (IndustrialDefinition.ProductOutput output : outputs) {
            counts.merge(outputKey(output.itemId(), output.potionId()), Math.max(1, output.baseAmount()), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private static String outputKey(String itemId, String potionId) {
        return (itemId != null ? itemId : "") + "|" + (potionId != null ? potionId : "");
    }

    private static String stepKey(IndustrialBoxData data,
                                  IndustrialDefinition definition,
                                  IndustrialDefinition.RecipeDefinition recipe,
                                  IndustrialDefinition.StepDefinition step,
                                  BlockPos machinePos,
                                  OutputPolicy outputPolicy) {
        return definition.id() + ":" + recipe.id() + ":" + data.currentStep() + ":" + step.type() + ":" + machinePos.asLong() + ":" + outputPolicy.id;
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

    private static void setStatus(IndustrialBoxManager manager, IndustrialBoxData data, String statusKey, String statusText) {
        String safeKey = statusKey != null ? statusKey : "";
        String safeText = statusText != null ? statusText : "";
        if (Objects.equals(data.statusKey(), safeKey) && Objects.equals(data.statusText(), safeText)) {
            return;
        }
        data.setStatusKey(safeKey);
        data.setStatusText(safeText);
        manager.persist(data);
    }

    public enum Result {
        PROGRESSED,
        WAITING,
        WAITING_RETRY,
        NEEDS_INPUT
    }

    private enum OutputPolicy {
        EXTRACT_TO_OUTPUT("extract_to_output"),
        KEEP_IN_MACHINE("keep_in_machine");

        private final String id;

        OutputPolicy(String id) {
            this.id = id;
        }

        private static OutputPolicy from(String name) {
            if (name != null && "keep_in_machine".equalsIgnoreCase(name.trim())) {
                return KEEP_IN_MACHINE;
            }
            return EXTRACT_TO_OUTPUT;
        }
    }

}
