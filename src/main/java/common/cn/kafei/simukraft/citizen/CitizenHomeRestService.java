package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CitizenHomeRestService {
    private static final String HOME_REST_MARKER = "home_rest";
    private static final int HOME_TARGET_SEARCH_RADIUS = 4;
    private static final int HOME_ANCHOR_SEARCH_RADIUS = 8;
    // 记录本晚已经处理过的居民，避免每 40 tick 反复传送造成抖动。
    private static final ConcurrentMap<String, Set<UUID>> RESTED_CITIZENS_BY_LEVEL = new ConcurrentHashMap<>();

    private CitizenHomeRestService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        String levelKey = SaveScopedCacheKey.levelKey(level);
        if (!isRestTime(level)) {
            RESTED_CITIZENS_BY_LEVEL.remove(levelKey);
            restoreHomeRestingCitizens(level);
            return;
        }
        if (level.getGameTime() % 40L != 0L) {
            return;
        }
        Set<UUID> restedCitizens = RESTED_CITIZENS_BY_LEVEL.computeIfAbsent(levelKey, ignored -> ConcurrentHashMap.newKeySet());
        // 同一张床可能分给多个居民，本 tick 只计算一次安全落点。
        ConcurrentMap<UUID, Vec3> homeTargets = new ConcurrentHashMap<>();
        CityPoiManager poiManager = CityPoiManager.get(level);
        CitizenManager manager = CitizenManager.get(level);
        for (CitizenData citizen : manager.allCitizens()) {
            if (citizen.dead()) {
                continue;
            }
            if (citizen.homeId() == null) {
                continue;
            }
            CityPoiData home = poiManager.getPoi(citizen.homeId());
            if (home == null || !home.active() || home.type() != CityPoiType.RESIDENTIAL) {
                continue;
            }
            Vec3 homeTarget = homeTargets.computeIfAbsent(home.poiId(), ignored -> resolveHomeTarget(level, home.pos()));
            if (restedCitizens.contains(citizen.uuid())) {
                CitizenTeleportService.reconcileLoadedCitizenEntities(level, citizen.uuid(), homeTarget);
                continue;
            }
            if (moveOrTeleportHome(level, citizen, homeTarget)) {
                citizen.setWorkStatus(CitizenWorkStatus.RESTING);
                citizen.setStatusLabel("夜间回家休息");
                citizen.setWorkNeedDetail(HOME_REST_MARKER);
                manager.saveCitizenNow(citizen.uuid());
                restedCitizens.add(citizen.uuid());
            }
        }
    }

    private static boolean moveOrTeleportHome(ServerLevel level, CitizenData citizen, Vec3 homeTarget) {
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity != null && CitizenNavigationService.requestMove(level, citizen.uuid(), homeTarget, MovementIntent.RETURN_HOME)) {
            return true;
        }
        return CitizenTeleportService.teleportOrSpawnCitizen(level, citizen, homeTarget);
    }

    private static void restoreHomeRestingCitizens(ServerLevel level) {
        CitizenManager manager = CitizenManager.get(level);
        for (CitizenData citizen : manager.allCitizens()) {
            if (citizen.dead()) {
                continue;
            }
            if (!HOME_REST_MARKER.equals(citizen.workNeedDetail())) {
                continue;
            }
            CitizenWorkStatus nextStatus = citizen.workplaceId() != null && citizen.jobType() != CityJobType.UNEMPLOYED
                    ? CitizenWorkStatus.WORKING
                    : CitizenWorkStatus.IDLE;
            citizen.setWorkStatus(nextStatus);
            citizen.setStatusLabel("");
            citizen.setWorkNeedDetail("");
            manager.saveCitizenNow(citizen.uuid());
            CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
            if (entity != null) {
                manager.syncEntity(entity);
            }
            if (nextStatus == CitizenWorkStatus.WORKING) {
                CitizenWorkplaceMoveService.returnToWorkplace(level, citizen);
            }
        }
    }

    private static boolean isRestTime(ServerLevel level) {
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

    // 清理指定存档的夜间回家标记，防止同一维度名在不同存档间复用。
    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server);
        RESTED_CITIZENS_BY_LEVEL.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    // resolveHomeTarget：解析住宅床边的安全脚底坐标，供回家和新入住生成共用。
    public static Vec3 resolveHomeTarget(ServerLevel level, BlockPos homePos) {
        BlockPos anchor = resolveHomeAnchor(level, homePos);
        List<BlockPos> bedsideCandidates = collectBedsideCandidates(level, anchor);
        if (!bedsideCandidates.isEmpty()) {
            return bedsideCandidates.stream()
                    .min(Comparator.comparingInt(candidate -> bedsideTargetScore(level, anchor, candidate)))
                    .map(Vec3::atBottomCenterOf)
                    .orElseGet(() -> Vec3.atBottomCenterOf(anchor));
        }
        List<BlockPos> candidates = collectHomeTargetCandidates(level, anchor);
        // 优先选择和床在同一可行走空间的点，避免 NPC 被传到墙外。
        List<BlockPos> reachableCandidates = collectReachableCandidates(level, anchor, candidates);
        List<BlockPos> preferredCandidates = !reachableCandidates.isEmpty()
                ? reachableCandidates
                : candidates.stream().filter(candidate -> hasClearPath(level, anchor, candidate)).toList();
        if (preferredCandidates.isEmpty()) {
            preferredCandidates = candidates;
        }
        return preferredCandidates.stream()
                .min(Comparator.comparingInt(candidate -> homeTargetScore(anchor, candidate)))
                .map(Vec3::atBottomCenterOf)
                .orElseGet(() -> Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, homePos)));
    }

    private static BlockPos resolveHomeAnchor(ServerLevel level, BlockPos homePos) {
        if (isResidentialBedHead(level.getBlockState(homePos))) {
            return homePos;
        }
        // 旧存档里 POI 可能记录在床侧，先回找床头作为安全点搜索中心。
        ArrayList<BlockPos> beds = new ArrayList<>();
        for (int yOffset = -2; yOffset <= 2; yOffset++) {
            for (int xOffset = -HOME_ANCHOR_SEARCH_RADIUS; xOffset <= HOME_ANCHOR_SEARCH_RADIUS; xOffset++) {
                for (int zOffset = -HOME_ANCHOR_SEARCH_RADIUS; zOffset <= HOME_ANCHOR_SEARCH_RADIUS; zOffset++) {
                    BlockPos candidate = homePos.offset(xOffset, yOffset, zOffset);
                    if (isResidentialBedHead(level.getBlockState(candidate))) {
                        beds.add(candidate.immutable());
                    }
                }
            }
        }
        return beds.stream()
                .min(Comparator.comparingInt(candidate -> homeTargetScore(homePos, candidate)))
                .orElse(homePos);
    }

    private static List<BlockPos> collectBedsideCandidates(ServerLevel level, BlockPos bedHeadPos) {
        if (!isResidentialBedHead(level.getBlockState(bedHeadPos))) {
            return List.of();
        }
        ArrayList<BlockPos> candidates = new ArrayList<>();
        BlockState bedHeadState = level.getBlockState(bedHeadPos);
        BlockPos bedFootPos = resolveBedFootPos(bedHeadPos, bedHeadState);
        addBedsideCandidates(level, bedHeadPos, bedFootPos, candidates);
        if (bedFootPos != null) {
            addBedsideCandidates(level, bedFootPos, bedHeadPos, candidates);
        }
        return candidates.stream().distinct().toList();
    }

    private static void addBedsideCandidates(ServerLevel level, BlockPos bedPos, BlockPos connectedBedPos, List<BlockPos> candidates) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = bedPos.relative(direction);
            if (!candidate.equals(connectedBedPos) && canStandAt(level, candidate)) {
                candidates.add(candidate.immutable());
            }
        }
    }

    private static BlockPos resolveBedFootPos(BlockPos bedHeadPos, BlockState bedHeadState) {
        if (!bedHeadState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return null;
        }
        Direction facing = bedHeadState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return bedHeadPos.relative(facing.getOpposite());
    }

    private static int bedsideTargetScore(ServerLevel level, BlockPos bedHeadPos, BlockPos candidate) {
        int baseScore = homeTargetScore(bedHeadPos, candidate);
        BlockPos bedFootPos = resolveBedFootPos(bedHeadPos, level.getBlockState(bedHeadPos));
        if (bedFootPos != null && candidate.distManhattan(bedFootPos) == 1) {
            baseScore -= 32;
        }
        return baseScore;
    }

    private static List<BlockPos> collectHomeTargetCandidates(ServerLevel level, BlockPos anchor) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int yOffset = -1; yOffset <= 2; yOffset++) {
            for (int xOffset = -HOME_TARGET_SEARCH_RADIUS; xOffset <= HOME_TARGET_SEARCH_RADIUS; xOffset++) {
                for (int zOffset = -HOME_TARGET_SEARCH_RADIUS; zOffset <= HOME_TARGET_SEARCH_RADIUS; zOffset++) {
                    BlockPos candidate = anchor.offset(xOffset, yOffset, zOffset);
                    if (canStandAt(level, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static List<BlockPos> collectReachableCandidates(ServerLevel level, BlockPos anchor, List<BlockPos> candidates) {
        Set<BlockPos> safePositions = ConcurrentHashMap.newKeySet();
        safePositions.addAll(candidates);
        Set<BlockPos> visited = ConcurrentHashMap.newKeySet();
        Queue<BlockPos> queue = new ArrayDeque<>();
        // 从床旁无遮挡的安全点开始泛洪，只保留同房间连通区域。
        for (BlockPos candidate : candidates) {
            if (isHomeSeed(level, anchor, candidate) && visited.add(candidate)) {
                queue.add(candidate);
            }
        }
        ArrayList<BlockPos> reachable = new ArrayList<>();
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            reachable.add(current);
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (safePositions.contains(next) && visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return List.copyOf(reachable);
    }

    private static boolean isHomeSeed(ServerLevel level, BlockPos anchor, BlockPos candidate) {
        // 种子点必须离床很近且无遮挡，防止从墙外台阶启动连通搜索。
        int dx = Math.abs(candidate.getX() - anchor.getX());
        int dy = Math.abs(candidate.getY() - anchor.getY());
        int dz = Math.abs(candidate.getZ() - anchor.getZ());
        return dx + dy + dz <= 2 && dy <= 1 && hasClearPath(level, anchor, candidate);
    }

    private static int homeTargetScore(BlockPos homePos, BlockPos candidate) {
        int dx = candidate.getX() - homePos.getX();
        int dy = candidate.getY() - homePos.getY();
        int dz = candidate.getZ() - homePos.getZ();
        int horizontalDistance = dx * dx + dz * dz;
        int verticalPenalty = Math.abs(dy) * 6;
        int sameLevelBonus = dy == 0 ? -2 : 0;
        return horizontalDistance * 16 + verticalPenalty + sameLevelBonus;
    }

    private static boolean hasClearPath(ServerLevel level, BlockPos homePos, BlockPos candidate) {
        if (homePos.equals(candidate)) {
            return true;
        }
        Vec3 start = Vec3.atBottomCenterOf(homePos).add(0.0D, 0.9D, 0.0D);
        Vec3 end = Vec3.atBottomCenterOf(candidate).add(0.0D, 0.9D, 0.0D);
        Vec3 delta = end.subtract(start);
        // 采样高度接近 NPC 腰部，比只看脚下更容易识别墙体遮挡。
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.max(Math.abs(delta.x), Math.abs(delta.y)), Math.abs(delta.z)) * 4.0D));
        for (int index = 1; index < steps; index++) {
            Vec3 sample = start.add(delta.scale((double) index / (double) steps));
            BlockPos samplePos = BlockPos.containing(sample);
            if (samplePos.equals(homePos) || samplePos.equals(candidate)) {
                continue;
            }
            BlockState state = level.getBlockState(samplePos);
            if (!state.getCollisionShape(level, samplePos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || level.isOutsideBuildHeight(pos.above()) || level.isOutsideBuildHeight(pos.below())) {
            return false;
        }
        BlockState floor = level.getBlockState(pos.below());
        BlockState body = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        // 安全落点要求脚下可站、身体和头部无碰撞，并排除水/岩浆。
        return floor.isFaceSturdy(level, pos.below(), Direction.UP)
                && isSafeSpace(level, pos, body)
                && isSafeSpace(level, pos.above(), head)
                && body.getFluidState().isEmpty()
                && head.getFluidState().isEmpty();
    }

    private static boolean isSafeSpace(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isResidentialBedHead(BlockState state) {
        return state.is(Blocks.RED_BED)
                && (!state.hasProperty(BlockStateProperties.BED_PART) || state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }
}
