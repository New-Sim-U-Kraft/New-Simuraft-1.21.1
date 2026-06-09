package common.cn.kafei.simukraft.storage;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("null")
public final class SimuSqliteDatabase {
    private static final String STORAGE_DIR = SimuKraft.MOD_ID;
    private static final String DATABASE_FILE = SimuKraft.MOD_ID + ".sqlite";
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final AtomicBoolean DRIVER_LOADED = new AtomicBoolean();

    private final Path databasePath;
    private final String jdbcUrl;

    private SimuSqliteDatabase(Path databasePath) {
        loadDriver();
        this.databasePath = databasePath;
        this.jdbcUrl = JDBC_PREFIX + databasePath.toAbsolutePath().normalize();
        SimuSqliteSchema.initialize(this);
    }

    public static SimuSqliteDatabase open(MinecraftServer server) {
        return new SimuSqliteDatabase(databasePath(server));
    }

    public static Path databasePath(MinecraftServer server) {
        Path worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return worldPath.resolve(STORAGE_DIR).resolve(DATABASE_FILE);
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            // WAL 降低读写互相阻塞；busy_timeout 避免短时间锁表直接失败。
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    public Path databasePath() {
        return databasePath;
    }

    private static void loadDriver() {
        if (DRIVER_LOADED.get()) {
            return;
        }
        synchronized (DRIVER_LOADED) {
            // JDBC 驱动只需加载一次，AtomicBoolean 防止多线程重复 Class.forName。
            if (DRIVER_LOADED.get()) {
                return;
            }
            try {
                Class.forName("org.sqlite.JDBC");
                DRIVER_LOADED.set(true);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("SQLite JDBC driver is not available. Check sqlite-jdbc runtime dependency.", exception);
            }
        }
    }
}
