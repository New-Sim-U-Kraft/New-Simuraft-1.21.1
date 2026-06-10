package common.cn.kafei.simukraft.planner;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenSelfFeedingService;
import common.cn.kafei.simukraft.citizen.CitizenSkillSnapshot;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.job.CitizenEmploymentService;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.material.GenericContainerAccess;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import common.cn.kafei.simukraft.protection.NpcBlockProtectionPolicy;
import common.cn.kafei.simukraft.registry.ModBlocks;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规划师工作服务：执行清除/填充/替换任务。设计对齐 {@link common.cn.kafei.simukraft.building.BuilderConstructionService}：
 * 规划师待在建筑盒处工作，按等级换算速度每 tick 处理少量方块；材料/掉落走紧贴建筑盒六个面的箱子；
 * 金钱在建任务时已一次性预扣。任务进 SQLite，可恢复。
 */

@SuppressWarnings("null")
public final class PlannerWorkService {
    private static final ConcurrentMap<String, LevelRuntime> LEVEL_RUNTIMES = new ConcurrentHashMap<>();
    private static final int SAVE_BLOCK_INTERVAL = 16;
    private static final int SCAN_LIMIT_PER_TICK = 2048;
    private static final long MATERIAL_RETRY_INTERVAL_TICKS = 40L;
    private static final double REACH = 3.0D;

    private PlannerWorkService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        hydrate(level, runtime);
        for (TaskRuntime taskRuntime : runtime.tasks.values()) {
            Optional<CitizenData> citizen = CitizenService.findCitizen(level, taskRuntime.task.citizenId());
            if (citizen.isEmpty() || citizen.get().dead()) {
                interruptTask(level, taskRuntime.task.citizenId(), citizen.isPresent() ? "planner_dead" : "planner_missing");
                continue;
            }
            if (CitizenSelfFeedingService.isSelfFeeding(level, citizen.get().uuid())) {
                continue;
            }
            tickTask(level, citizen.get(), runtime, taskRuntime);
        }
    }

    public static void startTask(ServerLevel level, PlanningTaskData task) {
        if (level == null || task == null || task.citizenId() == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        runtime.hydrated = true;
        runtime.tasks.put(task.citizenId(), new TaskRuntime(task));
        SimuSqliteStorage.savePlanningTask(level, task);
    }

    public static void cancelTask(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return;
        }
        runtime(level).tasks.remove(citizenId);
        SimuSqliteStorage.deletePlanningTask(level, citizenId);
    }

    public static boolean hasActiveTask(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return false;
        }
        TaskRuntime taskRuntime = runtime(level).tasks.get(citizenId);
        if (taskRuntime == null) {
            return false;
        }
        PlanningTaskStatus status = PlanningTaskStatus.from(taskRuntime.task.status());
        return status != PlanningTaskStatus.COMPLETED && status != PlanningTaskStatus.INTERRUPTED;
    }

    // countTargetBlocks：创建任务前统计真正需要处理的方块，避免把空扫计入费用和进度。
    public static int countTargetBlocks(ServerLevel level, PlanningTaskData task) {
        if (level == null || task == null) {
            return 0;
        }
        BlockPos chestPos = resolveTaskChest(level, task);
        int count = 0;
        for (int index = 0; index < task.totalBlocks(); index++) {
            BlockPos pos = task.blockAt(index);
            if (level.isLoaded(pos) && isTargetCell(level, task, pos, chestPos)) {
                count++;
            }
        }
        return count;
    }

    public static void interruptTasksByBuildBox(ServerLevel level, BlockPos buildBoxPos, String reason) {
        if (level == null || buildBoxPos == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        runtime.tasks.values().stream()
                .filter(taskRuntime -> buildBoxPos.equals(taskRuntime.task.buildBoxPos()))
                .map(taskRuntime -> taskRuntime.task.citizenId())
                .toList()
                .forEach(citizenId -> interruptTask(level, citizenId, reason));
    }

    public static void interruptTask(ServerLevel level, UUID citizenId, String reason) {
        if (level == null || citizenId == null) {
            return;
        }
        TaskRuntime removed = runtime(level).tasks.remove(citizenId);
        SimuSqliteStorage.deletePlanningTask(level, citizenId);
        if (removed != null) {
            CitizenService.findCitizen(level, citizenId)
                    .filter(citizen -> !citizen.dead())
                    .ifPresent(citizen -> flushXp(level, citizen, removed));
            SimuKraft.LOGGER.info("Simukraft: Planning task interrupted for {} ({})", citizenId, reason != null ? reason : "unknown");
        }
    }

    public static void flush(ServerLevel level) {
        if (level == null) {
            return;
        }
        LevelRuntime runtime = LEVEL_RUNTIMES.remove(runtimeKey(level));
        if (runtime == null) {
            return;
        }
        runtime.tasks.values().forEach(taskRuntime -> {
            CitizenService.findCitizen(level, taskRuntime.task.citizenId())
                    .filter(citizen -> !citizen.dead())
                    .ifPresent(citizen -> flushXp(level, citizen, taskRuntime));
            PlanningTaskData paused = taskRuntime.task.withStatus(PlanningTaskStatus.PAUSED_OFFLINE.id(), System.currentTimeMillis());
            SimuSqliteStorage.savePlanningTask(level, paused);
        });
    }

    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server).toLowerCase(Locale.ROOT);
        LEVEL_RUNTIMES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    private static void tickTask(ServerLevel level, CitizenData citizen, LevelRuntime runtime, TaskRuntime taskRuntime) {
        PlanningTaskData task = taskRuntime.task;
        BlockPos boxPos = task.buildBoxPos();
        if (citizen.jobType() != CityJobType.PLANNER || citizen.workplaceId() == null) {
            interruptTask(level, citizen.uuid(), "planner_not_assigned");
            return;
        }
        if (!level.isLoaded(boxPos)) {
            return;
        }
        if (!level.getBlockState(boxPos).is(ModBlocks.BUILD_BOX.get())) {
            interruptTask(level, citizen.uuid(), "build_box_removed");
            return;
        }
        if (shouldRest(level)) {
            setStatus(level, citizen, taskRuntime, "夜间休息中: 规划", CitizenWorkStatus.RESTING, PlanningTaskStatus.PAUSED_RESTING);
            return;
        }
        // 规划师待在建筑盒处工作；离得远先寻路/传送过去。
        Vec3 anchor = Vec3.atBottomCenterOf(boxPos.above());
        var citizenEntity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (citizenEntity == null) {
            return;
        }
        if (citizenEntity.position().distanceToSqr(anchor) > REACH * REACH) {
            if (!CitizenNavigationService.requestMove(level, citizen.uuid(), anchor, MovementIntent.WORK)) {
                CitizenTeleportService.teleportCitizen(level, citizen.uuid(), anchor);
            }
            return;
        }

        BlockPos chestPos = resolveTaskChest(level, task);
        int budget = consumeBudget(taskRuntime, citizen);
        int index = Math.max(0, task.currentIndex());
        int total = task.totalBlocks();
        int completed = Math.max(0, task.completedBlocks());
        int processed = 0;
        int scanned = 0;
        boolean waiting = false;
        while (index < total && processed < budget && scanned < SCAN_LIMIT_PER_TICK) {
            BlockPos pos = task.blockAt(index);
            scanned++;
            if (!level.isLoaded(pos)) {
                index++;
                continue;
            }
            CellResult result = applyCell(level, task, pos, chestPos);
            if (result == CellResult.WAITING) {
                waiting = true;
                break;
            }
            if (result == CellResult.PROCESSED) {
                completed++;
                processed++;
                addXp(taskRuntime, 1);
            }
            index++;
        }
        restoreUnusedBudget(taskRuntime, budget - processed);

        long now = System.currentTimeMillis();
        if (waiting) {
            // 等材料：标记状态、设置重试冷却，不推进游标。
            if (level.getGameTime() >= taskRuntime.nextRetryTick) {
                taskRuntime.nextRetryTick = level.getGameTime() + MATERIAL_RETRY_INTERVAL_TICKS;
            }
            PlanningTaskData updated = task.withProgress(index, completed, PlanningTaskStatus.WAITING_MATERIALS.id(), now);
            taskRuntime.task = updated;
            SimuSqliteStorage.savePlanningTask(level, updated);
            setStatus(level, citizen, taskRuntime, "缺少方块: 规划" + progressSuffix(updated), CitizenWorkStatus.WORKING, PlanningTaskStatus.WAITING_MATERIALS);
            return;
        }
        if (index >= total) {
            completeTask(level, citizen, runtime, taskRuntime);
            return;
        }
        PlanningTaskData updated = task.withProgress(index, completed, PlanningTaskStatus.PLANNING.id(), now);
        taskRuntime.task = updated;
        setStatus(level, citizen, taskRuntime, "规划中(" + operationLabel(task.operation()) + ")" + progressSuffix(updated), CitizenWorkStatus.WORKING, PlanningTaskStatus.PLANNING);
        if (index - taskRuntime.lastSavedIndex >= SAVE_BLOCK_INTERVAL) {
            taskRuntime.lastSavedIndex = index;
            SimuSqliteStorage.savePlanningTask(level, updated);
        }
    }

    private static CellResult applyCell(ServerLevel level, PlanningTaskData task, BlockPos pos, BlockPos chestPos) {
        return switch (task.operation()) {
            case REMOVE -> applyRemove(level, pos, chestPos, task.buildBoxPos());
            case FILL -> applyFill(level, pos, chestPos, task.fillBlockId());
            case REPLACE -> applyReplace(level, pos, chestPos, task.effectiveReplacementMap(), task.buildBoxPos());
        };
    }

    // isTargetCell：只做判定不改世界，用于创建任务时计算真实目标总数。
    private static boolean isTargetCell(ServerLevel level, PlanningTaskData task, BlockPos pos, BlockPos chestPos) {
        return switch (task.operation()) {
            case REMOVE -> isRemoveTarget(level, pos, task.buildBoxPos(), chestPos);
            case FILL -> isFillTarget(level, pos, task.fillBlockId());
            case REPLACE -> isReplaceTarget(level, pos, task.effectiveReplacementMap(), task.buildBoxPos(), chestPos);
        };
    }

    private static boolean isRemoveTarget(ServerLevel level, BlockPos pos, BlockPos boxPos, BlockPos chestPos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !isProtected(level, pos, state, boxPos, chestPos);
    }

    private static boolean isFillTarget(ServerLevel level, BlockPos pos, String fillBlockId) {
        BlockState state = level.getBlockState(pos);
        return (state.isAir() || state.canBeReplaced()) && resolveBlock(fillBlockId) != null;
    }

    private static boolean isReplaceTarget(ServerLevel level, BlockPos pos, Map<String, String> replacementMap, BlockPos boxPos, BlockPos chestPos) {
        if (replacementMap == null || replacementMap.isEmpty()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        String sourceBlockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String targetBlockId = replacementMap.get(sourceBlockId);
        return targetBlockId != null && resolveBlock(targetBlockId) != null && !isProtected(level, pos, state, boxPos, chestPos);
    }

    private static CellResult applyRemove(ServerLevel level, BlockPos pos, BlockPos chestPos, BlockPos boxPos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isProtected(level, pos, state, boxPos, chestPos)) {
            return CellResult.SKIPPED;
        }
        harvestInto(level, chestPos, pos, state);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        return CellResult.PROCESSED;
    }

    private static CellResult applyFill(ServerLevel level, BlockPos pos, BlockPos chestPos, String fillBlockId) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced()) {
            return CellResult.SKIPPED;
        }
        Block block = resolveBlock(fillBlockId);
        if (block == null) {
            return CellResult.SKIPPED;
        }
        if (!consumeBlockItem(level, chestPos, block)) {
            return CellResult.WAITING;
        }
        level.setBlock(pos, block.defaultBlockState(), 3);
        return CellResult.PROCESSED;
    }

    private static CellResult applyReplace(ServerLevel level, BlockPos pos, BlockPos chestPos, Map<String, String> replacementMap, BlockPos boxPos) {
        if (replacementMap == null || replacementMap.isEmpty()) {
            return CellResult.SKIPPED;
        }
        BlockState state = level.getBlockState(pos);
        String sourceBlockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String targetBlockId = replacementMap.get(sourceBlockId);
        if (targetBlockId == null || isProtected(level, pos, state, boxPos, chestPos)) {
            return CellResult.SKIPPED;
        }
        Block target = resolveBlock(targetBlockId);
        if (target == null) {
            return CellResult.SKIPPED;
        }
        if (!consumeBlockItem(level, chestPos, target)) {
            return CellResult.WAITING;
        }
        harvestInto(level, chestPos, pos, state);
        level.setBlock(pos, target.defaultBlockState(), 3);
        return CellResult.PROCESSED;
    }

    private static void completeTask(ServerLevel level, CitizenData citizen, LevelRuntime runtime, TaskRuntime taskRuntime) {
        flushXp(level, citizen, taskRuntime);
        runtime.tasks.remove(citizen.uuid(), taskRuntime);
        SimuSqliteStorage.deletePlanningTask(level, citizen.uuid());
        // 规划师完成任务后保留雇佣，转待命，等待下一个规划任务。
        citizen.setWorkStatus(CitizenWorkStatus.IDLE);
        citizen.setStatusLabel("规划完成，待命中");
        citizen.setWorkNeedDetail("");
        CitizenService.save(level, citizen.uuid());
        SimuKraft.LOGGER.info("Simukraft: Planning task completed by {}", citizen.name());
    }

    private static void hydrate(ServerLevel level, LevelRuntime runtime) {
        if (runtime.hydrated) {
            return;
        }
        runtime.hydrated = true;
        List<PlanningTaskData> tasks = SimuSqliteStorage.loadPlanningTasks(level);
        for (PlanningTaskData task : tasks) {
            PlanningTaskStatus status = PlanningTaskStatus.from(task.status());
            if (status == PlanningTaskStatus.COMPLETED || status == PlanningTaskStatus.INTERRUPTED) {
                continue;
            }
            PlanningTaskData resumed = status == PlanningTaskStatus.PAUSED_OFFLINE
                    ? task.withStatus(PlanningTaskStatus.PLANNING.id(), System.currentTimeMillis())
                    : task;
            runtime.tasks.putIfAbsent(resumed.citizenId(), new TaskRuntime(resumed));
            restorePlannerEmployment(level, resumed);
        }
    }

    // restorePlannerEmployment：规划任务恢复时以任务表为准修复规划师职业和建筑盒岗位。
    private static void restorePlannerEmployment(ServerLevel level, PlanningTaskData task) {
        if (level == null || task == null || task.citizenId() == null) {
            return;
        }
        CitizenEmploymentService.assignForSource(level, task.citizenId(), "build_box", "planner", task.buildBoxPos(), CitizenWorkStatus.WORKING, "");
    }

    private static void setStatus(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime, String label, CitizenWorkStatus workStatus, PlanningTaskStatus taskStatus) {
        if (PlanningTaskStatus.from(taskRuntime.task.status()) != taskStatus) {
            taskRuntime.task = taskRuntime.task.withStatus(taskStatus.id(), System.currentTimeMillis());
            SimuSqliteStorage.savePlanningTask(level, taskRuntime.task);
        }
        if (label.equals(citizen.statusLabel()) && citizen.workStatusType() == workStatus) {
            return;
        }
        citizen.setWorkStatus(workStatus);
        citizen.setStatusLabel(label);
        citizen.setWorkNeedDetail("plan:" + taskRuntime.task.taskId());
        CitizenService.save(level, citizen.uuid());
    }

    private static int consumeBudget(TaskRuntime taskRuntime, CitizenData citizen) {
        taskRuntime.progressAccumulator += plannerBlocksPerTick(citizen);
        int budget = Math.min(128, (int) taskRuntime.progressAccumulator);
        taskRuntime.progressAccumulator -= budget;
        return Math.max(0, budget);
    }

    // restoreUnusedBudget：非目标方块只消耗扫描上限，不消耗规划师实际处理预算。
    private static void restoreUnusedBudget(TaskRuntime taskRuntime, int unusedBudget) {
        if (unusedBudget <= 0) {
            return;
        }
        taskRuntime.progressAccumulator = Math.min(128.0D, taskRuntime.progressAccumulator + unusedBudget);
    }

    private static double plannerBlocksPerTick(CitizenData citizen) {
        double base = ServerConfig.plannerBlocksPerSecond();
        CitizenSkillSnapshot skill = CitizenLevelService.snapshot(citizen, CityJobType.PLANNER);
        if (skill.maxLevel() <= 1) {
            return Math.min(128.0D, base / 20.0D);
        }
        double progress = (skill.level() - 1) / (double) (skill.maxLevel() - 1);
        double perSecond = base * (1.0D + progress * 19.0D);
        return Math.min(128.0D, perSecond / 20.0D);
    }

    private static boolean shouldRest(ServerLevel level) {
        if (!ServerConfig.plannerPauseAtNight()) {
            return false;
        }
        return CitizenHomeRestService.isRestTime(level);
    }

    private static void addXp(TaskRuntime taskRuntime, int blocks) {
        if (blocks <= 0 || !ServerConfig.plannerXpGainEnabled()) {
            return;
        }
        int perBlock = ServerConfig.plannerXpPerBlock();
        if (perBlock <= 0) {
            return;
        }
        taskRuntime.pendingXp.updateAndGet(current -> (int) Math.min(Integer.MAX_VALUE, (long) current + (long) blocks * perBlock));
    }

    private static void flushXp(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime) {
        int xp = taskRuntime.pendingXp.getAndSet(0);
        if (xp <= 0) {
            return;
        }
        CitizenLevelService.addExperience(level, citizen.uuid(), CityJobType.PLANNER, xp);
    }

    private static void harvestInto(ServerLevel level, BlockPos chestPos, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos));
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack leftover = chestPos != null ? GenericContainerAccess.insert(level, chestPos, drop) : drop;
            if (!leftover.isEmpty()) {
                Block.popResource(level, pos, leftover);
            }
        }
    }

    private static boolean consumeBlockItem(ServerLevel level, BlockPos chestPos, Block block) {
        if (chestPos == null) {
            return false;
        }
        Item item = block.asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return false;
        }
        List<GenericContainerAccess.SlotSnapshot> slots = GenericContainerAccess.snapshotSlots(level, chestPos);
        for (GenericContainerAccess.SlotSnapshot slot : slots) {
            if (slot.stack().getItem() == item) {
                return GenericContainerAccess.consumeSingleItemAtSlot(level, chestPos, slot.slot(), slot.access(), slot.side(), item);
            }
        }
        return false;
    }

    private static BlockPos resolveTaskChest(ServerLevel level, PlanningTaskData task) {
        BlockPos selected = task.materialChestPos();
        if (selected == null) {
            return resolveAdjacentChest(level, task.buildBoxPos());
        }
        if (!level.isLoaded(selected)) {
            return selected;
        }
        return GenericContainerAccess.isContainer(level, selected)
                ? GenericContainerAccess.canonicalContainerPos(level, selected)
                : selected;
    }

    private static BlockPos resolveAdjacentChest(ServerLevel level, BlockPos boxPos) {
        for (Direction direction : Direction.values()) {
            BlockPos candidate = boxPos.relative(direction);
            if (level.isLoaded(candidate) && GenericContainerAccess.isContainer(level, candidate)) {
                return GenericContainerAccess.canonicalContainerPos(level, candidate);
            }
        }
        return null;
    }

    private static Block resolveBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    // 保护：不拆基岩、建筑盒、绑定的箱子，避免规划师自毁工作区或吃掉材料箱。
    private static boolean isProtected(ServerLevel level, BlockPos pos, BlockState state, BlockPos boxPos, BlockPos chestPos) {
        if (pos.equals(boxPos) || (chestPos != null && pos.equals(chestPos))) {
            return true;
        }
        if (state.is(Blocks.BEDROCK)) {
            return true;
        }
        if (state.is(ModBlocks.BUILD_BOX.get()) || state.is(ModBlocks.CITY_CORE.get())) {
            return true;
        }
        if (NpcBlockProtectionPolicy.isProtected(state)) {
            NpcBlockProtectionPolicy.logSkipped("planner", level, pos, state);
            return true;
        }
        return GenericContainerAccess.isContainer(level, pos);
    }

    private static String operationLabel(PlanOperation operation) {
        return switch (operation) {
            case REMOVE -> "清除";
            case FILL -> "填充";
            case REPLACE -> "替换";
        };
    }

    private static String progressSuffix(PlanningTaskData task) {
        int targetTotal = Math.max(1, task.targetBlocks());
        return " " + Math.min(task.completedBlocks(), targetTotal) + "/" + targetTotal;
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return LEVEL_RUNTIMES.computeIfAbsent(runtimeKey(level), ignored -> new LevelRuntime());
    }

    private static String runtimeKey(ServerLevel level) {
        return SaveScopedCacheKey.levelKey(level).toLowerCase(Locale.ROOT);
    }

    private enum CellResult {
        PROCESSED,
        SKIPPED,
        WAITING
    }

    private static final class LevelRuntime {
        private final ConcurrentMap<UUID, TaskRuntime> tasks = new ConcurrentHashMap<>();
        private volatile boolean hydrated;
    }

    private static final class TaskRuntime {
        private volatile PlanningTaskData task;
        private double progressAccumulator;
        private int lastSavedIndex;
        private long nextRetryTick;
        private final AtomicInteger pendingXp = new AtomicInteger();

        private TaskRuntime(PlanningTaskData task) {
            this.task = task;
            this.lastSavedIndex = task.currentIndex();
        }
    }
}
