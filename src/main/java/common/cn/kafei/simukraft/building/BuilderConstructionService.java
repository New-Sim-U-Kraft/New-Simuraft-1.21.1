package common.cn.kafei.simukraft.building;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenHousingService;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenSkillSnapshot;
import common.cn.kafei.simukraft.citizen.CitizenWorkplaceMoveService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.job.CityJobAssignmentService;
import common.cn.kafei.simukraft.job.CityJobMobilityService;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.material.WorkMaterialCache;
import common.cn.kafei.simukraft.material.WorkMaterialResult;
import common.cn.kafei.simukraft.registry.ModBlocks;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("null")
public final class BuilderConstructionService {
    // 建筑结构变换后的方块列表缓存，避免每个 tick 重复解析 NBT/旋转坐标。
    private static final ConcurrentMap<String, CompletableFuture<CachedStructure>> STRUCTURE_CACHE = new ConcurrentHashMap<>();
    // 每个存档维度独立的施工运行时，不能跨存档复用。
    private static final ConcurrentMap<String, LevelRuntime> LEVEL_RUNTIMES = new ConcurrentHashMap<>();
    private static final int SAVE_BLOCK_INTERVAL = 12;
    private static final long SAVE_INTERVAL_MS = 1500L;
    private static final long MATERIAL_RETRY_INTERVAL_TICKS = 40L;
    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            new BuilderIoThreadFactory()
    );

    private BuilderConstructionService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        // 先异步恢复 SQLite 中的未完成任务，再按 NPC 逐个推进施工。
        hydrateTasks(level, runtime);
        for (TaskRuntime taskRuntime : runtime.tasksByCitizen.values()) {
            Optional<CitizenData> citizen = CitizenService.findCitizen(level, taskRuntime.task.citizenId());
            if (citizen.isEmpty() || citizen.get().dead()) {
                interruptTask(level, taskRuntime.task.citizenId(), citizen.isPresent() ? "citizen_dead" : "citizen_missing");
                continue;
            }
            tickTask(level, citizen.get(), runtime, taskRuntime);
        }
        if (level.getGameTime() % 40L == 0L) {
            flushDirtyTasks(level, runtime);
            flushPendingBuilderXp(level, runtime);
        }
    }

    public static void startTask(ServerLevel level, BuildingTaskData task) {
        if (level == null || task == null || task.citizenId() == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        TaskRuntime taskRuntime = new TaskRuntime(task);
        runtime.tasksByCitizen.put(task.citizenId(), taskRuntime);
        runtime.hydrated = true;
        SimuSqliteStorage.saveBuildingTask(level, task);
        taskRuntime.lastSavedAt = task.updatedAt();
        taskRuntime.lastSavedIndex = task.currentBlockIndex();
        primeStructureLoad(task);
    }

    public static void cancelTask(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return;
        }
        runtime(level).tasksByCitizen.remove(citizenId);
        SimuSqliteStorage.deleteBuildingTask(level, citizenId);
    }

    // hasActiveBuildTask：供职业外观判断建筑师是否需要播放施工动作。
    public static boolean hasActiveBuildTask(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return false;
        }
        TaskRuntime taskRuntime = runtime(level).tasksByCitizen.get(citizenId);
        if (taskRuntime == null) {
            return false;
        }
        BuildingTaskStatus status = BuildingTaskStatus.from(taskRuntime.task.status());
        return taskRuntime.task.currentBlockIndex() < taskRuntime.task.totalBlocks()
                && status != BuildingTaskStatus.COMPLETED
                && status != BuildingTaskStatus.INTERRUPTED
                && !status.isPaused();
    }

    // findBuildBoxPos：供上班恢复逻辑按市民找到当前施工控制盒，不触发额外扫描或数据库读取。
    public static BlockPos findBuildBoxPos(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return null;
        }
        TaskRuntime taskRuntime = runtime(level).tasksByCitizen.get(citizenId);
        return taskRuntime != null && taskRuntime.task != null ? taskRuntime.task.buildBoxPos() : null;
    }

    public static void interruptTask(ServerLevel level, UUID citizenId, String reason) {
        if (level == null || citizenId == null) {
            return;
        }
        TaskRuntime removed = runtime(level).tasksByCitizen.remove(citizenId);
        if (removed != null) {
            CitizenService.findCitizen(level, citizenId)
                    .filter(citizen -> !citizen.dead())
                    .ifPresent(citizen -> flushPendingBuilderXp(level, citizen, removed));
            BuildingTaskData interrupted = withStatus(removed.task, BuildingTaskStatus.INTERRUPTED);
            SimuSqliteStorage.saveBuildingTask(level, interrupted);
        }
        SimuSqliteStorage.deleteBuildingTask(level, citizenId);
        SimuKraft.LOGGER.info("Simukraft: Building task interrupted for {} ({})", citizenId, reason != null ? reason : "unknown");
    }

    public static void interruptTasksByBuildBox(ServerLevel level, BlockPos buildBoxPos, String reason) {
        if (level == null || buildBoxPos == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        runtime.tasksByCitizen.values().stream()
                .filter(taskRuntime -> buildBoxPos.equals(taskRuntime.task.buildBoxPos()))
                .map(taskRuntime -> taskRuntime.task.citizenId())
                .toList()
                .forEach(citizenId -> {
                    interruptTask(level, citizenId, reason);
                    CitizenService.clearEmployment(level, citizenId);
                    CityJobMobilityService.resetCitizenAfterFire(level, citizenId);
                });
    }

    public static void flush(ServerLevel level) {
        if (level == null) {
            return;
        }
        LevelRuntime runtime = LEVEL_RUNTIMES.remove(runtimeKey(level));
        if (runtime == null) {
            return;
        }
        runtime.tasksByCitizen.values().forEach(taskRuntime -> {
            CitizenService.findCitizen(level, taskRuntime.task.citizenId())
                    .filter(citizen -> !citizen.dead())
                    .ifPresent(citizen -> flushPendingBuilderXp(level, citizen, taskRuntime));
            // 服务器关闭时统一标记离线暂停，避免重进游戏后任务被当作正在施工。
            BuildingTaskData paused = withStatus(taskRuntime.task, BuildingTaskStatus.PAUSED_OFFLINE);
            taskRuntime.task = paused;
            SimuSqliteStorage.saveBuildingTask(level, paused);
        });
    }

    // 清理指定存档的施工运行时缓存，结构变换缓存不跨存档保留。
    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server);
        LEVEL_RUNTIMES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
        STRUCTURE_CACHE.clear();
    }

    private static void tickTask(ServerLevel level, CitizenData citizen, LevelRuntime runtime, TaskRuntime taskRuntime) {
        BuildingTaskData task = taskRuntime.task;
        // 建筑师失业、控制盒被拆、夜间休息都在服务端判定，防止客户端伪造流程。
        if (citizen.jobType() != CityJobType.BUILDER || citizen.workplaceId() == null) {
            interruptTask(level, citizen.uuid(), "builder_not_assigned");
            return;
        }
        if (!level.getBlockState(task.buildBoxPos()).is(ModBlocks.BUILD_BOX.get())) {
            interruptTask(level, citizen.uuid(), "build_box_removed");
            CitizenService.clearEmployment(level, citizen.uuid());
            CityJobMobilityService.resetCitizenAfterFire(level, citizen.uuid());
            return;
        }
        if (shouldRest(level)) {
            pauseTask(level, citizen, taskRuntime, BuildingTaskStatus.PAUSED_RESTING, "夜间休息中: " + task.displayName());
            return;
        }
        if (BuildingTaskStatus.from(task.status()).isPaused()) {
            task = resumeTask(level, citizen, taskRuntime);
        }
        if (BuildingTaskStatus.from(task.status()) == BuildingTaskStatus.WAITING_MATERIALS) {
            if (level.getGameTime() < taskRuntime.nextMaterialRetryTick) {
                syncCitizenTaskState(level, citizen, taskRuntime, task, null);
                return;
            }
            taskRuntime.materialCache.markDirty();
        }
        CachedStructure cached = resolveCached(task);
        if (cached == null || cached.blocks().isEmpty()) {
            syncCitizenTaskState(level, citizen, taskRuntime, task, null);
            return;
        }
        syncCitizenTaskState(level, citizen, taskRuntime, task, cached);
        int placed = 0;
        int index = Math.max(0, task.currentBlockIndex());
        LayerRange currentLayer = resolveCurrentLayerRange(cached, index);
        int maxIndexExclusive = currentLayer != null ? Math.min(cached.blocks().size(), currentLayer.endIndex() + 1) : cached.blocks().size();
        int blockBudget = consumeBuildBudget(taskRuntime, citizen);
        // 每 tick 只放置当前层的一小段，降低大建筑一次性 setBlock 带来的卡顿。
        while (index < maxIndexExclusive && placed < blockBudget) {
            BuildingBlockData block = cached.blocks().get(index);
            BlockPos worldPos = block.relativePos();
            BlockState targetState = block.state();
            if (level.getBlockState(worldPos).equals(targetState)) {
                index++;
                continue;
            }
            WorkMaterialResult materialResult = BuilderMaterialService.tryConsumeForBlock(level, taskRuntime.materialCache, targetState);
            if (!materialResult.available()) {
                markWaitingForMaterials(level, citizen, taskRuntime, task, materialResult);
                return;
            }
            level.setBlock(worldPos, targetState, 3);
            spawnBuildParticles(level, worldPos);
            addPendingBuilderXp(taskRuntime, 1);
            index++;
            placed++;
        }
        if (index == task.currentBlockIndex()) {
            return;
        }
        long now = System.currentTimeMillis();
        BuildingTaskData updated = new BuildingTaskData(task.taskId(), task.citizenId(), task.cityId(), task.dimensionId(), task.buildBoxPos(), task.category(), task.buildingFileName(), task.displayName(), task.amount(), task.structureFileName(), task.origin(), task.rotationDegrees(), index, task.totalBlocks(), index >= cached.blocks().size() ? BuildingTaskStatus.COMPLETED.id() : BuildingTaskStatus.BUILDING.id(), task.createdAt(), now, task.poiDefinitions());
        taskRuntime.task = updated;
        taskRuntime.dirty = true;
        taskRuntime.missingMaterialName = "";
        taskRuntime.nextMaterialRetryTick = 0L;
        syncCitizenTaskState(level, citizen, taskRuntime, updated, cached);
        if (shouldPersist(taskRuntime, updated)) {
            persistTaskAsync(level, taskRuntime, updated);
        }
        if (index >= cached.blocks().size()) {
            completeTask(level, citizen, runtime, taskRuntime, updated, cached);
        }
    }

    private static void completeTask(ServerLevel level, CitizenData citizen, LevelRuntime runtime, TaskRuntime taskRuntime, BuildingTaskData task, CachedStructure cached) {
        UUID cityId = task.cityId();
        List<BuildingPoiInstance> poiInstances = resolvePoiInstances(cached.sourceBlocks(), cached.blocks(), task);
        if (cityId != null) {
            // 完工时才注册 POI，保证未建完的住宅/岗位不会提前参与居民分配。
            for (BuildingPoiInstance poi : poiInstances) {
                CityPoiManager.get(level).registerPoi(stablePoiId(poi), cityId, poi.worldPos(), poi.poiType(), poi.capacity());
            }
            CitizenHousingService.fillVacantHomes(level, cityId);
            CityManager.get(level).getCity(cityId).ifPresent(city -> CitizenHousingService.spawnCitizensForVacantHomes(level, cityId, city.cityCorePos().above(), ServerConfig.populationGrowthMaxPerInterval()));
            CityJobAssignmentService.invalidate(cityId);
        }
        BlockPos minPos = cached.blocks().stream().map(BuildingBlockData::relativePos).reduce(task.origin(), (current, pos) -> new BlockPos(Math.min(current.getX(), pos.getX()), Math.min(current.getY(), pos.getY()), Math.min(current.getZ(), pos.getZ())));
        BlockPos maxPos = cached.blocks().stream().map(BuildingBlockData::relativePos).reduce(task.origin(), (current, pos) -> new BlockPos(Math.max(current.getX(), pos.getX()), Math.max(current.getY(), pos.getY()), Math.max(current.getZ(), pos.getZ())));
        PlacedBuildingRecord placedBuilding = new PlacedBuildingRecord(UUID.randomUUID(), cityId, task.dimensionId(), task.category(), task.buildingFileName(), task.displayName(), task.amount(), task.structureFileName(), BuildingTransform.directionFromRotation(task.rotationDegrees()).getSerializedName(), task.origin(), BlockPos.ZERO, minPos, maxPos, System.currentTimeMillis(), cached.blocks(), task.poiDefinitions(), poiInstances);
        PlacedBuildingService.register(level, placedBuilding);
        ResidentialBedPoiService.addRecordedBeds(level, placedBuilding);
        flushPendingBuilderXp(level, citizen, taskRuntime);
        runtime.tasksByCitizen.remove(citizen.uuid(), taskRuntime);
        SimuSqliteStorage.deleteBuildingTask(level, citizen.uuid());
        CitizenService.clearEmployment(level, citizen.uuid());
        CityJobMobilityService.resetCitizenAfterFire(level, citizen.uuid());
        SimuKraft.LOGGER.info("Simukraft: Building completed by {} for {}", citizen.name(), task.displayName());
    }

    private static void hydrateTasks(ServerLevel level, LevelRuntime runtime) {
        if (runtime.hydrated) {
            return;
        }
        CompletableFuture<List<BuildingTaskData>> loadFuture = runtime.loadFuture;
        if (loadFuture == null) {
            // SQLite 读取放到 IO 线程，避免世界 tick 被磁盘读取阻塞。
            runtime.loadFuture = CompletableFuture.supplyAsync(() -> SimuSqliteStorage.loadBuildingTasks(level), IO_EXECUTOR);
            return;
        }
        if (!loadFuture.isDone()) {
            return;
        }
        try {
            loadFuture.join().forEach(task -> {
                boolean restoredFromOfflinePause = BuildingTaskStatus.from(task.status()) == BuildingTaskStatus.PAUSED_OFFLINE;
                BuildingTaskData resumed = restoredFromOfflinePause
                        ? withStatus(task, BuildingTaskStatus.BUILDING)
                        : task;
                TaskRuntime taskRuntime = new TaskRuntime(resumed);
                TaskRuntime existing = runtime.tasksByCitizen.putIfAbsent(resumed.citizenId(), taskRuntime);
                primeStructureLoad(resumed);
                if (existing == null && restoredFromOfflinePause && !shouldRest(level)) {
                    taskRuntime.dirty = true;
                    persistTaskAsync(level, taskRuntime, resumed);
                    CitizenService.findCitizen(level, resumed.citizenId())
                            .filter(citizen -> !citizen.dead())
                            .ifPresent(citizen -> {
                                citizen.setWorkStatus(CitizenWorkStatus.WORKING);
                                CitizenService.save(level, citizen.uuid());
                                CitizenWorkplaceMoveService.returnToWorkplace(level, citizen);
                            });
                }
            });
            runtime.hydrated = true;
        } catch (CompletionException exception) {
            SimuKraft.LOGGER.error("Simukraft: Failed to hydrate building tasks for {}", level.dimension().location(), exception);
            runtime.loadFuture = null;
        }
    }

    private static void flushDirtyTasks(ServerLevel level, LevelRuntime runtime) {
        runtime.tasksByCitizen.values().forEach(taskRuntime -> {
            if (!taskRuntime.dirty || taskRuntime.saveInFlight) {
                return;
            }
            persistTaskAsync(level, taskRuntime, taskRuntime.task);
        });
    }

    private static void addPendingBuilderXp(TaskRuntime taskRuntime, int placedBlocks) {
        if (taskRuntime == null || placedBlocks <= 0 || !ServerConfig.builderXpGainEnabled()) {
            return;
        }
        int xpPerBlock = ServerConfig.builderXpPerBlock();
        if (xpPerBlock <= 0) {
            return;
        }
        int xpToAdd = (int) Math.min(Integer.MAX_VALUE, (long) placedBlocks * xpPerBlock);
        taskRuntime.pendingBuilderXp.updateAndGet(current -> (int) Math.min(Integer.MAX_VALUE, (long) current + xpToAdd));
    }

    private static void flushPendingBuilderXp(ServerLevel level, LevelRuntime runtime) {
        if (level == null || runtime == null) {
            return;
        }
        runtime.tasksByCitizen.values().forEach(taskRuntime ->
                CitizenService.findCitizen(level, taskRuntime.task.citizenId())
                        .filter(citizen -> !citizen.dead())
                        .ifPresent(citizen -> flushPendingBuilderXp(level, citizen, taskRuntime)));
    }

    private static void flushPendingBuilderXp(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime) {
        if (level == null || citizen == null || taskRuntime == null) {
            return;
        }
        int xpToAdd = taskRuntime.pendingBuilderXp.getAndSet(0);
        if (xpToAdd <= 0) {
            return;
        }
        CitizenLevelService.LevelUpdateResult result = CitizenLevelService.addExperience(level, citizen.uuid(), CityJobType.BUILDER, xpToAdd);
        if (result.leveledUp()) {
            SimuKraft.LOGGER.info("Simukraft: Builder {} leveled up to Lv.{} after gaining {} xp", citizen.name(), result.after().level(), xpToAdd);
        }
    }

    private static void spawnBuildParticles(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        level.sendParticles(
                ParticleTypes.CLOUD,
                pos.getX() + 0.5D,
                pos.getY() + 0.18D,
                pos.getZ() + 0.5D,
                4,
                0.18D,
                0.12D,
                0.18D,
                0.015D
        );
    }

    private static int consumeBuildBudget(TaskRuntime taskRuntime, CitizenData citizen) {
        if (taskRuntime == null || citizen == null) {
            return 0;
        }
        taskRuntime.buildProgressAccumulator += builderBlocksPerTick(citizen);
        int blockBudget = Math.min(128, (int) taskRuntime.buildProgressAccumulator);
        taskRuntime.buildProgressAccumulator -= blockBudget;
        return blockBudget;
    }

    private static double builderBlocksPerTick(CitizenData citizen) {
        double baseBlocksPerSecond = ServerConfig.builderBlocksPerSecond();
        CitizenSkillSnapshot skill = CitizenLevelService.snapshot(citizen, CityJobType.BUILDER);
        if (skill.maxLevel() <= 1) {
            return Math.min(128.0D, baseBlocksPerSecond / 20.0D);
        }
        double progress = (skill.level() - 1) / (double) (skill.maxLevel() - 1);
        double blocksPerSecond = baseBlocksPerSecond * (1.0D + progress * 19.0D);
        return Math.min(128.0D, blocksPerSecond / 20.0D);
    }

    private static boolean shouldPersist(TaskRuntime taskRuntime, BuildingTaskData task) {
        return task.currentBlockIndex() - taskRuntime.lastSavedIndex >= SAVE_BLOCK_INTERVAL
                || task.updatedAt() - taskRuntime.lastSavedAt >= SAVE_INTERVAL_MS
                || BuildingTaskStatus.COMPLETED.id().equalsIgnoreCase(task.status())
                || BuildingTaskStatus.from(task.status()).isPaused();
    }

    private static void pauseTask(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime, BuildingTaskStatus status, String statusLabel) {
        BuildingTaskData task = taskRuntime.task;
        if (BuildingTaskStatus.from(task.status()) == status
                && citizen.workStatusType() == CitizenWorkStatus.RESTING
                && statusLabel.equals(citizen.statusLabel())) {
            return;
        }
        BuildingTaskData paused = withStatus(task, status);
        taskRuntime.task = paused;
        taskRuntime.dirty = true;
        taskRuntime.lastPhaseKey = status.id();
        citizen.setWorkStatus(CitizenWorkStatus.RESTING);
        citizen.setStatusLabel(statusLabel);
        citizen.setWorkNeedDetail("build:" + paused.taskId() + ":" + status.id());
        CitizenService.save(level, citizen.uuid());
        persistTaskAsync(level, taskRuntime, paused);
    }

    private static BuildingTaskData resumeTask(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime) {
        BuildingTaskData resumed = withStatus(taskRuntime.task, BuildingTaskStatus.BUILDING);
        taskRuntime.task = resumed;
        taskRuntime.dirty = true;
        taskRuntime.lastPhaseKey = "";
        citizen.setWorkStatus(CitizenWorkStatus.WORKING);
        CitizenService.save(level, citizen.uuid());
        persistTaskAsync(level, taskRuntime, resumed);
        CitizenWorkplaceMoveService.returnToWorkplace(level, citizen);
        return resumed;
    }

    private static BuildingTaskData withStatus(BuildingTaskData task, BuildingTaskStatus status) {
        return new BuildingTaskData(
                task.taskId(),
                task.citizenId(),
                task.cityId(),
                task.dimensionId(),
                task.buildBoxPos(),
                task.category(),
                task.buildingFileName(),
                task.displayName(),
                task.amount(),
                task.structureFileName(),
                task.origin(),
                task.rotationDegrees(),
                task.currentBlockIndex(),
                task.totalBlocks(),
                status.id(),
                task.createdAt(),
                System.currentTimeMillis(),
                task.poiDefinitions()
        );
    }

    private static boolean shouldRest(ServerLevel level) {
        if (!ServerConfig.builderPauseAtNight()) {
            return false;
        }
        int time = (int) Math.floorMod(level.getDayTime(), 24000L);
        int start = ServerConfig.builderRestStartTime();
        int end = ServerConfig.builderRestEndTime();
        if (start == end) {
            return false;
        }
        if (start < end) {
            return time >= start && time < end;
        }
        return time >= start || time < end;
    }

    private static void persistTaskAsync(ServerLevel level, TaskRuntime taskRuntime, BuildingTaskData snapshot) {
        if (taskRuntime.saveInFlight) {
            return;
        }
        taskRuntime.saveInFlight = true;
        // 保存使用快照，防止 IO 线程读取到 taskRuntime 正在变化的中间状态。
        CompletableFuture.runAsync(() -> SimuSqliteStorage.saveBuildingTask(level, snapshot), IO_EXECUTOR)
                .whenComplete((unused, throwable) -> {
                    taskRuntime.saveInFlight = false;
                    if (throwable != null) {
                        SimuKraft.LOGGER.error("Simukraft: Failed to persist building task {}", snapshot.taskId(), throwable);
                        return;
                    }
                    taskRuntime.lastSavedIndex = snapshot.currentBlockIndex();
                    taskRuntime.lastSavedAt = snapshot.updatedAt();
                    if (snapshot.equals(taskRuntime.task)) {
                        taskRuntime.dirty = false;
                    }
                });
    }

    private static void primeStructureLoad(BuildingTaskData task) {
        STRUCTURE_CACHE.computeIfAbsent(structureKey(task), ignored ->
                CompletableFuture.supplyAsync(() -> loadStructure(task), IO_EXECUTOR)
        );
    }

    private static CachedStructure resolveCached(BuildingTaskData task) {
        CompletableFuture<CachedStructure> future = STRUCTURE_CACHE.computeIfAbsent(structureKey(task), ignored ->
                CompletableFuture.supplyAsync(() -> loadStructure(task), IO_EXECUTOR)
        );
        if (!future.isDone()) {
            return null;
        }
        try {
            return future.join();
        } catch (CompletionException exception) {
            SimuKraft.LOGGER.error("Simukraft: Failed to preload structure {}", structureKey(task), exception);
            return null;
        }
    }

    private static CachedStructure loadStructure(BuildingTaskData task) {
        Optional<BuildingStructure> structureOptional = BuildingStructureService.loadStructure(task.category(), task.buildingFileName());
        if (structureOptional.isEmpty()) {
            return null;
        }
        BuildingStructure structure = structureOptional.get();
        List<BuildingBlockData> sourceBlocks = List.copyOf(structure.blocks());
        List<BuildingBlockData> placedBlocks = BuildingStructureService.resolvePlacedBlocks(structure, task.origin(), task.rotationDegrees()).stream()
                .sorted(Comparator.comparingInt((BuildingBlockData block) -> block.relativePos().getY()).thenComparingInt(block -> block.relativePos().getX()).thenComparingInt(block -> block.relativePos().getZ()))
                .toList();
        return new CachedStructure(placedBlocks, sourceBlocks, buildLayerRanges(placedBlocks));
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return LEVEL_RUNTIMES.computeIfAbsent(runtimeKey(level), ignored -> new LevelRuntime());
    }

    private static String runtimeKey(ServerLevel level) {
        return SaveScopedCacheKey.levelKey(level).toLowerCase(Locale.ROOT);
    }

    private static String structureKey(BuildingTaskData task) {
        // 同一个建筑文件放在不同原点/旋转时，世界坐标不同，必须使用不同缓存键。
        return task.category() + ":" + task.buildingFileName() + ":" + task.origin().toShortString() + ":" + task.rotationDegrees();
    }

    private static void syncCitizenTaskState(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime, BuildingTaskData task, CachedStructure cached) {
        if (level == null || citizen == null || task == null) {
            return;
        }
        String phaseKey;
        String statusLabel;
        String workNeedDetail;
        BuildingTaskStatus status = BuildingTaskStatus.from(task.status());
        // 状态文本同时写入持久化数据，客户端实体重载后也能恢复显示。
        if (status == BuildingTaskStatus.WAITING_MATERIALS) {
            String materialName = taskRuntime.missingMaterialName == null || taskRuntime.missingMaterialName.isBlank()
                    ? "未知材料"
                    : taskRuntime.missingMaterialName;
            phaseKey = "missing:" + materialName;
            statusLabel = "缺少材料: " + materialName;
            workNeedDetail = "build:" + task.taskId() + ":missing=" + materialName;
        } else if (status.isPaused()) {
            phaseKey = status.id();
            statusLabel = status == BuildingTaskStatus.PAUSED_RESTING ? "夜间休息中: " + task.displayName() : "建造暂停中: " + task.displayName();
            workNeedDetail = "build:" + task.taskId() + ":" + status.id();
        } else if (cached == null) {
            phaseKey = "loading";
            statusLabel = "建造准备中: " + task.displayName();
            workNeedDetail = "build:" + task.taskId() + ":loading";
        } else {
            LayerRange layerRange = resolveCurrentLayerRange(cached, task.currentBlockIndex());
            if (layerRange == null) {
                phaseKey = "finalizing";
                statusLabel = "建造收尾中: " + task.displayName();
                workNeedDetail = "build:" + task.taskId() + ":finalizing";
            } else {
                int layerNumber = layerRange.layerIndex() + 1;
                int totalLayers = cached.layerRanges().size();
                phaseKey = "layer:" + layerNumber + "/" + totalLayers;
                statusLabel = "建造中: " + task.displayName() + " 第" + layerNumber + "/" + totalLayers + "层";
                workNeedDetail = "build:" + task.taskId() + ":layer=" + layerNumber + "/" + totalLayers;
            }
        }
        CitizenWorkStatus workStatus = status.isPaused() ? CitizenWorkStatus.RESTING : CitizenWorkStatus.WORKING;
        if (phaseKey.equals(taskRuntime.lastPhaseKey)
                && workStatus == citizen.workStatusType()
                && statusLabel.equals(citizen.statusLabel())
                && workNeedDetail.equals(citizen.workNeedDetail())) {
            return;
        }
        citizen.setWorkStatus(workStatus);
        citizen.setStatusLabel(statusLabel);
        citizen.setWorkNeedDetail(workNeedDetail);
        taskRuntime.lastPhaseKey = phaseKey;
        CitizenService.save(level, citizen.uuid());
    }

    private static void markWaitingForMaterials(ServerLevel level, CitizenData citizen, TaskRuntime taskRuntime, BuildingTaskData task, WorkMaterialResult materialResult) {
        if (level.getGameTime() < taskRuntime.nextMaterialRetryTick && BuildingTaskStatus.from(task.status()) == BuildingTaskStatus.WAITING_MATERIALS) {
            syncCitizenTaskState(level, citizen, taskRuntime, task, null);
            return;
        }
        taskRuntime.materialCache.markDirty();
        long now = System.currentTimeMillis();
        BuildingTaskData waitingTask = new BuildingTaskData(
                task.taskId(),
                task.citizenId(),
                task.cityId(),
                task.dimensionId(),
                task.buildBoxPos(),
                task.category(),
                task.buildingFileName(),
                task.displayName(),
                task.amount(),
                task.structureFileName(),
                task.origin(),
                task.rotationDegrees(),
                task.currentBlockIndex(),
                task.totalBlocks(),
                BuildingTaskStatus.WAITING_MATERIALS.id(),
                task.createdAt(),
                now,
                task.poiDefinitions()
        );
        taskRuntime.task = waitingTask;
        taskRuntime.dirty = true;
        taskRuntime.missingMaterialName = BuilderMaterialService.describe(materialResult.requested());
        taskRuntime.nextMaterialRetryTick = level.getGameTime() + MATERIAL_RETRY_INTERVAL_TICKS;
        syncCitizenTaskState(level, citizen, taskRuntime, waitingTask, null);
        persistTaskAsync(level, taskRuntime, waitingTask);
    }

    private static List<LayerRange> buildLayerRanges(List<BuildingBlockData> blocks) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<LayerRange> ranges = new java.util.ArrayList<>();
        int layerStartIndex = 0;
        int currentY = blocks.getFirst().relativePos().getY();
        int layerIndex = 0;
        for (int i = 1; i < blocks.size(); i++) {
            int blockY = blocks.get(i).relativePos().getY();
            if (blockY == currentY) {
                continue;
            }
            ranges.add(new LayerRange(layerIndex++, currentY, layerStartIndex, i - 1));
            currentY = blockY;
            layerStartIndex = i;
        }
        ranges.add(new LayerRange(layerIndex, currentY, layerStartIndex, blocks.size() - 1));
        return List.copyOf(ranges);
    }

    private static LayerRange resolveCurrentLayerRange(CachedStructure cached, int currentBlockIndex) {
        if (cached == null || cached.layerRanges().isEmpty()) {
            return null;
        }
        int index = Math.max(0, currentBlockIndex);
        for (LayerRange layerRange : cached.layerRanges()) {
            if (index <= layerRange.endIndex()) {
                return layerRange;
            }
        }
        return null;
    }

    private static BlockPos resolvePoiPosition(List<BuildingBlockData> sourceBlocks, BuildingPoiDefinition poi, BlockPos origin, int rotationDegrees) {
        if (poi.poiType() == CityPoiType.RESIDENTIAL) {
            for (BuildingBlockData block : sourceBlocks) {
                if (isResidentialBed(block.state())) {
                    return origin.offset(BuildingTransform.rotatePosition(block.relativePos(), rotationDegrees));
                }
            }
        }
        String key = poi.id().toLowerCase(java.util.Locale.ROOT);
        for (BuildingBlockData block : sourceBlocks) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString().toLowerCase(java.util.Locale.ROOT);
            if (blockId.contains(key) || (poi.poiType() != CityPoiType.RESIDENTIAL && blockId.contains("control_box"))) {
                return origin.offset(BuildingTransform.rotatePosition(block.relativePos(), rotationDegrees));
            }
        }
        return origin;
    }

    private static List<BuildingPoiInstance> resolvePoiInstances(List<BuildingBlockData> sourceBlocks, List<BuildingBlockData> placedBlocks, BuildingTaskData task) {
        List<BuildingPoiDefinition> pois = task.poiDefinitions();
        List<BuildingPoiInstance> rewritten = new java.util.ArrayList<>();
        if (shouldRegisterResidentialBeds(task.category(), pois)) {
            rewritten.addAll(resolveResidentialBedInstances(placedBlocks));
        }
        for (BuildingPoiDefinition poi : pois) {
            if (poi.poiType() == CityPoiType.RESIDENTIAL) {
                continue;
            }
            BlockPos pos = resolvePoiPosition(sourceBlocks, poi, task.origin(), task.rotationDegrees());
            String key = UUID.nameUUIDFromBytes((poi.id() + "@" + pos.toShortString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            rewritten.add(new BuildingPoiInstance(key, poi.poiType(), poi.capacity(), pos));
        }
        return List.copyOf(rewritten);
    }

    private static boolean shouldRegisterResidentialBeds(String category, List<BuildingPoiDefinition> pois) {
        return isResidentialCategory(category)
                || pois.stream().anyMatch(poi -> poi.poiType() == CityPoiType.RESIDENTIAL);
    }

    private static List<BuildingPoiInstance> resolveResidentialBedInstances(List<BuildingBlockData> placedBlocks) {
        return placedBlocks.stream()
                .filter(block -> isResidentialBed(block.state()))
                .map(block -> block.relativePos().immutable())
                .distinct()
                .map(pos -> new BuildingPoiInstance(
                        UUID.nameUUIDFromBytes(("bed:" + pos.toShortString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                        CityPoiType.RESIDENTIAL,
                        1,
                        pos
                ))
                .toList();
    }

    public static List<BuildingPoiInstance> resolveResidentialBedPois(PlacedBuildingRecord building) {
        if (building == null || !isResidentialCategory(building.category())) {
            return List.of();
        }
        return resolveResidentialBedInstances(building.blocks());
    }

    private static boolean isResidentialBed(BlockState state) {
        // 住宅容量只统计建造流程放下的红床床头，避免一张床的床尾重复算容量。
        return state.is(Blocks.RED_BED)
                && (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                || state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD);
    }

    private static boolean isResidentialCategory(String category) {
        return "residential".equalsIgnoreCase(category);
    }

    private static UUID stablePoiId(BuildingPoiInstance poi) {
        try {
            return UUID.fromString(poi.key());
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes((poi.poiType().name() + "@" + poi.worldPos().toShortString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private record CachedStructure(List<BuildingBlockData> blocks, List<BuildingBlockData> sourceBlocks, List<LayerRange> layerRanges) {
    }

    private record LayerRange(int layerIndex, int y, int startIndex, int endIndex) {
    }

    private static final class LevelRuntime {
        private final ConcurrentMap<UUID, TaskRuntime> tasksByCitizen = new ConcurrentHashMap<>();
        private volatile boolean hydrated;
        private volatile CompletableFuture<List<BuildingTaskData>> loadFuture;
    }

    private static final class TaskRuntime {
        private volatile BuildingTaskData task;
        private final WorkMaterialCache materialCache;
        private volatile boolean dirty;
        private volatile boolean saveInFlight;
        private volatile int lastSavedIndex;
        private volatile long lastSavedAt;
        private volatile String lastPhaseKey = "";
        private volatile String missingMaterialName = "";
        private volatile long nextMaterialRetryTick;
        private double buildProgressAccumulator;
        private final AtomicInteger pendingBuilderXp = new AtomicInteger();

        private TaskRuntime(BuildingTaskData task) {
            this.task = task;
            this.materialCache = new WorkMaterialCache(task.buildBoxPos());
            this.lastSavedIndex = task.currentBlockIndex();
            this.lastSavedAt = task.updatedAt();
        }
    }

    private static final class BuilderIoThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "simukraft-builder-io-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
