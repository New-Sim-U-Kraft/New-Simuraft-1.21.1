package common.cn.kafei.simukraft.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("null")
final class PathSnapshotBuilder {
    private static final int HORIZONTAL_PADDING = 12;
    private static final int VERTICAL_PADDING = 8;
    private static final double NPC_HALF_WIDTH = 0.31D;
    private static final double NPC_HEIGHT = 1.8D;

    private PathSnapshotBuilder() {
    }

    static PathSnapshot build(ServerLevel level, BlockPos start, BlockPos target, int radius) {
        int safeRadius = Math.max(16, radius);
        int minX = Math.max(Math.min(start.getX(), target.getX()) - HORIZONTAL_PADDING, start.getX() - safeRadius);
        int maxX = Math.min(Math.max(start.getX(), target.getX()) + HORIZONTAL_PADDING, start.getX() + safeRadius);
        int minZ = Math.max(Math.min(start.getZ(), target.getZ()) - HORIZONTAL_PADDING, start.getZ() - safeRadius);
        int maxZ = Math.min(Math.max(start.getZ(), target.getZ()) + HORIZONTAL_PADDING, start.getZ() + safeRadius);
        int minY = Math.max(level.getMinBuildHeight(), Math.min(start.getY(), target.getY()) - VERTICAL_PADDING);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, Math.max(start.getY(), target.getY()) + VERTICAL_PADDING);

        Map<Long, PathCell> cells = new HashMap<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                mutable.set(x, start.getY(), z);
                if (!hasLoadedChunk(level, mutable)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    mutable.set(x, y, z);
                    PathCell cell = classify(level, mutable);
                    if (cell != null) {
                        cells.put(cell.key(), cell);
                    }
                }
            }
        }
        return new PathSnapshot(level.dimension().location(), start.immutable(), target.immutable(), Map.copyOf(cells), minY, maxY, level.getGameTime());
    }

    private static PathCell classify(ServerLevel level, BlockPos pos) {
        BlockState foot = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState below = level.getBlockState(pos.below());
        if (isDangerous(foot) || isDangerous(head) || isDangerous(below)) {
            return null;
        }

        boolean footWater = foot.getFluidState().is(FluidTags.WATER);
        boolean headWater = head.getFluidState().is(FluidTags.WATER);
        boolean water = footWater || headWater;
        boolean climbable = isClimbable(foot) || isClimbable(head) || isClimbable(below);
        if (water) {
            return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), pos.getY(), true, climbable, false, 1.8D);
        }
        if (climbable && isBodyPassable(level, pos, foot) && isBodyPassable(level, pos.above(), head)) {
            return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), pos.getY(), false, true, false, 2.0D);
        }
        if (isClosedWoodenLowerDoor(foot) && isMatchingWoodenDoorHead(head)) {
            double standY = supportTop(level, pos.below(), below);
            if (!Double.isNaN(standY) && hasNpcClearance(level, pos, standY, pos, pos.above())) {
                return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), standY, false, false, true, 3.2D);
            }
        }
        if (!isBodyPassable(level, pos, foot) || !isBodyPassable(level, pos.above(), head)) {
            return null;
        }
        double standY = supportTop(level, pos.below(), below);
        if (Double.isNaN(standY)) {
            return null;
        }
        if (!hasNpcClearance(level, pos, standY, null, null)) {
            return null;
        }
        return new PathCell(pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), standY, false, false, false, 1.0D);
    }

    private static boolean hasLoadedChunk(ServerLevel level, BlockPos pos) {
        return level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private static boolean isBodyPassable(ServerLevel level, BlockPos pos, BlockState state) {
        if (isOpenDoorOrGate(state)) {
            return true;
        }
        return state.isAir() || state.getFluidState().is(FluidTags.WATER) || state.getCollisionShape(level, pos).isEmpty() || isClimbable(state);
    }

    private static boolean hasNpcClearance(ServerLevel level, BlockPos feet, double standY, BlockPos ignoredA, BlockPos ignoredB) {
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
                    BlockState state = level.getBlockState(mutable);
                    VoxelShape shape = state.getCollisionShape(level, mutable);
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

    private static double supportTop(ServerLevel level, BlockPos supportPos, BlockState supportState) {
        VoxelShape shape = supportState.getCollisionShape(level, supportPos);
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

    private static boolean isOpenDoorOrGate(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock) {
            return state.hasProperty(DoorBlock.OPEN) && state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return state.hasProperty(FenceGateBlock.OPEN) && state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }

    private static boolean isClosedWoodenLowerDoor(BlockState state) {
        return state.is(BlockTags.WOODEN_DOORS)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.OPEN)
                && !state.getValue(DoorBlock.OPEN)
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static boolean isMatchingWoodenDoorHead(BlockState state) {
        return state.is(BlockTags.WOODEN_DOORS)
                && state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.is(Blocks.SCAFFOLDING);
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
}
