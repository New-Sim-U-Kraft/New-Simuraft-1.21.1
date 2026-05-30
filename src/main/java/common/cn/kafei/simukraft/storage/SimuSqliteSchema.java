package common.cn.kafei.simukraft.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SimuSqliteSchema {
    private SimuSqliteSchema() {
    }

    public static void initialize(SimuSqliteDatabase database) {
        try {
            Files.createDirectories(database.databasePath().getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create Sim-U-Kraft SQLite directory", exception);
        }
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS cities(city_id TEXT PRIMARY KEY, city_name TEXT NOT NULL, core_x INTEGER NOT NULL, core_y INTEGER NOT NULL, core_z INTEGER NOT NULL, funds REAL NOT NULL, city_level INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS city_members(city_id TEXT NOT NULL, player_id TEXT NOT NULL, player_name TEXT NOT NULL, permission_level TEXT NOT NULL, PRIMARY KEY(city_id, player_id), FOREIGN KEY(city_id) REFERENCES cities(city_id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS finance_transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, city_id TEXT NOT NULL, sort_index INTEGER NOT NULL, time INTEGER NOT NULL, actor_id TEXT, actor_name TEXT NOT NULL, amount REAL NOT NULL, balance_after REAL NOT NULL, type TEXT NOT NULL, reason TEXT NOT NULL, FOREIGN KEY(city_id) REFERENCES cities(city_id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS city_chunks(city_id TEXT NOT NULL, chunk_long INTEGER NOT NULL, PRIMARY KEY(city_id, chunk_long))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS city_pois(poi_id TEXT PRIMARY KEY, city_id TEXT NOT NULL, pos_long INTEGER NOT NULL, type TEXT NOT NULL, capacity INTEGER NOT NULL, active INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS citizens(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, gender TEXT NOT NULL, age INTEGER NOT NULL, lifespan INTEGER NOT NULL, job_type TEXT NOT NULL, job_id TEXT NOT NULL, status TEXT NOT NULL, work_status TEXT NOT NULL, work_need_detail TEXT NOT NULL, status_label TEXT NOT NULL, is_working INTEGER NOT NULL, npc_id INTEGER NOT NULL, skin_path TEXT NOT NULL, city_id TEXT, home_id TEXT, workplace_id TEXT, workplace_pos_long INTEGER, health REAL NOT NULL, hunger REAL NOT NULL, happiness REAL NOT NULL, sick INTEGER NOT NULL, child INTEGER NOT NULL, child_growth_due_day INTEGER NOT NULL, born_day INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS citizen_skills(citizen_id TEXT NOT NULL, skill_key TEXT NOT NULL, skill_value INTEGER NOT NULL, PRIMARY KEY(citizen_id, skill_key), FOREIGN KEY(citizen_id) REFERENCES citizens(uuid) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS building_tasks(task_id TEXT PRIMARY KEY, citizen_id TEXT NOT NULL UNIQUE, city_id TEXT, dimension_id TEXT NOT NULL, build_box_x INTEGER NOT NULL, build_box_y INTEGER NOT NULL, build_box_z INTEGER NOT NULL, category TEXT NOT NULL, building_file_name TEXT NOT NULL, display_name TEXT NOT NULL, amount TEXT NOT NULL DEFAULT '', structure_file_name TEXT NOT NULL, origin_x INTEGER NOT NULL, origin_y INTEGER NOT NULL, origin_z INTEGER NOT NULL, rotation_degrees INTEGER NOT NULL, current_block_index INTEGER NOT NULL, total_blocks INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS building_task_pois(task_id TEXT NOT NULL, poi_key TEXT NOT NULL, poi_type TEXT NOT NULL, capacity INTEGER NOT NULL, PRIMARY KEY(task_id, poi_key), FOREIGN KEY(task_id) REFERENCES building_tasks(task_id) ON DELETE CASCADE)");
            addColumnIfMissing(connection, "citizens", "workplace_pos_long", "INTEGER");
            addColumnIfMissing(connection, "building_tasks", "amount", "TEXT NOT NULL DEFAULT ''");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_city_members_city ON city_members(city_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_finance_city ON finance_transactions(city_id, sort_index)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_city_chunks_city ON city_chunks(city_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_city_pois_city ON city_pois(city_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_citizens_city ON citizens(city_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_building_tasks_city ON building_tasks(city_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_building_tasks_dimension ON building_tasks(dimension_id)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize Sim-U-Kraft SQLite database", exception);
        }
    }

    private static void addColumnIfMissing(Connection connection, String tableName, String columnName, String columnDefinition) throws SQLException {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        }
    }
}
