package common.cn.kafei.simukraft.storage;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@SuppressWarnings("null")
public final class IndustrialBoxSqliteRepository {
    private final SimuSqliteDatabase database;

    public IndustrialBoxSqliteRepository(SimuSqliteDatabase database) {
        this.database = database;
    }

    public synchronized void saveAll(CompoundTag tag) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            SqliteNbtHelper.clearTables(connection, "industrial_boxes");
            try {
                ListTag boxes = tag.getList("Boxes", CompoundTag.TAG_COMPOUND);
                for (int i = 0; i < boxes.size(); i++) {
                    saveBox(connection, boxes.getCompound(i));
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save industrial boxes to SQLite", exception);
        }
    }

    public synchronized void upsert(CompoundTag boxTag) {
        try (Connection connection = database.openConnection()) {
            saveBox(connection, boxTag);
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save industrial box to SQLite", exception);
        }
    }

    public synchronized void delete(long boxPosLong) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM industrial_boxes WHERE box_pos_long = ?")) {
            statement.setLong(1, boxPosLong);
            statement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to delete industrial box from SQLite", exception);
        }
    }

    public synchronized CompoundTag loadAll() {
        CompoundTag tag = new CompoundTag();
        ListTag boxes = new ListTag();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM industrial_boxes ORDER BY box_pos_long");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                CompoundTag box = new CompoundTag();
                box.putLong("BoxPos", resultSet.getLong("box_pos_long"));
                box.putString("BuildingId", resultSet.getString("building_id"));
                box.putString("DefinitionId", resultSet.getString("definition_id"));
                box.putString("SelectedRecipeId", resultSet.getString("selected_recipe_id"));
                box.putBoolean("Running", resultSet.getInt("running") != 0);
                box.putBoolean("SpawnEntityDone", resultSet.getInt("spawn_entity_done") != 0);
                box.putInt("CurrentStep", resultSet.getInt("current_step"));
                box.putString("StatusKey", resultSet.getString("status_key"));
                box.putString("StatusText", resultSet.getString("status_text"));
                box.putString("MachineState", resultSet.getString("machine_state"));
                box.putLong("UpdatedAt", resultSet.getLong("updated_at"));
                boxes.add(box);
            }
            tag.put("Boxes", boxes);
            return boxes.isEmpty() ? null : tag;
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to load industrial boxes from SQLite", exception);
            return null;
        }
    }

    private void saveBox(Connection connection, CompoundTag box) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO industrial_boxes(box_pos_long, building_id, definition_id, selected_recipe_id, running, spawn_entity_done, current_step, status_key, status_text, machine_state, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(box_pos_long) DO UPDATE SET building_id = excluded.building_id, definition_id = excluded.definition_id, selected_recipe_id = excluded.selected_recipe_id, running = excluded.running, spawn_entity_done = excluded.spawn_entity_done, current_step = excluded.current_step, status_key = excluded.status_key, status_text = excluded.status_text, machine_state = excluded.machine_state, updated_at = excluded.updated_at")) {
            statement.setLong(1, box.getLong("BoxPos"));
            statement.setString(2, box.getString("BuildingId"));
            statement.setString(3, box.getString("DefinitionId"));
            statement.setString(4, box.getString("SelectedRecipeId"));
            statement.setInt(5, box.getBoolean("Running") ? 1 : 0);
            statement.setInt(6, box.getBoolean("SpawnEntityDone") ? 1 : 0);
            statement.setInt(7, box.getInt("CurrentStep"));
            statement.setString(8, box.getString("StatusKey"));
            statement.setString(9, box.getString("StatusText"));
            statement.setString(10, box.getString("MachineState"));
            statement.setLong(11, box.getLong("UpdatedAt"));
            statement.executeUpdate();
        }
    }
}
