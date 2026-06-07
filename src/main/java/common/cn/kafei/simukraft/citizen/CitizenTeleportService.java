package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("null")
public final class CitizenTeleportService {
    private static final double MAX_TELEPORT_VERTICAL_DELTA = 1.0D;
    private static final double MAX_LOW_STAND_OFFSET = 0.75D;
    private static final int NEARBY_SAFE_HORIZONTAL_RADIUS = 2;
    private static final int NEARBY_SAFE_MIN_Y_OFFSET = -1;
    private static final int NEARBY_SAFE_MAX_Y_OFFSET = 2;
    private static final double RESCUE_COLLISION_EPSILON = 1.0E-7D;

    private CitizenTeleportService() {
    }

    public static boolean teleportCitizen(ServerLevel level, UUID citizenId, Vec3 target) {
        if (level == null || citizenId == null || target == null) {
            return false;
        }
        CitizenEntity citizenEntity = findCitizenEntity(level, citizenId);
        if (citizenEntity == null) {
            return false;
        }
        Vec3 landing = boundedLandingTarget(level, target);
        if (landing == null || !withinCurrentVerticalRange(citizenEntity.position(), landing)) {
            return false;
        }
        citizenEntity.getNavigation().stop();
        citizenEntity.setDeltaMovement(Vec3.ZERO);
        spawnTeleportParticles(level, citizenEntity.position(), citizenEntity.getRandom());
        citizenEntity.teleportTo(landing.x, landing.y, landing.z);
        spawnTeleportParticles(level, citizenEntity.position(), citizenEntity.getRandom());
        return true;
    }

    public static boolean teleportOrSpawnCitizen(ServerLevel level, CitizenData data, Vec3 target) {
        if (level == null || data == null || target == null) {
            return false;
        }
        if (data.dead()) {
            return false;
        }
        Vec3 landing = boundedLandingTarget(level, target);
        if (landing == null) {
            return false;
        }
        // 先合并已加载的同 UUID 实体；找不到时才按居民数据补生成实体。
        CitizenEntity citizenEntity = reconcileLoadedCitizenEntities(level, data.uuid(), landing);
        if (citizenEntity != null && !withinCurrentVerticalRange(citizenEntity.position(), landing)) {
            return false;
        }
        if (citizenEntity == null) {
            citizenEntity = ModEntities.CITIZEN.get().create(level);
            if (citizenEntity == null) {
                return false;
            }
            citizenEntity.setUUID(data.uuid());
            citizenEntity.setPersistenceRequired();
            citizenEntity.moveTo(landing.x, landing.y, landing.z, level.random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(citizenEntity);
            CitizenManager.get(level).syncEntity(citizenEntity);
        }
        citizenEntity.getNavigation().stop();
        citizenEntity.setDeltaMovement(Vec3.ZERO);
        spawnTeleportParticles(level, citizenEntity.position(), citizenEntity.getRandom());
        citizenEntity.teleportTo(landing.x, landing.y, landing.z);
        spawnTeleportParticles(level, citizenEntity.position(), citizenEntity.getRandom());
        return true;
    }

    // teleportCitizenToNearbySafePosition：NPC 卡进方块时，搜索半径 2 内最近的安全脚底点并救出。
    public static boolean teleportCitizenToNearbySafePosition(ServerLevel level, CitizenEntity citizenEntity) {
        if (level == null || citizenEntity == null || citizenEntity.isRemoved()) {
            return false;
        }
        Vec3 landing = nearestSafeLandingAround(level, citizenEntity);
        if (landing == null) {
            return false;
        }
        citizenEntity.getNavigation().stop();
        citizenEntity.setDeltaMovement(Vec3.ZERO);
        spawnTeleportParticles(level, citizenEntity.position(), citizenEntity.getRandom());
        citizenEntity.teleportTo(landing.x, landing.y, landing.z);
        spawnTeleportParticles(level, citizenEntity.position(), citizenEntity.getRandom());
        return true;
    }

    public static CitizenEntity findCitizenEntity(ServerLevel level, UUID citizenId) {
        return findLoadedCitizenEntity(level, citizenId);
    }

    /**
     * Returns the loaded citizen entity with the given id via the level's O(1) UUID index.
     *
     * <p>This read-only lookup replaces a full entity scan for the hot pathfinding callers.
     * Deduplication of stray duplicate entities is intentionally left to
     * {@link #reconcileLoadedCitizenEntities} (used by spawn and teleport callers) and to the
     * per-tick self-reconcile each entity performs, so this fast path performs no discards.
     *
     * @param level the server level to query
     * @param citizenId the citizen UUID
     * @return the loaded, non-removed citizen entity, or {@code null} if none is loaded
     */
    public static CitizenEntity findLoadedCitizenEntity(ServerLevel level, UUID citizenId) {
        if (level == null || citizenId == null) {
            return null;
        }
        return level.getEntity(citizenId) instanceof CitizenEntity citizenEntity && !citizenEntity.isRemoved()
                ? citizenEntity
                : null;
    }

    public static CitizenEntity reconcileLoadedCitizenEntities(ServerLevel level, UUID citizenId, Vec3 preferredTarget) {
        if (level == null || citizenId == null) {
            return null;
        }
        List<CitizenEntity> matches = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof CitizenEntity citizenEntity && citizenId.equals(citizenEntity.getUUID()) && !citizenEntity.isRemoved()) {
                matches.add(citizenEntity);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        // 保留离目标最近的实体，避免旧区块加载后出现同一居民的重复实体。
        CitizenEntity kept = preferredTarget != null
                ? matches.stream().min(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(preferredTarget))).orElse(matches.getFirst())
                : matches.getFirst();
        for (CitizenEntity duplicate : matches) {
            if (duplicate != kept) {
                duplicate.discard();
            }
        }
        return kept;
    }

    /**
     * boundedLandingTarget：把传送落点限制在目标点位 ±1 范围内的最近安全格。
     */
    private static Vec3 boundedLandingTarget(ServerLevel level, Vec3 target) {
        BlockPos anchor = BlockPos.containing(target.x, target.y, target.z);
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    BlockPos candidate = anchor.offset(xOffset, yOffset, zOffset);
                    Vec3 landing = safeLandingPosition(level, candidate);
                    if (landing == null) {
                        continue;
                    }
                    if (!withinOneBlock(target, landing)) {
                        continue;
                    }
                    double distance = landing.distanceToSqr(target);
                    if (best == null || distance < bestDistance) {
                        best = landing;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    public static boolean isSafeLandingPosition(ServerLevel level, BlockPos pos) {
        return safeLandingPosition(level, pos) != null;
    }

    // safeLandingTarget：对外提供实际脚底落点，供岗位落点选择复用传送安全判定。
    public static Vec3 safeLandingTarget(ServerLevel level, BlockPos pos) {
        return safeLandingPosition(level, pos);
    }

    // nearestSafeLandingAround：从当前位置向外找最近的可站立脚底点，避免救援传送过远。
    private static Vec3 nearestSafeLandingAround(ServerLevel level, CitizenEntity citizenEntity) {
        BlockPos anchor = citizenEntity.blockPosition();
        Vec3 current = citizenEntity.position();
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int yOffset = NEARBY_SAFE_MIN_Y_OFFSET; yOffset <= NEARBY_SAFE_MAX_Y_OFFSET; yOffset++) {
            for (int xOffset = -NEARBY_SAFE_HORIZONTAL_RADIUS; xOffset <= NEARBY_SAFE_HORIZONTAL_RADIUS; xOffset++) {
                for (int zOffset = -NEARBY_SAFE_HORIZONTAL_RADIUS; zOffset <= NEARBY_SAFE_HORIZONTAL_RADIUS; zOffset++) {
                    BlockPos candidate = anchor.offset(xOffset, yOffset, zOffset);
                    if (!level.isLoaded(candidate)) {
                        continue;
                    }
                    Vec3 landing = safeLandingPosition(level, candidate);
                    if (landing == null || !hasEntityClearance(level, citizenEntity, landing)) {
                        continue;
                    }
                    double distance = landing.distanceToSqr(current);
                    if (best == null || distance < bestDistance) {
                        best = landing;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    // hasEntityClearance：用 NPC 当前碰撞盒尺寸复核落点，防止安全脚底点旁边仍有墙体挤压。
    private static boolean hasEntityClearance(ServerLevel level, CitizenEntity citizenEntity, Vec3 landing) {
        Vec3 current = citizenEntity.position();
        AABB landingBox = citizenEntity.getBoundingBox()
                .move(landing.x - current.x, landing.y - current.y, landing.z - current.z)
                .deflate(RESCUE_COLLISION_EPSILON);
        return level.noBlockCollision(citizenEntity, landingBox);
    }

    private static Vec3 safeLandingPosition(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || level.isOutsideBuildHeight(pos) || level.isOutsideBuildHeight(pos.above()) || level.isOutsideBuildHeight(pos.below())) {
            return null;
        }
        BlockState floor = level.getBlockState(pos.below());
        BlockState body = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        double lowStandY = lowStandY(level, pos, body);
        if (!Double.isNaN(lowStandY)) {
            double standY = lowStandY;
            if (Double.isNaN(standY)
                    || !head.getCollisionShape(level, pos.above()).isEmpty()
                    || !body.getFluidState().isEmpty()
                    || !head.getFluidState().isEmpty()
                    || !hasLandingClearance(level, pos, standY)) {
                return null;
            }
            return new Vec3(pos.getX() + 0.5D, standY, pos.getZ() + 0.5D);
        }
        boolean safe = floor.isFaceSturdy(level, pos.below(), Direction.UP)
                && body.getCollisionShape(level, pos).isEmpty()
                && head.getCollisionShape(level, pos.above()).isEmpty()
                && body.getFluidState().isEmpty()
                && head.getFluidState().isEmpty();
        return safe ? Vec3.atBottomCenterOf(pos) : null;
    }

    // lowStandY：识别半砖、地毯等低矮碰撞面作为实际脚底高度。
    private static double lowStandY(ServerLevel level, BlockPos pos, BlockState state) {
        double standY = supportTop(level, pos, state);
        double offset = standY - pos.getY();
        return !Double.isNaN(standY) && offset > 0.0D && offset <= MAX_LOW_STAND_OFFSET ? standY : Double.NaN;
    }

    private static double supportTop(ServerLevel level, BlockPos supportPos, BlockState supportState) {
        VoxelShape shape = supportState.getCollisionShape(level, supportPos);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        double top = Double.NEGATIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            top = Math.max(top, supportPos.getY() + box.maxY);
        }
        return Double.isFinite(top) ? top : Double.NaN;
    }

    private static boolean hasLandingClearance(ServerLevel level, BlockPos pos, double standY) {
        BlockState body = level.getBlockState(pos);
        for (AABB box : body.getCollisionShape(level, pos).toAabbs()) {
            if (box.move(pos).maxY > standY) {
                return false;
            }
        }
        return true;
    }

    /**
     * withinOneBlock：保证实际落点相对目标点位每个轴向误差不超过 1。
     */
    private static boolean withinOneBlock(Vec3 target, Vec3 landing) {
        return Math.abs(landing.x - target.x) <= 1.0D
                && Math.abs(landing.y - target.y) <= 1.0D
                && Math.abs(landing.z - target.z) <= 1.0D;
    }

    /**
     * withinCurrentVerticalRange：传送只能落在 NPC 当前实际高度 ±1 内，避免兜底传送把 NPC 送上屋顶。
     */
    private static boolean withinCurrentVerticalRange(Vec3 current, Vec3 landing) {
        return current != null && landing != null && Math.abs(landing.y - current.y) <= MAX_TELEPORT_VERTICAL_DELTA;
    }

    private static void spawnTeleportParticles(ServerLevel level, Vec3 position, RandomSource random) {
        for (int i = 0; i < 12; i++) {
            double x = position.x + (random.nextDouble() - 0.5D);
            double y = position.y + random.nextDouble() * 2.0D;
            double z = position.z + (random.nextDouble() - 0.5D);
            level.sendParticles(ParticleTypes.PORTAL, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.5D);
        }
        for (int i = 0; i < 4; i++) {
            double x = position.x + (random.nextDouble() - 0.5D) * 0.5D;
            double y = position.y + random.nextDouble();
            double z = position.z + (random.nextDouble() - 0.5D) * 0.5D;
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.0D, 0.1D, 0.0D, 0.02D);
        }
    }
}
