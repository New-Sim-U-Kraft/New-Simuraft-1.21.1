package common.cn.kafei.simukraft.path;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Map;

public record PathSnapshot(ResourceLocation dimensionId, BlockPos startPos, BlockPos targetPos, Map<Long, PathCell> cells, int minY, int maxY, long createdAt) {
    public PathCell cell(BlockPos pos) {
        return cells.get(PathCell.key(pos));
    }

    public PathCell cell(int x, int y, int z) {
        return cells.get(PathCell.key(x, y, z));
    }

    public Collection<PathCell> allCells() {
        return cells.values();
    }

    public boolean contains(BlockPos pos) {
        return cells.containsKey(PathCell.key(pos));
    }
}
