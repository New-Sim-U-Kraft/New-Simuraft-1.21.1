package common.cn.kafei.simukraft.storage;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@SuppressWarnings("null")
public final class CitizenSqliteRepository {
    private final SimuSqliteDatabase database;

    public CitizenSqliteRepository(SimuSqliteDatabase database) {
        this.database = database;
    }

    public synchronized void saveAll(CompoundTag tag) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            SqliteNbtHelper.clearTables(connection, "citizen_skills", "citizens");
            try {
                ListTag citizens = tag.getList("Citizens", CompoundTag.TAG_COMPOUND);
                for (int i = 0; i < citizens.size(); i++) {
                    saveCitizen(connection, citizens.getCompound(i));
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save citizens to SQLite", exception);
        }
    }

    public synchronized void upsert(CompoundTag citizenTag) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                saveCitizen(connection, citizenTag);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to save citizen to SQLite", exception);
        }
    }

    public synchronized void delete(java.util.UUID citizenId) {
        if (citizenId == null) {
            return;
        }
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM citizens WHERE uuid = ?")) {
            statement.setString(1, citizenId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to delete citizen from SQLite", exception);
        }
    }

    public synchronized void clearEmployment(java.util.UUID citizenId) {
        if (citizenId == null) {
            return;
        }
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE citizens SET job_type = 'UNEMPLOYED', job_id = 'UNEMPLOYED', status = 'idle', work_status = 'work_status.idle', work_need_detail = '', status_label = '', is_working = 0, workplace_id = NULL, workplace_pos_long = NULL WHERE uuid = ?")) {
            statement.setString(1, citizenId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            SimuKraft.LOGGER.error("Failed to clear citizen employment in SQLite", exception);
        }
    }

    public synchronized CompoundTag loadAll() {
        CompoundTag tag = new CompoundTag();
        ListTag citizens = new ListTag();
        try (Connection connection = database.openConnection()) {
            java.util.Map<String, CompoundTag> skillsByUuid = loadAllCitizenSkills(connection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM citizens ORDER BY uuid");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String uuid = resultSet.getString("uuid");
                    CompoundTag citizen = new CompoundTag();
                    citizen.putUUID("Uuid", java.util.UUID.fromString(uuid));
                    citizen.putString("Name", resultSet.getString("name"));
                    citizen.putString("Gender", resultSet.getString("gender"));
                    citizen.putInt("Age", resultSet.getInt("age"));
                    citizen.putInt("Lifespan", resultSet.getInt("lifespan"));
                    citizen.putString("JobType", resultSet.getString("job_type"));
                    citizen.putString("JobId", resultSet.getString("job_id"));
                    citizen.putString("Status", resultSet.getString("status"));
                    citizen.putString("WorkStatus", resultSet.getString("work_status"));
                    citizen.putString("WorkNeedDetail", resultSet.getString("work_need_detail"));
                    citizen.putString("StatusLabel", resultSet.getString("status_label"));
                    citizen.putBoolean("IsWorking", resultSet.getInt("is_working") != 0);
                    citizen.putInt("NpcId", resultSet.getInt("npc_id"));
                    citizen.putString("SkinPath", resultSet.getString("skin_path"));
                    SqliteNbtHelper.putNullableUuid(citizen, "CityId", resultSet.getString("city_id"));
                    SqliteNbtHelper.putNullableUuid(citizen, "HomeId", resultSet.getString("home_id"));
                    SqliteNbtHelper.putNullableUuid(citizen, "WorkplaceId", resultSet.getString("workplace_id"));
                    long workplacePosLong = resultSet.getLong("workplace_pos_long");
                    if (!resultSet.wasNull()) {
                        citizen.putLong("WorkplacePos", workplacePosLong);
                    }
                    citizen.putDouble("Health", resultSet.getDouble("health"));
                    citizen.putDouble("Happiness", resultSet.getDouble("happiness"));
                    citizen.putBoolean("Sick", resultSet.getInt("sick") != 0);
                    citizen.putBoolean("Child", resultSet.getInt("child") != 0);
                    citizen.putLong("ChildGrowthDueDay", resultSet.getLong("child_growth_due_day"));
                    citizen.putLong("BornDay", resultSet.getLong("born_day"));
                    String dimId = resultSet.getString("dimension_id");
                    citizen.putString("DimensionId", dimId != null ? dimId : "minecraft:overworld");
                    citizen.put("Skills", skillsByUuid.getOrDefault(uuid, new CompoundTag()));
                    citizens.add(citizen);
                }
            }
            tag.put("Citizens", citizens);
            return tag;
        } catch (SQLException | IllegalArgumentException exception) {
            SimuKraft.LOGGER.error("Failed to load citizens from SQLite", exception);
            return null;
        }
    }

    private void saveCitizen(Connection connection, CompoundTag citizen) throws SQLException {
        String uuid = citizen.getUUID("Uuid").toString();
        try (PreparedStatement citizenStatement = connection.prepareStatement("INSERT INTO citizens(uuid, name, gender, age, lifespan, job_type, job_id, status, work_status, work_need_detail, status_label, is_working, npc_id, skin_path, city_id, home_id, workplace_id, workplace_pos_long, health, happiness, sick, child, child_growth_due_day, born_day, dimension_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, gender = excluded.gender, age = excluded.age, lifespan = excluded.lifespan, job_type = excluded.job_type, job_id = excluded.job_id, status = excluded.status, work_status = excluded.work_status, work_need_detail = excluded.work_need_detail, status_label = excluded.status_label, is_working = excluded.is_working, npc_id = excluded.npc_id, skin_path = excluded.skin_path, city_id = excluded.city_id, home_id = excluded.home_id, workplace_id = excluded.workplace_id, workplace_pos_long = excluded.workplace_pos_long, health = excluded.health, happiness = excluded.happiness, sick = excluded.sick, child = excluded.child, child_growth_due_day = excluded.child_growth_due_day, born_day = excluded.born_day, dimension_id = excluded.dimension_id");
             PreparedStatement deleteSkills = connection.prepareStatement("DELETE FROM citizen_skills WHERE citizen_id = ?");
             PreparedStatement skillStatement = connection.prepareStatement("INSERT INTO citizen_skills(citizen_id, skill_key, skill_value) VALUES(?, ?, ?)")) {
            citizenStatement.setString(1, uuid);
            citizenStatement.setString(2, citizen.getString("Name"));
            citizenStatement.setString(3, citizen.getString("Gender"));
            citizenStatement.setInt(4, citizen.getInt("Age"));
            citizenStatement.setInt(5, citizen.getInt("Lifespan"));
            citizenStatement.setString(6, citizen.getString("JobType"));
            citizenStatement.setString(7, citizen.getString("JobId"));
            citizenStatement.setString(8, citizen.getString("Status"));
            citizenStatement.setString(9, citizen.getString("WorkStatus"));
            citizenStatement.setString(10, citizen.getString("WorkNeedDetail"));
            citizenStatement.setString(11, citizen.getString("StatusLabel"));
            citizenStatement.setInt(12, citizen.getBoolean("IsWorking") ? 1 : 0);
            citizenStatement.setInt(13, citizen.getInt("NpcId"));
            citizenStatement.setString(14, citizen.getString("SkinPath"));
            SqliteNbtHelper.setNullableString(citizenStatement, 15, citizen.hasUUID("CityId") ? citizen.getUUID("CityId").toString() : null);
            SqliteNbtHelper.setNullableString(citizenStatement, 16, citizen.hasUUID("HomeId") ? citizen.getUUID("HomeId").toString() : null);
            SqliteNbtHelper.setNullableString(citizenStatement, 17, citizen.hasUUID("WorkplaceId") ? citizen.getUUID("WorkplaceId").toString() : null);
            if (citizen.contains("WorkplacePos")) {
                citizenStatement.setLong(18, citizen.getLong("WorkplacePos"));
            } else {
                citizenStatement.setObject(18, null);
            }
            citizenStatement.setDouble(19, citizen.getDouble("Health"));
            citizenStatement.setDouble(20, citizen.getDouble("Happiness"));
            citizenStatement.setInt(21, citizen.getBoolean("Sick") ? 1 : 0);
            citizenStatement.setInt(22, citizen.getBoolean("Child") ? 1 : 0);
            citizenStatement.setLong(23, citizen.getLong("ChildGrowthDueDay"));
            citizenStatement.setLong(24, citizen.getLong("BornDay"));
            String dimId = citizen.contains("DimensionId") ? citizen.getString("DimensionId") : "minecraft:overworld";
            citizenStatement.setString(25, dimId.isBlank() ? "minecraft:overworld" : dimId);
            citizenStatement.executeUpdate();
            deleteSkills.setString(1, uuid);
            deleteSkills.executeUpdate();
            CompoundTag skills = citizen.getCompound("Skills");
            for (String key : skills.getAllKeys()) {
                skillStatement.setString(1, uuid);
                skillStatement.setString(2, key);
                skillStatement.setInt(3, skills.getInt(key));
                skillStatement.addBatch();
            }
            skillStatement.executeBatch();
        }
    }

    private java.util.Map<String, CompoundTag> loadAllCitizenSkills(Connection connection) throws SQLException {
        java.util.Map<String, CompoundTag> result = new java.util.HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT citizen_id, skill_key, skill_value FROM citizen_skills ORDER BY citizen_id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.computeIfAbsent(resultSet.getString("citizen_id"), ignored -> new CompoundTag())
                        .putInt(resultSet.getString("skill_key"), resultSet.getInt("skill_value"));
            }
        }
        return result;
    }

    private CompoundTag loadCitizenSkills(Connection connection, String uuid) throws SQLException {
        CompoundTag skills = new CompoundTag();
        try (PreparedStatement statement = connection.prepareStatement("SELECT skill_key, skill_value FROM citizen_skills WHERE citizen_id = ? ORDER BY skill_key")) {
            statement.setString(1, uuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    skills.putInt(resultSet.getString("skill_key"), resultSet.getInt("skill_value"));
                }
            }
        }
        return skills;
    }
}
