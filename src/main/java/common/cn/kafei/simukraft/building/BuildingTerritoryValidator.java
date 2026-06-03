package common.cn.kafei.simukraft.building;

import common.cn.kafei.simukraft.city.CityChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BuildingTerritoryValidator {
    private BuildingTerritoryValidator() {
    }

    public static boolean blockBoundsInCity(ServerLevel level, UUID cityId, List<BuildingBlockData> blocks) {
        if (level == null || cityId == null) {
            return false;
        }
        return blockBoundsInChunks(blocks, CityChunkManager.get(level).getCityChunks(cityId));
    }

    public static boolean blockBoundsInChunks(List<BuildingBlockData> blocks, Set<Long> cityChunks) {
        if (blocks == null || blocks.isEmpty()) {
            return false;
        }
        List<BlockPos> positions = new ArrayList<>(blocks.size());
        for (BuildingBlockData block : blocks) {
            if (block == null || block.relativePos() == null) {
                return false;
            }
            positions.add(block.relativePos());
        }
        return positionBoundsInChunks(positions, cityChunks);
    }

    public static boolean positionBoundsInChunks(List<BlockPos> positions, Set<Long> cityChunks) {
        if (positions == null || positions.isEmpty() || cityChunks == null || cityChunks.isEmpty()) {
            return false;
        }
        BlockPos first = positions.getFirst();
        int minX = first.getX();
        int maxX = first.getX();
        int minZ = first.getZ();
        int maxZ = first.getZ();
        for (BlockPos pos : positions) {
            if (pos == null) {
                return false;
            }
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return boundsInChunks(minX, maxX, minZ, maxZ, cityChunks);
    }

    public static boolean isPositionInChunks(BlockPos pos, Set<Long> cityChunks) {
        if (pos == null || cityChunks == null || cityChunks.isEmpty()) {
            return false;
        }
        return cityChunks.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private static boolean boundsInChunks(int minX, int maxX, int minZ, int maxZ, Set<Long> cityChunks) {
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!cityChunks.contains(ChunkPos.asLong(chunkX, chunkZ))) {
                    return false;
                }
            }
        }
        return true;
    }
}
