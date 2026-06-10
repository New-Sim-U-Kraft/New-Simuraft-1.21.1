package common.cn.kafei.simukraft.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.planner.PlanOperation;
import common.cn.kafei.simukraft.planner.PlanningTaskData;
import net.minecraft.core.BlockPos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规划任务的 SQLite 仓库。任务扁平存储（区域用 min/max 的 long 表示），按维度加载用于恢复，按市民删除。
 */

@SuppressWarnings("null")
public final class PlanningTaskSqliteRepository {
    private final SimuSqliteDatabase database;

    public PlanningTaskSqliteRepository(SimuSqliteDatabase database) {
        this.database = database;
    }

    public synchronized void upsert(PlanningTaskData task) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO planning_tasks(task_id, citizen_id, city_id, dimension_id, box_long, min_long, max_long, operation, fill_block, source_block, material_chest_long, replacement_map, current_index, total_blocks, completed_blocks, target_blocks, status, created_at, updated_at) "
                             + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON CONFLICT(task_id) DO UPDATE SET citizen_id = excluded.citizen_id, city_id = excluded.city_id, dimension_id = excluded.dimension_id, box_long = excluded.box_long, min_long = excluded.min_long, max_long = excluded.max_long, operation = excluded.operation, fill_block = excluded.fill_block, source_block = excluded.source_block, material_chest_long = excluded.material_chest_long, replacement_map = excluded.replacement_map, current_index = excluded.current_index, total_blocks = excluded.total_blocks, completed_blocks = excluded.completed_blocks, target_blocks = excluded.target_blocks, status = excluded.status, updated_at = excluded.updated_at")) {
            statement.setString(1, task.taskId().toString());
            statement.setString(2, task.citizenId().toString());
            statement.setString(3, task.cityId() != null ? task.cityId().toString() : null);
            statement.setString(4, task.dimensionId());
            statement.setLong(5, task.buildBoxPos().asLong());
            statement.setLong(6, task.minPos().asLong());
            statement.setLong(7, task.maxPos().asLong());
            statement.setString(8, task.operation().id());
            statement.setString(9, task.fillBlockId());
            statement.setString(10, task.sourceBlockId());
            if (task.materialChestPos() != null) {
                statement.setLong(11, task.materialChestPos().asLong());
            } else {
                statement.setNull(11, Types.BIGINT);
            }
            statement.setString(12, encodeReplacementMap(task.replacementMap()));
            statement.setInt(13, task.currentIndex());
            statement.setInt(14, task.totalBlocks());
            statement.setInt(15, task.completedBlocks());
            statement.setInt(16, task.targetBlocks());
            statement.setString(17, task.status());
            statement.setLong(18, task.createdAt());
            statement.setLong(19, task.updatedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save planning task to SQLite", exception);
        }
    }

    public synchronized List<PlanningTaskData> findByDimension(String dimensionId) {
        List<PlanningTaskData> tasks = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM planning_tasks WHERE dimension_id = ?")) {
            statement.setString(1, dimensionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tasks.add(read(resultSet));
                }
            }
        } catch (SQLException | IllegalArgumentException exception) {
            SimuKraft.LOGGER.error("Failed to load planning tasks from SQLite", exception);
        }
        return tasks;
    }

    public synchronized void deleteByCitizen(UUID citizenId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM planning_tasks WHERE citizen_id = ?")) {
            statement.setString(1, citizenId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to delete planning task from SQLite", exception);
        }
    }

    private PlanningTaskData read(ResultSet resultSet) throws SQLException {
        String cityIdString = resultSet.getString("city_id");
        return new PlanningTaskData(
                UUID.fromString(resultSet.getString("task_id")),
                UUID.fromString(resultSet.getString("citizen_id")),
                cityIdString != null ? UUID.fromString(cityIdString) : null,
                resultSet.getString("dimension_id"),
                BlockPos.of(resultSet.getLong("box_long")),
                BlockPos.of(resultSet.getLong("min_long")),
                BlockPos.of(resultSet.getLong("max_long")),
                PlanOperation.fromId(resultSet.getString("operation")),
                resultSet.getString("fill_block"),
                resultSet.getString("source_block"),
                readNullableBlockPos(resultSet, "material_chest_long"),
                decodeReplacementMap(resultSet.getString("replacement_map")),
                resultSet.getInt("current_index"),
                resultSet.getInt("total_blocks"),
                resultSet.getInt("completed_blocks"),
                normalizedTargetBlocks(resultSet),
                resultSet.getString("status"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private static int normalizedTargetBlocks(ResultSet resultSet) throws SQLException {
        int targetBlocks = resultSet.getInt("target_blocks");
        return targetBlocks > 0 ? targetBlocks : resultSet.getInt("total_blocks");
    }

    private static BlockPos readNullableBlockPos(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : BlockPos.of(value);
    }

    private static String encodeReplacementMap(Map<String, String> replacementMap) {
        if (replacementMap == null || replacementMap.isEmpty()) {
            return "";
        }
        JsonObject object = new JsonObject();
        replacementMap.forEach((source, target) -> {
            if (source != null && target != null && !source.isBlank() && !target.isBlank()) {
                object.addProperty(source, target);
            }
        });
        return object.toString();
    }

    private static Map<String, String> decodeReplacementMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            Map<String, String> map = new LinkedHashMap<>();
            object.entrySet().forEach(entry -> {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    String source = entry.getKey();
                    String target = entry.getValue().getAsString();
                    if (source != null && target != null && !source.isBlank() && !target.isBlank()) {
                        map.put(source, target);
                    }
                }
            });
            return map;
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Failed to parse planning replacement map from SQLite: {}", json, exception);
            return Map.of();
        }
    }
}
