package common.cn.kafei.simukraft.planner;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一条规划任务的不可变快照。区域用 min/max 表示，执行时按线性游标 {@link #blockAt(int)} 推导第 index 个方块，
 * 不存整张方块列表（避免大区域撑爆数据库）。fillBlockId/sourceBlockId 为方块注册名字符串，按需使用。
 */
public record PlanningTaskData(UUID taskId,
                               UUID citizenId,
                               UUID cityId,
                               String dimensionId,
                               BlockPos buildBoxPos,
                               BlockPos minPos,
                               BlockPos maxPos,
                               PlanOperation operation,
                               String fillBlockId,
                               String sourceBlockId,
                               @Nullable BlockPos materialChestPos,
                               Map<String, String> replacementMap,
                               int currentIndex,
                               int totalBlocks,
                               int completedBlocks,
                               int targetBlocks,
                               String status,
                               long createdAt,
                               long updatedAt) {

    public PlanningTaskData(UUID taskId,
                            UUID citizenId,
                            UUID cityId,
                            String dimensionId,
                            BlockPos buildBoxPos,
                            BlockPos minPos,
                            BlockPos maxPos,
                            PlanOperation operation,
                            String fillBlockId,
                            String sourceBlockId,
                            int currentIndex,
                            int totalBlocks,
                            String status,
                            long createdAt,
                            long updatedAt) {
        this(taskId, citizenId, cityId, dimensionId, buildBoxPos, minPos, maxPos, operation, fillBlockId, sourceBlockId,
                null, Map.of(), currentIndex, totalBlocks, 0, totalBlocks, status, createdAt, updatedAt);
    }

    public PlanningTaskData {
        buildBoxPos = buildBoxPos.immutable();
        minPos = minPos.immutable();
        maxPos = maxPos.immutable();
        fillBlockId = fillBlockId == null ? "" : fillBlockId;
        sourceBlockId = sourceBlockId == null ? "" : sourceBlockId;
        materialChestPos = materialChestPos != null ? materialChestPos.immutable() : null;
        replacementMap = immutableReplacementMap(replacementMap);
        currentIndex = Math.max(0, currentIndex);
        totalBlocks = Math.max(0, totalBlocks);
        targetBlocks = targetBlocks <= 0 ? totalBlocks : targetBlocks;
        completedBlocks = Math.clamp(completedBlocks, 0, targetBlocks);
    }

    public static int volume(BlockPos min, BlockPos max) {
        long dx = (long) (max.getX() - min.getX() + 1);
        long dy = (long) (max.getY() - min.getY() + 1);
        long dz = (long) (max.getZ() - min.getZ() + 1);
        long total = dx * dy * dz;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, total));
    }

    public int width() {
        return maxPos.getX() - minPos.getX() + 1;
    }

    public int depth() {
        return maxPos.getZ() - minPos.getZ() + 1;
    }

    public int height() {
        return maxPos.getY() - minPos.getY() + 1;
    }

    // 把线性 index 映射到区域内某方块；REMOVE 从顶层往下，FILL/REPLACE 从底层往上。
    public BlockPos blockAt(int index) {
        int dx = width();
        int dz = depth();
        int layerSize = Math.max(1, dx * dz);
        int layer = index / layerSize;
        int rem = index % layerSize;
        int x = minPos.getX() + rem % dx;
        int z = minPos.getZ() + rem / dx;
        int y = operation.ascendingY() ? minPos.getY() + layer : maxPos.getY() - layer;
        return new BlockPos(x, y, z);
    }

    public Map<String, String> effectiveReplacementMap() {
        if (operation != PlanOperation.REPLACE) {
            return Map.of();
        }
        if (!replacementMap.isEmpty()) {
            return replacementMap;
        }
        if (!sourceBlockId.isBlank() && !fillBlockId.isBlank()) {
            return Map.of(sourceBlockId, fillBlockId);
        }
        return Map.of();
    }

    public PlanningTaskData withProgress(int newIndex, String newStatus, long updatedAtMs) {
        return withProgress(newIndex, completedBlocks, newStatus, updatedAtMs);
    }

    public PlanningTaskData withProgress(int newIndex, int newCompletedBlocks, String newStatus, long updatedAtMs) {
        return new PlanningTaskData(taskId, citizenId, cityId, dimensionId, buildBoxPos, minPos, maxPos,
                operation, fillBlockId, sourceBlockId, materialChestPos, replacementMap, newIndex, totalBlocks,
                newCompletedBlocks, targetBlocks, newStatus, createdAt, updatedAtMs);
    }

    public PlanningTaskData withTargetBlocks(int newTargetBlocks) {
        return new PlanningTaskData(taskId, citizenId, cityId, dimensionId, buildBoxPos, minPos, maxPos,
                operation, fillBlockId, sourceBlockId, materialChestPos, replacementMap, currentIndex, totalBlocks,
                completedBlocks, newTargetBlocks, status, createdAt, updatedAt);
    }

    public PlanningTaskData withStatus(String newStatus, long updatedAtMs) {
        return withProgress(currentIndex, newStatus, updatedAtMs);
    }

    private static Map<String, String> immutableReplacementMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((source, target) -> {
            if (source != null && target != null && !source.isBlank() && !target.isBlank()) {
                copy.put(source, target);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
