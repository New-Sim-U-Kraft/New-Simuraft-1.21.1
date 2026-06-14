package common.cn.kafei.simukraft.storage;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("null")
public final class CommercialSqliteRepository {
    private final SimuSqliteDatabase database;

    public CommercialSqliteRepository(SimuSqliteDatabase database) {
        this.database = database;
    }

    /** saveBoxes: 保存全部商业箱状态。 */
    public synchronized void saveBoxes(CompoundTag tag) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            SqliteNbtHelper.clearTables(connection, "commercial_boxes");
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
            SimuKraft.LOGGER.error("Failed to save commercial boxes to SQLite", exception);
        }
    }

    /** upsertBox: 保存单个商业箱状态。 */
    public synchronized void upsertBox(CompoundTag boxTag) {
        try (Connection connection = database.openConnection()) {
            saveBox(connection, boxTag);
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save commercial box to SQLite", exception);
        }
    }

    /** deleteBox: 删除商业箱和其库存。 */
    public synchronized void deleteBox(long boxPosLong) {
        try (Connection connection = database.openConnection();
             PreparedStatement stockStatement = connection.prepareStatement("DELETE FROM commercial_stock WHERE box_pos_long = ?");
             PreparedStatement boxStatement = connection.prepareStatement("DELETE FROM commercial_boxes WHERE box_pos_long = ?")) {
            stockStatement.setLong(1, boxPosLong);
            stockStatement.executeUpdate();
            boxStatement.setLong(1, boxPosLong);
            boxStatement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to delete commercial box from SQLite", exception);
        }
    }

    /** loadBoxes: 读取全部商业箱状态。 */
    public synchronized CompoundTag loadBoxes() {
        CompoundTag tag = new CompoundTag();
        ListTag boxes = new ListTag();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM commercial_boxes ORDER BY box_pos_long");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                CompoundTag box = new CompoundTag();
                box.putLong("BoxPos", resultSet.getLong("box_pos_long"));
                box.putString("BuildingId", resultSet.getString("building_id"));
                box.putString("DefinitionId", resultSet.getString("definition_id"));
                box.putBoolean("Running", resultSet.getInt("running") != 0);
                box.putString("StatusKey", resultSet.getString("status_key"));
                box.putString("StatusText", resultSet.getString("status_text"));
                box.putLong("UpdatedAt", resultSet.getLong("updated_at"));
                boxes.add(box);
            }
            tag.put("Boxes", boxes);
            return boxes.isEmpty() ? null : tag;
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to load commercial boxes from SQLite", exception);
            return null;
        }
    }

    /** saveStock: 保存全部商业库存。 */
    public synchronized void saveStock(CompoundTag tag) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            SqliteNbtHelper.clearTables(connection, "commercial_stock");
            try {
                ListTag stock = tag.getList("Stock", CompoundTag.TAG_COMPOUND);
                for (int i = 0; i < stock.size(); i++) {
                    saveStockEntry(connection, stock.getCompound(i));
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save commercial stock to SQLite", exception);
        }
    }

    /** upsertStockEntries: 批量保存多个商业库存条目（单次事务）。 */
    public synchronized void upsertStockEntries(List<CompoundTag> stockTags) {
        if (stockTags == null || stockTags.isEmpty()) {
            return;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (CompoundTag stockTag : stockTags) {
                    saveStockEntry(connection, stockTag);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save commercial stock entries to SQLite", exception);
        }
    }

    /** upsertStockEntry: 保存单个商业库存条目。 */
    public synchronized void upsertStockEntry(CompoundTag stockTag) {
        try (Connection connection = database.openConnection()) {
            saveStockEntry(connection, stockTag);
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save commercial stock entry to SQLite", exception);
        }
    }

    /** deleteStockAtBox: 删除指定商业箱库存。 */
    public synchronized void deleteStockAtBox(long boxPosLong) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM commercial_stock WHERE box_pos_long = ?")) {
            statement.setLong(1, boxPosLong);
            statement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to delete commercial stock from SQLite", exception);
        }
    }

    /** loadStock: 读取全部商业库存。 */
    public synchronized CompoundTag loadStock() {
        CompoundTag tag = new CompoundTag();
        ListTag stock = new ListTag();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM commercial_stock ORDER BY box_pos_long, item_id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                CompoundTag entry = new CompoundTag();
                entry.putLong("BoxPos", resultSet.getLong("box_pos_long"));
                entry.putString("ItemId", resultSet.getString("item_id"));
                entry.putInt("CurrentStock", resultSet.getInt("current_stock"));
                entry.putInt("MaxStock", resultSet.getInt("max_stock"));
                entry.putLong("LastRestockGameTime", resultSet.getLong("last_restock_game_time"));
                entry.putLong("UpdatedAt", resultSet.getLong("updated_at"));
                stock.add(entry);
            }
            tag.put("Stock", stock);
            return stock.isEmpty() ? null : tag;
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to load commercial stock from SQLite", exception);
            return null;
        }
    }

    /** addDailyIncome: 累加指定城市在某个 MC 日的商业营业收入。 */
    public synchronized boolean addDailyIncome(UUID cityId, long incomeDay, double amount) {
        if (cityId == null || incomeDay <= 0L || amount <= 0.0D) {
            return false;
        }
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO commercial_daily_income(city_id, income_day, income, tax_collected) "
                             + "VALUES(?, ?, ?, 0) "
                             + "ON CONFLICT(city_id, income_day) DO UPDATE SET income = income + excluded.income")) {
            statement.setString(1, cityId.toString());
            statement.setLong(2, incomeDay);
            statement.setDouble(3, amount);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to add commercial daily income to SQLite", exception);
            return false;
        }
    }

    /** loadUntaxedIncomeBefore: 读取指定日期之前尚未上交企业税的商业收入。 */
    public synchronized Map<UUID, Double> loadUntaxedIncomeBefore(long dayExclusive) {
        if (dayExclusive <= 1L) {
            return Map.of();
        }
        Map<UUID, Double> result = new LinkedHashMap<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT city_id, SUM(income) AS income FROM commercial_daily_income "
                             + "WHERE income_day < ? AND tax_collected = 0 GROUP BY city_id")) {
            statement.setLong(1, dayExclusive);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.put(UUID.fromString(resultSet.getString("city_id")), resultSet.getDouble("income"));
                }
            }
        } catch (SQLException | IllegalArgumentException exception) {
            SimuKraft.LOGGER.error("Failed to load untaxed commercial income from SQLite", exception);
            return Map.of();
        }
        return Map.copyOf(result);
    }

    /** markIncomeTaxCollectedBefore: 标记指定城市在日期之前的商业收入已完成企业税结算。 */
    public synchronized boolean markIncomeTaxCollectedBefore(UUID cityId, long dayExclusive) {
        if (cityId == null || dayExclusive <= 1L) {
            return false;
        }
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE commercial_daily_income SET tax_collected = 1 "
                             + "WHERE city_id = ? AND income_day < ? AND tax_collected = 0")) {
            statement.setString(1, cityId.toString());
            statement.setLong(2, dayExclusive);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to mark commercial income tax as collected in SQLite", exception);
            return false;
        }
    }

    private void saveBox(Connection connection, CompoundTag box) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO commercial_boxes(box_pos_long, building_id, definition_id, running, status_key, status_text, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(box_pos_long) DO UPDATE SET building_id = excluded.building_id, definition_id = excluded.definition_id, running = excluded.running, status_key = excluded.status_key, status_text = excluded.status_text, updated_at = excluded.updated_at")) {
            statement.setLong(1, box.getLong("BoxPos"));
            statement.setString(2, box.getString("BuildingId"));
            statement.setString(3, box.getString("DefinitionId"));
            statement.setInt(4, box.getBoolean("Running") ? 1 : 0);
            statement.setString(5, box.getString("StatusKey"));
            statement.setString(6, box.getString("StatusText"));
            statement.setLong(7, box.getLong("UpdatedAt"));
            statement.executeUpdate();
        }
    }

    private void saveStockEntry(Connection connection, CompoundTag stock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO commercial_stock(box_pos_long, item_id, current_stock, max_stock, last_restock_game_time, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(box_pos_long, item_id) DO UPDATE SET current_stock = excluded.current_stock, max_stock = excluded.max_stock, last_restock_game_time = excluded.last_restock_game_time, updated_at = excluded.updated_at")) {
            statement.setLong(1, stock.getLong("BoxPos"));
            statement.setString(2, stock.getString("ItemId"));
            statement.setInt(3, stock.getInt("CurrentStock"));
            statement.setInt(4, stock.getInt("MaxStock"));
            statement.setLong(5, stock.getLong("LastRestockGameTime"));
            statement.setLong(6, stock.getLong("UpdatedAt"));
            statement.executeUpdate();
        }
    }
}
