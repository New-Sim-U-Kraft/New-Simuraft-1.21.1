package common.cn.kafei.simukraft.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/**
 * Samples the live world on the server thread into an immutable {@link PathSnapshot}.
 *
 * <p>This is the only component that reads the live world for pathfinding; once a snapshot is
 * frozen the asynchronous search never touches block state again. Each {@link PathCell} records the
 * exact stand height and traversal class of a column position. Redundant block-state and collision
 * reads are deduplicated through a per-build {@link SampleCache}, which keeps the produced cell map
 * identical while removing the overlapping world reads between adjacent vertical samples.
 */
@SuppressWarnings("null")
final class PathSnapshotBuilder {
    private static final int HORIZONTAL_PADDING = 12;
    private static final int VERTICAL_PADDING = 8;
    private static final double NPC_HALF_WIDTH = 0.31D;
    private static final double NPC_HEIGHT = 1.8D;
    private static final double MAX_LOW_STAND_OFFSET = 0.75D;
    // A legitimate floor support's top sits at or below the cell's own grid Y (collision height
    // <= 1.0). Anything higher is a fence/wall/closed-gate protruding into the cell, never a surface.
    private static final double FLOOR_TOP_EPSILON = 1.0E-4D;

    private PathSnapshotBuilder() {
    }

    /**
     * Builds an immutable snapshot covering the volume between {@code start} and {@code target}.
     *
     * @param level the server level to sample
     * @param start the citizen's start block
     * @param target the destination block
     * @param radius the local path radius that bounds the sampled box
     * @return an immutable snapshot of every walkable cell in the bounded volume
     */
    static PathSnapshot build(ServerLevel level, BlockPos start, BlockPos target, int radius) {
        SnapshotBounds bounds = bounds(level, start, target, radius);
        SampleCache cache = new SampleCache(level);
        Map<Long, PathCell> cells = new HashMap<>();
        LongOpenHashSet bodyPassages = new LongOpenHashSet();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        boolean complete = true;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                mutable.set(x, start.getY(), z);
                if (!hasLoadedChunk(level, mutable)) {
                    complete = false;
                    continue;
                }
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    mutable.set(x, y, z);
                    if (hasBodyPassage(cache, mutable)) {
                        bodyPassages.add(mutable.asLong());
                    }
                    PathCell cell = classify(cache, mutable);
                    if (cell != null) {
                        cells.put(cell.key(), cell);
                    }
                }
            }
        }
        return new PathSnapshot(level.dimension().location(), start.immutable(), target.immutable(),
                Map.copyOf(cells), LongSets.unmodifiable(bodyPassages), bounds.minY(), bounds.maxY(), level.getGameTime(), complete);
    }

    /**
     * Computes the sampled box for a request without reading any block state.
     *
     * <p>Exposed so callers can test box containment for snapshot reuse using exactly the same
     * bounds {@link #build} would produce.
     */
    static SnapshotBounds bounds(ServerLevel level, BlockPos start, BlockPos target, int radius) {
        int safeRadius = Math.max(16, radius);
        int minX = Math.max(Math.min(start.getX(), target.getX()) - HORIZONTAL_PADDING, start.getX() - safeRadius);
        int maxX = Math.min(Math.max(start.getX(), target.getX()) + HORIZONTAL_PADDING, start.getX() + safeRadius);
        int minZ = Math.max(Math.min(start.getZ(), target.getZ()) - HORIZONTAL_PADDING, start.getZ() - safeRadius);
        int maxZ = Math.min(Math.max(start.getZ(), target.getZ()) + HORIZONTAL_PADDING, start.getZ() + safeRadius);
        int minY = Math.max(level.getMinBuildHeight(), Math.min(start.getY(), target.getY()) - VERTICAL_PADDING);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, Math.max(start.getY(), target.getY()) + VERTICAL_PADDING);
        return new SnapshotBounds(minX, maxX, minZ, maxZ, minY, maxY);
    }

    /**
     * Classifies a single column position into a walkable {@link PathCell}, or {@code null} when the
     * citizen cannot occupy it.
     */
    private static PathCell classify(SampleCache cache, BlockPos pos) {
        BlockState foot = cache.state(pos);
        BlockState head = cache.state(pos.above());
        BlockState below = cache.state(pos.below());
        if (isDangerous(foot) || isDangerous(head) || isDangerous(below)) {
            return null;
        }

        boolean footWater = foot.getFluidState().is(FluidTags.WATER);
        boolean headWater = head.getFluidState().is(FluidTags.WATER);
        boolean water = footWater || headWater;
        boolean climbable = isClimbable(foot) || isClimbable(head);
        if (water) {
            if (!isFootPassable(cache, pos, foot) || !isHeadPassable(cache, pos.above(), head)) {
                return null;
            }
            return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), pos.getY(), true, climbable, false, 1.8D);
        }
        if (climbable && isFootPassable(cache, pos, foot) && isHeadPassable(cache, pos.above(), head)) {
            return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), pos.getY(), false, true, false, 2.0D);
        }
        if (isClosedWoodenLowerDoor(foot) && isMatchingWoodenDoorHead(head)) {
            double standY = supportTop(cache, pos.below(), below);
            if (!Double.isNaN(standY) && hasNpcClearance(cache, pos, standY, pos, pos.above())) {
                return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), standY, false, false, true, 3.2D);
            }
        }
        if (!isFootPassable(cache, pos, foot) || !isHeadPassable(cache, pos.above(), head)) {
            double standY = lowStandY(cache, pos, foot);
            if (!Double.isNaN(standY) && isHeadPassable(cache, pos.above(), head, standY - pos.getY()) && hasNpcClearance(cache, pos, standY, null, null)) {
                return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), standY, false, false, false, 1.05D);
            }
            return null;
        }
        double standY = supportTop(cache, pos.below(), below);
        if (Double.isNaN(standY)) {
            return null;
        }
        // Reject standing on the cap of a fence, wall or closed gate: their 1.5-high collision makes
        // the support top protrude into this cell, but vanilla ground navigation treats them as
        // non-walkable and the slim post cannot actually carry the body.
        if (standY - pos.getY() > FLOOR_TOP_EPSILON) {
            return null;
        }
        if (!hasNpcClearance(cache, pos, standY, null, null)) {
            return null;
        }
        return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), standY, false, false, false, 1.0D);
    }

    private static boolean hasLoadedChunk(ServerLevel level, BlockPos pos) {
        return level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * Returns whether the citizen's body can occupy the column at {@code pos} given its block state.
     */
    private static boolean isFootPassable(SampleCache cache, BlockPos pos, BlockState state) {
        return isBodyPassable(cache, pos, state, 0.0D, 1.0D);
    }

    private static boolean isHeadPassable(SampleCache cache, BlockPos pos, BlockState state) {
        return isBodyPassable(cache, pos, state, 0.0D, NPC_HEIGHT - 1.0D);
    }

    private static boolean isHeadPassable(SampleCache cache, BlockPos pos, BlockState state, double standOffset) {
        double localMinY = Math.max(0.0D, standOffset - 1.0D);
        double localMaxY = Math.max(localMinY, standOffset + NPC_HEIGHT - 1.0D);
        return isBodyPassable(cache, pos, state, localMinY, localMaxY);
    }

    private static boolean isBodyPassable(SampleCache cache, BlockPos pos, BlockState state, double localMinY, double localMaxY) {
        Block block = state.getBlock();
        if (isDoorLikeBlock(block)) {
            return isNpcPassableDoorLikeBlock(state, cache.shape(pos, state), localMinY, localMaxY);
        }
        return state.isAir()
                || state.getFluidState().is(FluidTags.WATER)
                || isClimbable(state)
                || clearsNpcBodySlice(cache, pos, state, localMinY, localMaxY);
    }

    /**
     * Returns whether the block's collision shape leaves the citizen's centred footprint column
     * free, i.e. a body standing at the block's centre would not intersect it.
     *
     * <p>This generalises the previous {@code shape.isEmpty()} test: a thin, face-hugging partial
     * shape the slim body actually clears — an open trapdoor pinned to one wall, a wall lever or
     * button — is now passable, which is what lets a citizen climb a ladder out through an open
     * trapdoor or stand under one. A shape that fills the footprint (a closed trapdoor on the floor,
     * a slab, a fence post, a closed gate) still reports blocking, so it is routed onto via {@link
     * #lowStandY} or jumped/avoided exactly as before. The test also checks the occupied vertical
     * slice, so upper trapdoors above the head are not treated like floor-level blockers.
     */
    private static boolean clearsNpcBodySlice(SampleCache cache, BlockPos pos, BlockState state, double localMinY, double localMaxY) {
        return clearsNpcBodySlice(cache.shape(pos, state), localMinY, localMaxY);
    }

    /** clearsNpcBodySlice: 检查中心脚印在指定垂直切片里是否避开碰撞体。 */
    static boolean clearsNpcBodySlice(VoxelShape shape, double localMinY, double localMaxY) {
        if (shape.isEmpty()) {
            return true;
        }
        double minX = 0.5D - NPC_HALF_WIDTH;
        double maxX = 0.5D + NPC_HALF_WIDTH;
        double minZ = 0.5D - NPC_HALF_WIDTH;
        double maxZ = 0.5D + NPC_HALF_WIDTH;
        for (AABB box : shape.toAabbs()) {
            if (box.maxX > minX && box.minX < maxX
                    && box.maxY > localMinY && box.minY < localMaxY
                    && box.maxZ > minZ && box.minZ < maxZ) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBodyPassage(SampleCache cache, BlockPos pos) {
        BlockState foot = cache.state(pos);
        BlockState head = cache.state(pos.above());
        if (isDangerous(foot) || isDangerous(head)) {
            return false;
        }
        return isFootPassable(cache, pos, foot)
                && isHeadPassable(cache, pos.above(), head)
                && hasNpcClearance(cache, pos, pos.getY(), null, null);
    }

    /**
     * Returns whether the citizen's bounding box, standing at {@code standY} above {@code feet}, is
     * free of solid collision, ignoring up to two positions (used to exclude an opening door).
     */
    private static boolean hasNpcClearance(SampleCache cache, BlockPos feet, double standY, BlockPos ignoredA, BlockPos ignoredB) {
        double centerX = feet.getX() + 0.5D;
        double centerZ = feet.getZ() + 0.5D;
        AABB npcBox = new AABB(
                centerX - NPC_HALF_WIDTH,
                standY,
                centerZ - NPC_HALF_WIDTH,
                centerX + NPC_HALF_WIDTH,
                standY + NPC_HEIGHT,
                centerZ + NPC_HALF_WIDTH);
        int minX = (int) Math.floor(npcBox.minX);
        int minY = (int) Math.floor(npcBox.minY) - 1;
        int minZ = (int) Math.floor(npcBox.minZ);
        int maxX = (int) Math.floor(npcBox.maxX);
        int maxY = (int) Math.floor(npcBox.maxY);
        int maxZ = (int) Math.floor(npcBox.maxZ);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (mutable.equals(ignoredA) || mutable.equals(ignoredB)) {
                        continue;
                    }
                    BlockState state = cache.state(mutable);
                    VoxelShape shape = cache.shape(mutable, state);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    for (AABB box : shape.toAabbs()) {
                        if (box.move(x, y, z).intersects(npcBox)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Returns the world-space top surface of a supporting block, or {@link Double#NaN} when it has
     * no collision to stand on.
     */
    private static double supportTop(SampleCache cache, BlockPos supportPos, BlockState supportState) {
        VoxelShape shape = cache.shape(supportPos, supportState);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        double top = Double.NEGATIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            top = Math.max(top, supportPos.getY() + box.maxY);
        }
        if (!Double.isFinite(top)) {
            return Double.NaN;
        }
        return top;
    }

    /** isNpcPassableDoorLikeBlock: 门、栅栏门、活板门仅在当前陆地寻路状态可通过时放行。 */
    static boolean isNpcPassableDoorLikeBlock(BlockState state) {
        Block block = state.getBlock();
        return isDoorLikeBlock(block) && state.isPathfindable(PathComputationType.LAND);
    }

    static boolean isNpcPassableDoorLikeBlock(BlockState state, VoxelShape shape, double localMinY, double localMaxY) {
        Block block = state.getBlock();
        if (!isDoorLikeBlock(block)) {
            return false;
        }
        return state.isPathfindable(PathComputationType.LAND)
                || clearsNpcBodySlice(shape, localMinY, localMaxY);
    }

    private static boolean isDoorLikeBlock(Block block) {
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock;
    }

    private static boolean isClosedWoodenLowerDoor(BlockState state) {
        return DoorBlock.isWoodenDoor(state)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.OPEN)
                && !state.getValue(DoorBlock.OPEN)
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static boolean isMatchingWoodenDoorHead(BlockState state) {
        return DoorBlock.isWoodenDoor(state)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.is(Blocks.SCAFFOLDING);
    }

    // lowStandY：识别半砖、地毯等低矮碰撞面，避免把半格台阶误判为上一层跳跃。
    private static double lowStandY(SampleCache cache, BlockPos pos, BlockState state) {
        double standY = supportTop(cache, pos, state);
        double offset = standY - pos.getY();
        return !Double.isNaN(standY) && offset > 0.0D && offset <= MAX_LOW_STAND_OFFSET ? standY : Double.NaN;
    }

    private static boolean isDangerous(BlockState state) {
        Block block = state.getBlock();
        return state.getFluidState().is(FluidTags.LAVA)
                || block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE;
    }

    /**
     * Inclusive integer bounds of a sampled snapshot box.
     */
    record SnapshotBounds(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        /**
         * Returns whether this box fully contains {@code other} on all three axes.
         */
        boolean contains(SnapshotBounds other) {
            return minX <= other.minX && maxX >= other.maxX
                    && minZ <= other.minZ && maxZ >= other.maxZ
                    && minY <= other.minY && maxY >= other.maxY;
        }
    }

    /**
     * Per-build memoization of block states and collision shapes.
     *
     * <p>A single build samples each position's state and shape at most once even though adjacent
     * vertical samples and overlapping clearance scans request the same positions repeatedly. The
     * cache lives only for the duration of one synchronous {@link #build} call, so the world cannot
     * change underneath it and the produced cell map is byte-for-byte identical to an uncached
     * build.
     */
    private static final class SampleCache {
        private final ServerLevel level;
        private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<VoxelShape> shapes = new Long2ObjectOpenHashMap<>();

        private SampleCache(ServerLevel level) {
            this.level = level;
        }

        private BlockState state(BlockPos pos) {
            long key = pos.asLong();
            BlockState cached = states.get(key);
            if (cached != null) {
                return cached;
            }
            BlockState state = level.getBlockState(pos);
            states.put(key, state);
            return state;
        }

        private VoxelShape shape(BlockPos pos, BlockState state) {
            long key = pos.asLong();
            VoxelShape cached = shapes.get(key);
            if (cached != null) {
                return cached;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            shapes.put(key, shape);
            return shape;
        }
    }
}
