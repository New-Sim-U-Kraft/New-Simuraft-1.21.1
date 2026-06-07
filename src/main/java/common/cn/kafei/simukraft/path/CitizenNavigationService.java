package common.cn.kafei.simukraft.path;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.network.path.NpcPathDebugSyncPacket;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("null")
public final class CitizenNavigationService {
    private static final ConcurrentMap<String, LevelRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final double PASSED_WAYPOINT_DOT_EPSILON = 1.05D;
    private static final double PASSED_WAYPOINT_LATERAL_TOLERANCE = 0.45D;
    private static final double WALK_PASSED_VERTICAL_TOLERANCE = 1.0D;
    private static final double ACTION_PASSED_VERTICAL_TOLERANCE = 2.25D;
    private static final double TURN_DOT_THRESHOLD = 0.906D;
    private static final double STALLED_SOFT_SKIP_DISTANCE = 2.25D;
    private static final double ACTION_START_DISTANCE = 0.65D;
    private static final double CLIMB_VERTICAL_ARRIVAL_DISTANCE = 0.03D;
    private static final double CLIMB_VERTICAL_ASSIST_DISTANCE = 0.75D;
    private static final double CLIMB_VERTICAL_SPEED = 0.16D;
    private static final double CLIMB_VERTICAL_SPEED_FACTOR = 0.22D;
    private static final double CLIMB_EXIT_DETACH_HORIZONTAL_SPEED = 0.09D;
    private static final double CLIMB_EXIT_DROP_SPEED = -0.12D;
    private static final double CORNER_ARRIVAL_DISTANCE = 0.30D;
    private static final double SEGMENT_LOOKAHEAD_BLOCKS = 1.15D;
    private static final double CORNER_LOOKAHEAD_BLOCKS = 0.55D;
    private static final int STALLED_SOFT_SKIP_TICKS = 20;
    private static final int STALLED_REPATH_TICKS = 80;
    private static final int MAX_CROWD_YIELD_TICKS = 45;
    private static final int STALLED_TELEPORT_TICKS = 1200;
    private static final int DEBUG_SYNC_PATH_LIMIT = 96;
    private static final double DEBUG_SYNC_RADIUS = 192.0D;
    private static final double DOOR_INTERACT_RANGE_SQR = 9.0D;
    private static final double DOOR_CLEAR_RANGE_SQR = 2.25D;
    private static final double DOOR_DOORWAY_RANGE_SQR = 1.44D;
    private static final int MAX_TRACKED_DOORS = 128;
    private static volatile ExecutorService pathExecutor;
    private static volatile int executorSize;

    private CitizenNavigationService() {
    }

    public static boolean requestMove(ServerLevel level, UUID citizenId, Vec3 target, MovementIntent intent) {
        return requestMove(level, citizenId, target, intent, false);
    }

    public static boolean requestTestMove(ServerLevel level, UUID citizenId, Vec3 target, MovementIntent intent) {
        return requestMove(level, citizenId, target, intent, true);
    }

    private static boolean requestMove(ServerLevel level, UUID citizenId, Vec3 target, MovementIntent intent, boolean bypassAdmissionLimits) {
        if (level == null || citizenId == null || target == null) {
            return false;
        }
        MovementIntent normalizedIntent = intent != null ? intent : MovementIntent.WALK;
        CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, citizenId);
        if (citizen == null) {
            return false;
        }
        Vec3 current = citizen.position();
        double distanceSqr = current.distanceToSqr(target);
        double farDistance = localPathDistanceLimit();
        if (distanceSqr >= farDistance * farDistance || !hasLoadedChunk(level, BlockPos.containing(target.x, target.y, target.z))) {
            return CitizenTeleportService.teleportCitizen(level, citizenId, target);
        }

        LevelRuntime runtime = runtime(level);
        if (normalizedIntent == MovementIntent.SELF_FEEDING) {
            clearLowerPriorityNavigation(level, runtime, citizenId, citizen);
        } else if (normalizedIntent == MovementIntent.WORK && hasSelfFeedingNavigation(runtime, citizenId)) {
            return false;
        }
        ActiveNavigation active = runtime.active.get(citizenId);
        if (active != null && active.sameTarget(target)) {
            return true;
        }
        if (runtime.pending.containsKey(citizenId)) {
            return true;
        }
        PathRequest queued = runtime.latestRequests.get(citizenId);
        if (queued != null && queued.target().distanceToSqr(target) <= 4.0D) {
            return true;
        }
        Long cooldownUntil = runtime.cooldowns.get(citizenId);
        if (cooldownUntil != null && cooldownUntil > level.getGameTime()) {
            return false;
        }
        if (!bypassAdmissionLimits) {
            if (!runtime.active.containsKey(citizenId) && runtime.active.size() >= ServerConfig.pathMaxActiveCitizens()) {
                return false;
            }
            if (countLoadedCitizens(level, runtime) > ServerConfig.pathMaxLoadedCitizenEntities()) {
                return false;
            }
        }

        PathRequest request = new PathRequest(citizenId, level.dimension().location(), citizen.blockPosition(), target, normalizedIntent, level.getGameTime());
        runtime.latestRequests.put(citizenId, request);
        if (runtime.queuedCitizenIds.add(citizenId)) {
            runtime.queue.offer(citizenId);
        }
        return true;
    }

    /** hasSelfFeedingNavigation: 判断买饭导航是否正在占用该 NPC，避免普通工作移动抢占。 */
    private static boolean hasSelfFeedingNavigation(LevelRuntime runtime, UUID citizenId) {
        ActiveNavigation active = runtime.active.get(citizenId);
        if (active != null && active.intent == MovementIntent.SELF_FEEDING) {
            return true;
        }
        RunningRequest running = runtime.pending.get(citizenId);
        if (running != null && running.cacheKey().intent() == MovementIntent.SELF_FEEDING) {
            return true;
        }
        PathRequest queued = runtime.latestRequests.get(citizenId);
        return queued != null && queued.intent() == MovementIntent.SELF_FEEDING;
    }

    /** clearLowerPriorityNavigation: 买饭开始时清掉旧的普通工作导航，防止两套状态轮流改目标。 */
    private static void clearLowerPriorityNavigation(ServerLevel level, LevelRuntime runtime, UUID citizenId, CitizenEntity citizen) {
        ActiveNavigation active = runtime.active.get(citizenId);
        if (active != null && active.intent != MovementIntent.SELF_FEEDING) {
            runtime.active.remove(citizenId);
            citizen.getNavigation().stop();
            PathCrowdCoordinator.clear(level, citizenId);
        }
        RunningRequest running = runtime.pending.get(citizenId);
        if (running != null && running.cacheKey().intent() != MovementIntent.SELF_FEEDING) {
            RunningRequest removed = runtime.pending.remove(citizenId);
            if (removed != null) {
                removed.future().cancel(false);
            }
        }
        PathRequest queued = runtime.latestRequests.get(citizenId);
        if (queued != null && queued.intent() != MovementIntent.SELF_FEEDING) {
            runtime.latestRequests.remove(citizenId);
            runtime.queuedCitizenIds.remove(citizenId);
        }
    }

    public static void stop(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        runtime.latestRequests.remove(citizenId);
        runtime.blockedSince.remove(citizenId);
        RunningRequest running = runtime.pending.remove(citizenId);
        if (running != null) {
            running.future.cancel(false);
        }
        ActiveNavigation active = runtime.active.remove(citizenId);
        CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, citizenId);
        if (active != null && citizen != null) {
            citizen.getNavigation().stop();
        }
        PathCrowdCoordinator.clear(level, citizenId);
    }

    public static boolean isNavigating(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return false;
        }
        LevelRuntime runtime = runtime(level);
        return runtime.active.containsKey(citizenId) || runtime.pending.containsKey(citizenId) || runtime.latestRequests.containsKey(citizenId);
    }

    public static boolean debugPathTo(ServerPlayer player, Vec3 target) {
        if (player == null || target == null) {
            return false;
        }
        CitizenEntity citizen = findNearestLoadedCitizen(player.serverLevel(), player.position(), ServerConfig.pathLocalRadiusBlocks());
        if (citizen == null) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.path_debug.no_citizen"));
            PacketDistributor.sendToPlayer(player, NpcPathDebugSyncPacket.clear());
            return false;
        }
        return debugPathTo(player, citizen, target);
    }

    public static boolean debugPathTo(ServerPlayer player, CitizenEntity citizen, Vec3 target) {
        if (player == null || citizen == null || target == null || !(citizen.level() instanceof ServerLevel level)) {
            return false;
        }
        if (level != player.serverLevel()) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.path_debug.failed", "citizen_dimension_mismatch"));
            return false;
        }
        BlockPos targetPos = BlockPos.containing(target.x, target.y, target.z);
        if (!hasLoadedChunk(level, targetPos)) {
            sendDebugFailure(player, citizen.getUUID(), "target_chunk_not_loaded");
            return false;
        }
        double radius = ServerConfig.pathLocalRadiusBlocks();
        if (citizen.position().distanceToSqr(target) > radius * radius) {
            sendDebugFailure(player, citizen.getUUID(), "target_outside_local_radius");
            return false;
        }

        PathRequest request = new PathRequest(citizen.getUUID(), level.dimension().location(), citizen.blockPosition(), target, MovementIntent.RUN, level.getGameTime());
        PathSnapshot snapshot = PathSnapshotBuilder.build(level, request.startPos(), request.targetBlockPos(), ServerConfig.pathLocalRadiusBlocks());
        InfoToastService.send(player, Component.translatable("message.simukraft.path_debug.started", citizen.getName().getString(), formatTarget(target)));
        CompletableFuture<PathResult> future = CompletableFuture.supplyAsync(() -> HybridPathfinder.find(request, snapshot), executor());
        future.whenComplete((result, throwable) -> level.getServer().execute(() -> applyDebugPath(level, player, citizen.getUUID(), result, throwable)));
        return true;
    }

    public static void clearDebugPath(ServerPlayer player) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, NpcPathDebugSyncPacket.clear());
            InfoToastService.send(player, Component.translatable("message.simukraft.path_debug.cleared"));
        }
    }

    public static boolean sendStatus(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        LevelRuntime runtime = runtime(level);
        runtime.cooldowns.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        InfoToastService.send(player, Component.translatable(
                "message.simukraft.path_status.summary",
                runtime.queuedCitizenIds.size(),
                runtime.pending.size(),
                runtime.active.size(),
                runtime.cooldowns.size()));
        PathRuntimeIssue issue = nearestIssue(level, runtime, player.position());
        if (issue != null) {
            InfoToastService.warning(player, Component.translatable(
                    "message.simukraft.path_status.issue",
                    shortId(issue.citizenId()),
                    issue.status(),
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(issue.distanceToTargetSqr())),
                    issue.waypointIndex(),
                    issue.waypointCount()));
        } else {
            InfoToastService.success(player, Component.translatable("message.simukraft.path_status.no_issue"));
        }
        syncDebugPaths(level, player);
        return true;
    }

    public static void syncDebugPaths(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        PacketDistributor.sendToPlayer(player, NpcPathDebugSyncPacket.clear());
        double maxDistanceSqr = DEBUG_SYNC_RADIUS * DEBUG_SYNC_RADIUS;
        List<DebugPathEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, ActiveNavigation> entry : runtime.active.entrySet()) {
            CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, entry.getKey());
            if (citizen == null) {
                continue;
            }
            double distanceSqr = citizen.position().distanceToSqr(player.position());
            if (distanceSqr <= maxDistanceSqr) {
                entries.add(new DebugPathEntry(entry.getKey(), entry.getValue(), distanceSqr));
            }
        }
        entries.sort(Comparator.comparingDouble(DebugPathEntry::distanceSqr));
        int sent = 0;
        for (DebugPathEntry entry : entries) {
            if (sent >= DEBUG_SYNC_PATH_LIMIT) {
                break;
            }
            PacketDistributor.sendToPlayer(player, NpcPathDebugSyncPacket.fromWaypoints(entry.citizenId(), entry.navigation().waypoints, entry.navigation().debugStatus()));
            sent++;
        }
        if (sent > 0) {
            InfoToastService.send(player, Component.translatable("message.simukraft.path_debug.synced", sent, runtime.active.size()));
        }
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        applyCompletedPaths(level, runtime);
        tickActivePaths(level, runtime);
        processOpenedDoors(level, runtime);
        processQueuedRequests(level, runtime);
        if (level.getGameTime() % 200L == 0L) {
            runtime.pathCache.cleanup(level.getGameTime());
            runtime.cooldowns.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
            PathCrowdCoordinator.cleanup(level);
        }
    }

    public static void invalidate(ServerLevel level, BlockPos changedPos) {
        if (level == null || changedPos == null) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        runtime.pathCache.clear();
        runtime.snapshotCache.clear();
    }

    public static void clearServerCaches(MinecraftServer server) {
        if (server != null) {
            String prefix = SaveKey.serverKey(server) + "|";
            RUNTIMES.keySet().removeIf(key -> key.startsWith(prefix));
        } else {
            RUNTIMES.clear();
        }
        PathCrowdCoordinator.clearServerCaches(server);
        ExecutorService executor = pathExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        pathExecutor = null;
        executorSize = 0;
    }

    private static void processQueuedRequests(ServerLevel level, LevelRuntime runtime) {
        int processed = 0;
        int budget = Math.max(0, ServerConfig.pathMaxNewRequestsPerTick());
        while (processed < budget) {
            if (runtime.active.size() + runtime.pending.size() >= ServerConfig.pathMaxActiveCitizens()) {
                return;
            }
            UUID citizenId = runtime.queue.poll();
            if (citizenId == null) {
                return;
            }
            runtime.queuedCitizenIds.remove(citizenId);
            PathRequest request = runtime.latestRequests.remove(citizenId);
            if (request == null || runtime.pending.containsKey(citizenId)) {
                continue;
            }
            CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, citizenId);
            if (citizen == null) {
                continue;
            }
            PathRequest currentRequest = new PathRequest(citizenId, request.dimensionId(), citizen.blockPosition(), request.target(), request.intent(), level.getGameTime());
            PathCacheKey cacheKey = new PathCacheKey(currentRequest.dimensionId(), currentRequest.startPos(), currentRequest.targetBlockPos(), currentRequest.intent());
            PathResult cached = runtime.pathCache.get(cacheKey, level.getGameTime());
            if (cached != null) {
                activate(level, runtime, cached);
                processed++;
                continue;
            }
            PathSnapshot snapshot = runtime.snapshotCache.acquire(level, currentRequest.startPos(), currentRequest.targetBlockPos(), ServerConfig.pathLocalRadiusBlocks());
            CompletableFuture<PathResult> future = CompletableFuture.supplyAsync(() -> HybridPathfinder.find(currentRequest, snapshot), executor());
            runtime.pending.put(citizenId, new RunningRequest(future, cacheKey));
            processed++;
        }
    }

    private static void applyCompletedPaths(ServerLevel level, LevelRuntime runtime) {
        for (Iterator<Map.Entry<UUID, RunningRequest>> iterator = runtime.pending.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, RunningRequest> entry = iterator.next();
            RunningRequest running = entry.getValue();
            if (!running.future.isDone()) {
                continue;
            }
            iterator.remove();
            PathResult result;
            try {
                result = running.future.join();
            } catch (RuntimeException exception) {
                runtime.cooldowns.put(entry.getKey(), level.getGameTime() + ServerConfig.pathRepathCooldownTicks());
                SimuKraft.LOGGER.warn("Simukraft: NPC path calculation failed for {}", entry.getKey(), exception);
                continue;
            }
            if (result.success()) {
                runtime.pathCache.put(running.cacheKey, result, level.getGameTime(), ServerConfig.pathCacheTtlTicks());
                activate(level, runtime, result);
            } else {
                runtime.cooldowns.remove(result.citizenId());
                runtime.blockedSince.remove(result.citizenId());
                CitizenTeleportService.teleportCitizen(level, result.citizenId(), result.target());
                if (ServerConfig.pathDebugEnabled()) {
                    SimuKraft.LOGGER.info("Simukraft: NPC path failed for {}, teleporting to target: {}", result.citizenId(), result.reason());
                }
            }
        }
    }

    private static void activate(ServerLevel level, LevelRuntime runtime, PathResult result) {
        if (!result.success() || result.waypoints().isEmpty()) {
            return;
        }
        CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, result.citizenId());
        if (citizen == null) {
            return;
        }
        runtime.active.put(result.citizenId(), new ActiveNavigation(result));
    }

    private static void tickActivePaths(ServerLevel level, LevelRuntime runtime) {
        for (Iterator<Map.Entry<UUID, ActiveNavigation>> iterator = runtime.active.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, ActiveNavigation> entry = iterator.next();
            CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, entry.getKey());
            if (citizen == null) {
                iterator.remove();
                continue;
            }
            ActiveTickResult result = entry.getValue().tick(level, citizen, runtime);
            if (result == ActiveTickResult.RUNNING) {
                continue;
            }
            citizen.getNavigation().stop();
            PathCrowdCoordinator.clear(level, entry.getKey());
            iterator.remove();
            if (result == ActiveTickResult.REPATH) {
                ActiveNavigation active = entry.getValue();
                long blockedSince = runtime.blockedSince.computeIfAbsent(entry.getKey(), id -> level.getGameTime());
                if (level.getGameTime() - blockedSince >= STALLED_TELEPORT_TICKS) {
                    CitizenTeleportService.teleportCitizen(level, entry.getKey(), active.target);
                    runtime.latestRequests.remove(entry.getKey());
                    runtime.cooldowns.remove(entry.getKey());
                    runtime.blockedSince.remove(entry.getKey());
                    continue;
                }
                runtime.cooldowns.put(entry.getKey(), level.getGameTime() + 20L);
                PathRequest request = new PathRequest(entry.getKey(), level.dimension().location(), citizen.blockPosition(), active.target, active.intent, level.getGameTime());
                runtime.latestRequests.put(entry.getKey(), request);
                if (runtime.queuedCitizenIds.add(entry.getKey())) {
                    runtime.queue.offer(entry.getKey());
                }
            } else {
                runtime.blockedSince.remove(entry.getKey());
            }
        }
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(runtimeKey(level), key -> new LevelRuntime());
    }

    private static String runtimeKey(ServerLevel level) {
        return SaveKey.serverKey(level.getServer()) + "|" + level.dimension().location();
    }

    /**
     * Counts the citizen entities loaded in the level, memoizing the result for the current tick so
     * the many move requests issued per tick share a single full entity scan.
     */
    private static int countLoadedCitizens(ServerLevel level, LevelRuntime runtime) {
        long gameTime = level.getGameTime();
        if (runtime.loadedCitizenCountTick == gameTime) {
            return runtime.loadedCitizenCount;
        }
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof CitizenEntity) {
                count++;
            }
        }
        runtime.loadedCitizenCountTick = gameTime;
        runtime.loadedCitizenCount = count;
        return count;
    }

    private static boolean hasLoadedChunk(ServerLevel level, BlockPos pos) {
        return level != null
                && pos != null
                && level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private static CitizenEntity findNearestLoadedCitizen(ServerLevel level, Vec3 origin, double radius) {
        if (level == null || origin == null) {
            return null;
        }
        double radiusSqr = Math.max(8.0D, radius) * Math.max(8.0D, radius);
        CitizenEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (!(entity instanceof CitizenEntity citizen) || citizen.isRemoved()) {
                continue;
            }
            double distance = citizen.position().distanceToSqr(origin);
            if (distance <= radiusSqr && distance < nearestDistance) {
                nearest = citizen;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static void applyDebugPath(ServerLevel level, ServerPlayer player, UUID citizenId, PathResult result, Throwable throwable) {
        if (throwable != null) {
            SimuKraft.LOGGER.warn("Simukraft: NPC debug path calculation failed for {}", citizenId, throwable);
            sendDebugFailure(player, citizenId, "path_calculation_failed");
            return;
        }
        if (result == null) {
            sendDebugFailure(player, citizenId, "path_result_missing");
            return;
        }
        PacketDistributor.sendToPlayer(player, NpcPathDebugSyncPacket.fromResult(result));
        if (result.success()) {
            LevelRuntime runtime = runtime(level);
            runtime.latestRequests.remove(result.citizenId());
            RunningRequest running = runtime.pending.remove(result.citizenId());
            if (running != null) {
                running.future.cancel(false);
            }
            runtime.cooldowns.remove(result.citizenId());
            activate(level, runtime, result);
            InfoToastService.success(player, Component.translatable("message.simukraft.path_debug.success", result.waypoints().size()));
        } else {
            InfoToastService.warning(player, Component.translatable("message.simukraft.path_debug.failed", result.reason()));
        }
    }

    private static void sendDebugFailure(ServerPlayer player, UUID citizenId, String reason) {
        if (player == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, NpcPathDebugSyncPacket.failure(citizenId, reason));
        InfoToastService.warning(player, Component.translatable("message.simukraft.path_debug.failed", reason));
    }

    private static String formatTarget(Vec3 target) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", target.x, target.y, target.z);
    }

    private static int localPathDistanceLimit() {
        return Math.min(ServerConfig.pathFarMovementTeleportDistance(), ServerConfig.pathLocalRadiusBlocks());
    }

    private static String shortId(UUID id) {
        return id == null ? "unknown" : id.toString().substring(0, 8);
    }

    private static PathRuntimeIssue nearestIssue(ServerLevel level, LevelRuntime runtime, Vec3 origin) {
        PathRuntimeIssue nearest = null;
        double nearestDistanceSqr = Double.MAX_VALUE;
        for (Map.Entry<UUID, ActiveNavigation> entry : runtime.active.entrySet()) {
            ActiveNavigation navigation = entry.getValue();
            String status = navigation.debugStatus();
            if ("running".equals(status)) {
                continue;
            }
            CitizenEntity citizen = CitizenTeleportService.findCitizenEntity(level, entry.getKey());
            if (citizen == null) {
                continue;
            }
            double distanceSqr = citizen.position().distanceToSqr(origin);
            if (distanceSqr < nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearest = new PathRuntimeIssue(
                        entry.getKey(),
                        status,
                        citizen.position().distanceToSqr(navigation.target),
                        navigation.waypointIndex,
                        navigation.waypoints.size());
            }
        }
        return nearest;
    }

    /**
     * Opens the wooden door the citizen is arriving at, recording it so it can be closed once the
     * citizen has cleared the opening. Only doors this follower actually flips from closed to open
     * are tracked; a door already open is left as the world set it.
     */
    private static void tryOpenWoodenDoor(ServerLevel level, CitizenEntity citizen, PathWaypoint waypoint, LevelRuntime runtime) {
        if (level == null || citizen == null || waypoint == null) {
            return;
        }
        BlockPos doorPos = lowerWoodenDoorPos(level, waypoint.blockPos());
        if (doorPos != null) {
            if (citizen.position().distanceToSqr(Vec3.atCenterOf(doorPos)) <= DOOR_INTERACT_RANGE_SQR) {
                BlockState state = level.getBlockState(doorPos);
                if (state.getBlock() instanceof DoorBlock doorBlock && isClosedWoodenLowerDoor(state)) {
                    doorBlock.setOpen(citizen, level, state, doorPos, true);
                    trackOpenedDoor(runtime, level, citizen, doorPos);
                }
            }
            return;
        }
    }

    private static void trackOpenedDoor(LevelRuntime runtime, ServerLevel level, CitizenEntity citizen, BlockPos pos) {
        if (runtime.openedDoors.size() >= MAX_TRACKED_DOORS) {
            evictOldestDoor(runtime);
        }
        runtime.openedDoors.put(pos.asLong(), new OpenedDoor(citizen.getUUID(), level.getGameTime()));
    }

    private static void evictOldestDoor(LevelRuntime runtime) {
        Long oldestKey = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<Long, OpenedDoor> entry : runtime.openedDoors.entrySet()) {
            if (entry.getValue().openedAt() < oldestAt) {
                oldestAt = entry.getValue().openedAt();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            runtime.openedDoors.remove(oldestKey);
        }
    }

    /**
     * Closes every tracked wooden door whose opener has cleared the opening (or is gone),
     * re-reading the live block first so a door a player removed or re-closed is never forced and
     * so the door is not slammed on another citizen still in the doorway.
     */
    private static void processOpenedDoors(ServerLevel level, LevelRuntime runtime) {
        if (runtime.openedDoors.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Long, OpenedDoor>> iterator = runtime.openedDoors.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Long, OpenedDoor> entry = iterator.next();
            BlockPos pos = BlockPos.of(entry.getKey());
            BlockState state = level.getBlockState(pos);
            if (!isCloseableWoodenDoor(state)) {
                iterator.remove();
                continue;
            }
            CitizenEntity opener = CitizenTeleportService.findCitizenEntity(level, entry.getValue().citizenId());
            boolean cleared = opener == null
                    || horizontalDistanceSqr(opener.position(), Vec3.atCenterOf(pos)) > DOOR_CLEAR_RANGE_SQR;
            if (!cleared) {
                continue;
            }
            if (isOtherCitizenInDoorway(level, runtime, pos, entry.getValue().citizenId())) {
                continue;
            }
            closeWoodenDoor(level, opener, pos, state);
            iterator.remove();
        }
    }

    private static boolean isCloseableWoodenDoor(BlockState state) {
        return isOpenWoodenLowerDoor(state);
    }

    private static void closeWoodenDoor(ServerLevel level, CitizenEntity citizen, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock doorBlock && isOpenWoodenLowerDoor(state)) {
            doorBlock.setOpen(citizen, level, state, pos, false);
        }
    }

    private static boolean isOtherCitizenInDoorway(ServerLevel level, LevelRuntime runtime, BlockPos pos, UUID excludeId) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (UUID id : runtime.active.keySet()) {
            if (id.equals(excludeId)) {
                continue;
            }
            CitizenEntity other = CitizenTeleportService.findCitizenEntity(level, id);
            if (other != null && horizontalDistanceSqr(other.position(), center) <= DOOR_DOORWAY_RANGE_SQR) {
                return true;
            }
        }
        return false;
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static boolean isOpenWoodenLowerDoor(BlockState state) {
        return DoorBlock.isWoodenDoor(state)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.OPEN)
                && state.getValue(DoorBlock.OPEN)
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && !isPoweredBarrier(state);
    }

    // A redstone-held door must not be fought: closing it would just snap back open.
    private static boolean isPoweredBarrier(BlockState state) {
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
    }

    private static BlockPos lowerWoodenDoorPos(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isClosedWoodenLowerDoor(state)) {
            return pos;
        }
        if (isWoodenDoorUpper(state)) {
            BlockPos below = pos.below();
            if (isClosedWoodenLowerDoor(level.getBlockState(below))) {
                return below;
            }
        }
        return null;
    }

    private static boolean isClosedWoodenLowerDoor(BlockState state) {
        return DoorBlock.isWoodenDoor(state)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.OPEN)
                && !state.getValue(DoorBlock.OPEN)
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static boolean isWoodenDoorUpper(BlockState state) {
        return DoorBlock.isWoodenDoor(state)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static ExecutorService executor() {
        int requestedSize = Math.max(1, ServerConfig.pathWorkerThreads());
        ExecutorService existing = pathExecutor;
        if (existing != null && !existing.isShutdown() && executorSize == requestedSize) {
            return existing;
        }
        synchronized (CitizenNavigationService.class) {
            existing = pathExecutor;
            if (existing != null && !existing.isShutdown() && executorSize == requestedSize) {
                return existing;
            }
            if (existing != null) {
                existing.shutdownNow();
            }
            executorSize = requestedSize;
            pathExecutor = Executors.newFixedThreadPool(requestedSize, new PathThreadFactory());
            return pathExecutor;
        }
    }

    private static double speedFor(MovementIntent intent, MovementMode mode) {
        if (mode == MovementMode.CLIMB || mode == MovementMode.SWIM) {
            return 0.9D;
        }
        if (mode == MovementMode.RUN || intent == MovementIntent.RUN || intent == MovementIntent.RETURN_HOME) {
            return 1.2D;
        }
        if (intent == MovementIntent.WORK || intent == MovementIntent.SELF_FEEDING) {
            return 1.0D;
        }
        return 0.85D;
    }

    private enum ActiveTickResult {
        RUNNING,
        COMPLETE,
        REPATH
    }

    private static final class LevelRuntime {
        private final ConcurrentLinkedQueue<UUID> queue = new ConcurrentLinkedQueue<>();
        private final java.util.Set<UUID> queuedCitizenIds = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<UUID, PathRequest> latestRequests = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, RunningRequest> pending = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, ActiveNavigation> active = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, Long> blockedSince = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, OpenedDoor> openedDoors = new ConcurrentHashMap<>();
        private final PathResultCache pathCache = new PathResultCache();
        private final PathSnapshotCache snapshotCache = new PathSnapshotCache();
        private long loadedCitizenCountTick = Long.MIN_VALUE;
        private int loadedCitizenCount;
    }

    private record RunningRequest(CompletableFuture<PathResult> future, PathCacheKey cacheKey) {
    }

    /** A wooden door a citizen opened, tracked so it can be closed once cleared. */
    private record OpenedDoor(UUID citizenId, long openedAt) {
    }

    private record DebugPathEntry(UUID citizenId, ActiveNavigation navigation, double distanceSqr) {
    }

    private record PathRuntimeIssue(UUID citizenId, String status, double distanceToTargetSqr, int waypointIndex, int waypointCount) {
    }

    private static final class ActiveNavigation {
        private final Vec3 target;
        private final MovementIntent intent;
        private final java.util.List<PathWaypoint> waypoints;
        private int waypointIndex;
        private int stalledTicks;
        private int crowdYieldTicks;
        private int actionWaypointIndex = -1;
        private boolean jumpTriggered;
        private double lastDistance = Double.MAX_VALUE;
        private ActiveNavigation(PathResult result) {
            this.target = result.target();
            this.intent = result.intent();
            this.waypoints = result.waypoints();
            this.waypointIndex = waypoints.size() > 1 ? 1 : 0;
        }

        private boolean sameTarget(Vec3 other) {
            return other != null && target.distanceToSqr(other) <= 4.0D;
        }

        private String debugStatus() {
            if (crowdYieldTicks > 0) {
                return "crowd_yield";
            }
            if (stalledTicks > STALLED_SOFT_SKIP_TICKS) {
                return "stalled";
            }
            return "running";
        }

        private ActiveTickResult tick(ServerLevel level, CitizenEntity citizen, LevelRuntime runtime) {
            if (waypoints.isEmpty()) {
                return ActiveTickResult.COMPLETE;
            }
            citizen.getNavigation().stop();
            resetActionStateIfNeeded();
            advanceReachedWaypoints(citizen);
            if (waypointIndex >= waypoints.size()) {
                return ActiveTickResult.COMPLETE;
            }
            resetActionStateIfNeeded();
            PathWaypoint waypoint = waypoints.get(waypointIndex);
            double distance = citizen.position().distanceTo(waypoint.position());
            if (shouldAdvanceWaypoint(citizen, citizen.position(), waypointIndex, waypoint)) {
                advanceWaypoint();
                advanceReachedWaypoints(citizen);
                if (waypointIndex >= waypoints.size()) {
                    return ActiveTickResult.COMPLETE;
                }
                resetActionStateIfNeeded();
                waypoint = waypoints.get(waypointIndex);
                distance = citizen.position().distanceTo(waypoint.position());
            }

            PathWaypoint commandWaypoint = waypoint;
            Vec3 commandTarget = commandTarget(citizen.position(), waypointIndex, waypoint, commandWaypoint);
            MovementMode commandMode = commandMode(commandTarget, commandWaypoint);
            tryOpenWoodenDoor(level, citizen, waypoint, runtime);
            if (ClimbWaypointPolicy.isLandingAfterDescendingClimb(waypoints, waypointIndex)) {
                if (shouldApplyClimbExitDetach(citizen, waypoint)) {
                    Vec3 detachDirection = ClimbWaypointPolicy.landingDetachDirection(waypoints, waypointIndex,
                            climbExitFallbackDirection(level, waypoints.get(waypointIndex - 1)));
                    citizen.getMoveControl().setWantedPosition(citizen.getX(), citizen.getY(), citizen.getZ(), 0.0D);
                    applyClimbExitDetach(citizen, detachDirection);
                    stalledTicks = 0;
                    lastDistance = distance;
                    return ActiveTickResult.RUNNING;
                }
            }
            PathCrowdCoordinator.record(level, citizen.getUUID(), citizen.position(), commandTarget);
            boolean crowdYieldTimedOut = false;
            if (!isActionMode(waypoint.mode()) && PathCrowdCoordinator.shouldYield(level, citizen, commandTarget)) {
                crowdYieldTicks++;
                if (crowdYieldTicks <= MAX_CROWD_YIELD_TICKS) {
                    Vec3 motion = citizen.getDeltaMovement();
                    citizen.setDeltaMovement(motion.x * 0.2D, motion.y, motion.z * 0.2D);
                    citizen.getMoveControl().setWantedPosition(citizen.getX(), citizen.getY(), citizen.getZ(), 0.0D);
                    stalledTicks = 0;
                    lastDistance = distance;
                    return ActiveTickResult.RUNNING;
                }
                crowdYieldTimedOut = true;
            } else {
                crowdYieldTicks = 0;
            }

            if (lastDistance - distance > 0.04D) {
                stalledTicks = 0;
                lastDistance = distance;
            } else {
                stalledTicks++;
            }
            if (!isActionMode(waypoint.mode()) && !requiresWaypointCentering(waypointIndex, waypoint.mode()) && stalledTicks > STALLED_SOFT_SKIP_TICKS && distance <= STALLED_SOFT_SKIP_DISTANCE) {
                advanceWaypoint();
                return ActiveTickResult.RUNNING;
            }
            if (stalledTicks > STALLED_REPATH_TICKS) {
                return ActiveTickResult.REPATH;
            }

            double speed = speedFor(intent, commandMode);
            if (crowdYieldTimedOut) {
                speed *= 0.55D;
            }
            citizen.getMoveControl().setWantedPosition(commandTarget.x, commandTarget.y, commandTarget.z, speed);
            applyClimbMotion(citizen, commandTarget, commandMode);
            if (shouldTriggerJump(citizen, waypointIndex, waypoint)) {
                citizen.getJumpControl().jump();
                jumpTriggered = true;
            }
            level.getGameTime();
            return ActiveTickResult.RUNNING;
        }

        private void advanceReachedWaypoints(CitizenEntity citizen) {
            while (waypointIndex < waypoints.size()) {
                PathWaypoint waypoint = waypoints.get(waypointIndex);
                if (!shouldAdvanceWaypoint(citizen, citizen.position(), waypointIndex, waypoint)) {
                    return;
                }
                advanceWaypoint();
            }
        }

        private void advanceWaypoint() {
            waypointIndex++;
            stalledTicks = 0;
            crowdYieldTicks = 0;
            lastDistance = Double.MAX_VALUE;
            actionWaypointIndex = -1;
            jumpTriggered = false;
        }

        private void resetActionStateIfNeeded() {
            if (actionWaypointIndex != waypointIndex) {
                actionWaypointIndex = waypointIndex;
                jumpTriggered = false;
            }
        }

        private boolean shouldAdvanceWaypoint(CitizenEntity citizen, Vec3 position, int index, PathWaypoint waypoint) {
            if (waypoint.mode() == MovementMode.CLIMB) {
                return ClimbWaypointPolicy.isReached(position, waypoints, index);
            }
            if (ClimbWaypointPolicy.isLandingAfterClimb(waypoints, index)) {
                return ClimbWaypointPolicy.isLandingReached(position, waypoints, index, citizen.onGround());
            }
            double arrivalDistance = arrivalDistance(index, waypoint.mode());
            if (position.distanceToSqr(waypoint.position()) <= arrivalDistance * arrivalDistance) {
                if (waypoint.mode() == MovementMode.JUMP && jumpRequiresLiftoff(index, waypoint) && (!jumpTriggered || !citizen.onGround())) {
                    return false;
                }
                return true;
            }
            if (isActionMode(waypoint.mode())) {
                return false;
            }
            if (requiresWaypointCentering(index, waypoint.mode())) {
                return false;
            }
            return hasPassedWaypoint(position, index, waypoint);
        }

        private Vec3 commandTarget(Vec3 position, int index, PathWaypoint waypoint, PathWaypoint commandWaypoint) {
            if (waypoint.mode() == MovementMode.JUMP && index > 0 && !jumpTriggered && !isNearActionStart(position, index)) {
                return waypoints.get(index - 1).position();
            }
            if (waypoint.mode() == MovementMode.CLIMB) {
                return ClimbWaypointPolicy.commandTarget(position, waypoints, index);
            }
            if (!isActionMode(waypoint.mode()) && index > 0) {
                return segmentFollowTarget(position, index, waypoint);
            }
            return commandWaypoint.position();
        }

        private MovementMode commandMode(Vec3 commandTarget, PathWaypoint commandWaypoint) {
            if (isActionMode(commandWaypoint.mode()) && commandTarget != commandWaypoint.position()) {
                return MovementMode.WALK;
            }
            return commandWaypoint.mode();
        }

        private Vec3 segmentFollowTarget(Vec3 position, int index, PathWaypoint waypoint) {
            Vec3 from = waypoints.get(index - 1).position();
            Vec3 to = waypoint.position();
            double segmentX = to.x - from.x;
            double segmentY = to.y - from.y;
            double segmentZ = to.z - from.z;
            double segmentLengthSqr = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ;
            if (segmentLengthSqr < 0.0001D) {
                return to;
            }
            double progress = ((position.x - from.x) * segmentX + (position.y - from.y) * segmentY + (position.z - from.z) * segmentZ) / segmentLengthSqr;
            double segmentLength = Math.sqrt(segmentLengthSqr);
            double lookahead = requiresWaypointCentering(index, waypoint.mode()) ? CORNER_LOOKAHEAD_BLOCKS : SEGMENT_LOOKAHEAD_BLOCKS;
            double targetProgress = clamp(progress, 0.0D, 1.0D) + lookahead / segmentLength;
            targetProgress = clamp(targetProgress, 0.0D, 1.0D);
            return new Vec3(
                    from.x + segmentX * targetProgress,
                    from.y + segmentY * targetProgress,
                    from.z + segmentZ * targetProgress
            );
        }

        private boolean shouldTriggerJump(CitizenEntity citizen, int index, PathWaypoint waypoint) {
            return waypoint.mode() == MovementMode.JUMP
                    && index > 0
                    && !jumpTriggered
                    && citizen.onGround()
                    && isNearActionStart(citizen.position(), index)
                    && waypoint.position().y > waypoints.get(index - 1).position().y + 0.25D;
        }

        /**
         * Returns whether a JUMP waypoint actually needs a manual lift-off. A rise within the auto
         * step band ({@code <= 0.25}) never satisfies {@link #shouldTriggerJump}, so the body climbs
         * it by stepping and {@code jumpTriggered} stays false; gating advancement on that flag would
         * deadlock the citizen under the waypoint. Such tiny JUMP edges therefore advance on arrival
         * like a walk, while genuine jumps still wait for the jump to fire and the body to land.
         */
        private boolean jumpRequiresLiftoff(int index, PathWaypoint waypoint) {
            return index > 0 && waypoint.position().y - waypoints.get(index - 1).position().y > 0.25D;
        }

        private void applyClimbMotion(CitizenEntity citizen, Vec3 commandTarget, MovementMode commandMode) {
            if (commandMode != MovementMode.CLIMB) {
                return;
            }
            double dx = commandTarget.x - citizen.getX();
            double dz = commandTarget.z - citizen.getZ();
            double horizontalSqr = dx * dx + dz * dz;
            if (horizontalSqr > CLIMB_VERTICAL_ASSIST_DISTANCE * CLIMB_VERTICAL_ASSIST_DISTANCE) {
                return;
            }
            double dy = commandTarget.y - citizen.getY();
            if (Math.abs(dy) <= CLIMB_VERTICAL_ARRIVAL_DISTANCE) {
                return;
            }
            Vec3 motion = citizen.getDeltaMovement();
            double verticalSpeed = Math.max(-CLIMB_VERTICAL_SPEED, Math.min(CLIMB_VERTICAL_SPEED, dy * CLIMB_VERTICAL_SPEED_FACTOR));
            citizen.setDeltaMovement(motion.x, verticalSpeed, motion.z);
            citizen.fallDistance = 0.0F;
        }

        /** applyClimbExitDetach: 离梯下落阶段只轻微离墙并压低竖直速度，避免触发爬梯上行。 */
        private void applyClimbExitDetach(CitizenEntity citizen, Vec3 direction) {
            if (direction.lengthSqr() < 1.0E-4D) {
                return;
            }
            Vec3 motion = citizen.getDeltaMovement();
            citizen.setDeltaMovement(
                    direction.x * CLIMB_EXIT_DETACH_HORIZONTAL_SPEED,
                    Math.min(motion.y, CLIMB_EXIT_DROP_SPEED),
                    direction.z * CLIMB_EXIT_DETACH_HORIZONTAL_SPEED);
            citizen.fallDistance = 0.0F;
        }

        /** shouldApplyClimbExitDetach: 只在未落地的陆地下梯阶段施加脱离力。 */
        private boolean shouldApplyClimbExitDetach(CitizenEntity citizen, PathWaypoint waypoint) {
            return waypoint.mode() != MovementMode.SWIM && !citizen.onGround();
        }

        /** climbExitFallbackDirection: 路径没有水平出口时，真实梯子才按方块朝向兜底离墙。 */
        private Vec3 climbExitFallbackDirection(ServerLevel level, PathWaypoint climbWaypoint) {
            BlockState state = level.getBlockState(climbWaypoint.blockPos());
            if (state.getBlock() instanceof LadderBlock && state.hasProperty(LadderBlock.FACING)) {
                net.minecraft.core.Direction facing = state.getValue(LadderBlock.FACING);
                return new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
            }
            return Vec3.ZERO;
        }

        private boolean isNearActionStart(Vec3 position, int index) {
            if (index <= 0) {
                return true;
            }
            Vec3 start = waypoints.get(index - 1).position();
            double dx = position.x - start.x;
            double dz = position.z - start.z;
            return dx * dx + dz * dz <= ACTION_START_DISTANCE * ACTION_START_DISTANCE
                    && Math.abs(position.y - start.y) <= 0.75D;
        }

        private boolean isActionMode(MovementMode mode) {
            return mode == MovementMode.JUMP || mode == MovementMode.SWIM || mode == MovementMode.CLIMB || mode == MovementMode.FALL;
        }

        private boolean hasPassedWaypoint(Vec3 position, int index, PathWaypoint waypoint) {
            if (index <= 0) {
                return false;
            }
            Vec3 from = waypoints.get(index - 1).position();
            Vec3 to = waypoint.position();
            double segmentX = to.x - from.x;
            double segmentZ = to.z - from.z;
            double segmentLengthSqr = segmentX * segmentX + segmentZ * segmentZ;
            double verticalTolerance = isActionMode(waypoint.mode())
                    ? ACTION_PASSED_VERTICAL_TOLERANCE
                    : WALK_PASSED_VERTICAL_TOLERANCE;
            if (segmentLengthSqr < 0.0001D || Math.abs(position.y - to.y) > verticalTolerance) {
                return false;
            }
            double progressX = position.x - from.x;
            double progressZ = position.z - from.z;
            double projection = (progressX * segmentX + progressZ * segmentZ) / segmentLengthSqr;
            if (projection < PASSED_WAYPOINT_DOT_EPSILON) {
                return false;
            }
            double closestX = from.x + segmentX * projection;
            double closestZ = from.z + segmentZ * projection;
            double lateralX = position.x - closestX;
            double lateralZ = position.z - closestZ;
            double lateralSqr = lateralX * lateralX + lateralZ * lateralZ;
            if (lateralSqr <= PASSED_WAYPOINT_LATERAL_TOLERANCE * PASSED_WAYPOINT_LATERAL_TOLERANCE) {
                return true;
            }
            return false;
        }

        private boolean requiresWaypointCentering(int index, MovementMode mode) {
            return !isActionMode(mode) && isTurnWaypoint(index);
        }

        /**
         * Returns whether the path bends at this waypoint by more than the corner threshold.
         *
         * <p>The test uses the actual waypoint positions and the angle between the incoming and
         * outgoing segments, so it still detects same-quadrant bends that survive smoothing (e.g.
         * a {@code (+3,+1)} segment into a {@code (+1,+3)} segment), which the previous
         * sign-comparison missed. The first and last waypoints are never treated as turns: the last
         * one keeps the looser arrival tolerance so the citizen is not forced to centre exactly on
         * the goal cell.
         */
        private boolean isTurnWaypoint(int index) {
            if (index <= 0 || index >= waypoints.size() - 1) {
                return false;
            }
            Vec3 previous = waypoints.get(index - 1).position();
            Vec3 current = waypoints.get(index).position();
            Vec3 next = waypoints.get(index + 1).position();
            double inX = current.x - previous.x;
            double inZ = current.z - previous.z;
            double outX = next.x - current.x;
            double outZ = next.z - current.z;
            double inLength = Math.sqrt(inX * inX + inZ * inZ);
            double outLength = Math.sqrt(outX * outX + outZ * outZ);
            if (inLength < 1.0E-4D || outLength < 1.0E-4D) {
                return false;
            }
            double directionDot = (inX * outX + inZ * outZ) / (inLength * outLength);
            return directionDot < TURN_DOT_THRESHOLD;
        }

        private double arrivalDistance(int index, MovementMode mode) {
            if (requiresWaypointCentering(index, mode)) {
                return CORNER_ARRIVAL_DISTANCE;
            }
            return switch (mode) {
                case CLIMB, SWIM -> 1.15D;
                case JUMP, FALL -> 1.05D;
                default -> 0.72D;
            };
        }

        private double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class PathThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "simukraft-path-worker-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }

    private static final class SaveKey {
        private static String serverKey(MinecraftServer server) {
            return server == null ? "unknown" : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        }
    }
}
