package common.cn.kafei.simukraft.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercialSqliteRepositoryTest {
    @TempDir
    Path tempDir;

    /** accumulatesIncomeAndMarksEnterpriseTaxCollected: 验证商业日收入累加后只结算一次企业税。 */
    @Test
    void accumulatesIncomeAndMarksEnterpriseTaxCollected() throws Exception {
        SimuSqliteDatabase database = openDatabase(tempDir.resolve("commercial.sqlite"));
        CommercialSqliteRepository repository = new CommercialSqliteRepository(database);
        UUID cityId = UUID.randomUUID();
        insertCity(database, cityId);

        assertTrue(repository.addDailyIncome(cityId, 1L, 10.0D));
        assertTrue(repository.addDailyIncome(cityId, 1L, 6.0D));
        assertTrue(repository.addDailyIncome(cityId, 2L, 4.0D));

        Map<UUID, Double> dayTwoDue = repository.loadUntaxedIncomeBefore(2L);
        assertEquals(16.0D, dayTwoDue.get(cityId), 0.001D);

        assertTrue(repository.markIncomeTaxCollectedBefore(cityId, 2L));
        assertTrue(repository.loadUntaxedIncomeBefore(2L).isEmpty());

        Map<UUID, Double> dayThreeDue = repository.loadUntaxedIncomeBefore(3L);
        assertEquals(4.0D, dayThreeDue.get(cityId), 0.001D);
    }

    /** openDatabase: 通过反射创建测试用 SQLite 数据库实例。 */
    private static SimuSqliteDatabase openDatabase(Path databasePath) throws Exception {
        Constructor<SimuSqliteDatabase> constructor = SimuSqliteDatabase.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(databasePath);
    }

    /** insertCity: 为外键约束准备测试城市。 */
    private static void insertCity(SimuSqliteDatabase database, UUID cityId) throws Exception {
        try (var connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO cities(city_id, city_name, core_x, core_y, core_z, funds, city_level) VALUES(?, ?, 0, 64, 0, 20.0, 0)")) {
            statement.setString(1, cityId.toString());
            statement.setString(2, "Test City");
            statement.executeUpdate();
        }
    }
}
