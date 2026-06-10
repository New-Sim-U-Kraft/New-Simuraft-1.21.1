package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.job.CityJobType;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

public final class CitizenLevelService {
    private static final int[] LEVEL_THRESHOLDS = {
            50, 150, 350, 650, 1150, 1850, 2850, 4050, 5550,
            7350, 9450, 11850, 14550, 17550, 20850, 24450, 28350, 32550, 37050
    };
    private static final String LEVEL_SUFFIX = ".level";
    private static final String XP_SUFFIX = ".xp";
    private static final String GLOBAL_SKILL_KEY = "global";
    private static final String RAW_LEVEL_KEY = "level";
    private static final String RAW_XP_KEY = "xp";
    private static final String[] LEGACY_ROLE_SKILL_KEYS = {
            "resident",
            "builder",
            "planner",
            "commercial",
            "commercial_worker",
            "industrial",
            "industrial_worker",
            "farmer",
            "logistics",
            "logistics_worker",
            "storage",
            "storage_worker",
            "guard",
            "gatherer",
            "other"
    };
    private static final LevelScope ACTIVE_LEVEL_SCOPE = LevelScope.GLOBAL;

    private CitizenLevelService() {
    }

    public enum LevelScope {
        GLOBAL,
        PROFESSION
    }

    public static CitizenSkillSnapshot snapshot(CitizenData data, CityJobType skillType) {
        return snapshot(data, skillType, configuredMaxLevel());
    }

    public static CitizenSkillSnapshot snapshot(CitizenData data, CityJobType skillType, int maxLevel) {
        return snapshot(data, skillType, maxLevel, ACTIVE_LEVEL_SCOPE);
    }

    public static CitizenSkillSnapshot snapshot(CitizenData data, CityJobType skillType, int maxLevel, LevelScope scope) {
        CityJobType normalizedType = normalizeSkillType(skillType);
        int normalizedMaxLevel = normalizeMaxLevel(maxLevel);
        if (data == null) {
            return new CitizenSkillSnapshot(normalizedType, 1, 0, normalizedMaxLevel);
        }
        int xp = Math.clamp(readSkillXp(data, normalizedType, normalizedMaxLevel, scope), 0, maxStoredXp(normalizedMaxLevel));
        int level = levelForXp(xp, normalizedMaxLevel);
        return new CitizenSkillSnapshot(normalizedType, level, xp, normalizedMaxLevel);
    }

    public static LevelUpdateResult addExperience(ServerLevel level, UUID citizenId, CityJobType skillType, int amount) {
        CityJobType normalizedType = normalizeSkillType(skillType);
        if (level == null || citizenId == null || amount <= 0) {
            return LevelUpdateResult.unchanged(new CitizenSkillSnapshot(normalizedType, 1, 0, configuredMaxLevel()));
        }
        CitizenManager manager = CitizenManager.get(level);
        Optional<CitizenData> optionalCitizen = manager.getCitizen(citizenId);
        if (optionalCitizen.isEmpty()) {
            return LevelUpdateResult.unchanged(new CitizenSkillSnapshot(normalizedType, 1, 0, configuredMaxLevel()));
        }
        CitizenData data = optionalCitizen.get();
        LevelUpdateResult result;
        synchronized (data) {
            CitizenSkillSnapshot before = snapshot(data, normalizedType);
            int cappedXp = (int) Math.min((long) before.xp() + amount, maxStoredXp(before.maxLevel()));
            int nextLevel = levelForXp(cappedXp, before.maxLevel());
            CitizenSkillSnapshot after = new CitizenSkillSnapshot(normalizedType, nextLevel, cappedXp, before.maxLevel());
            if (before.level() == after.level() && before.xp() == after.xp()) {
                return LevelUpdateResult.unchanged(before);
            }
            writeSkillSnapshot(data, normalizedType, LevelScope.GLOBAL, after.xp(), after.maxLevel());
            writeProfessionExperience(data, normalizedType, amount, after.maxLevel());
            result = new LevelUpdateResult(before, after);
        }
        manager.saveCitizenNow(citizenId);
        return result;
    }

    public static int xpForCurrentLevel(int level) {
        if (level <= 1) {
            return 0;
        }
        int index = Math.min(level - 2, LEVEL_THRESHOLDS.length - 1);
        return LEVEL_THRESHOLDS[index];
    }

    public static int xpForNextLevel(int level, int maxLevel) {
        int normalizedMaxLevel = normalizeMaxLevel(maxLevel);
        if (level >= normalizedMaxLevel) {
            return -1;
        }
        int index = level - 1;
        if (index < 0 || index >= LEVEL_THRESHOLDS.length) {
            return -1;
        }
        return LEVEL_THRESHOLDS[index];
    }

    public static int xpInCurrentLevel(CitizenSkillSnapshot snapshot) {
        if (snapshot == null || snapshot.maxLevelReached()) {
            return 0;
        }
        return Math.max(0, snapshot.xp() - xpForCurrentLevel(snapshot.level()));
    }

    public static int xpNeededForCurrentLevel(CitizenSkillSnapshot snapshot) {
        if (snapshot == null || snapshot.maxLevelReached()) {
            return 0;
        }
        int nextLevelXp = xpForNextLevel(snapshot.level(), snapshot.maxLevel());
        if (nextLevelXp < 0) {
            return 0;
        }
        return Math.max(0, nextLevelXp - xpForCurrentLevel(snapshot.level()));
    }

    public static float progress(CitizenSkillSnapshot snapshot) {
        if (snapshot == null || snapshot.maxLevelReached()) {
            return 1.0F;
        }
        int needed = xpNeededForCurrentLevel(snapshot);
        if (needed <= 0) {
            return 1.0F;
        }
        return Math.clamp(xpInCurrentLevel(snapshot) / (float) needed, 0.0F, 1.0F);
    }

    public static String skillKey(CityJobType skillType) {
        return skillKey(skillType, ACTIVE_LEVEL_SCOPE);
    }

    public static String skillKey(CityJobType skillType, LevelScope scope) {
        return normalizeScope(scope) == LevelScope.PROFESSION ? professionSkillKey(skillType) : GLOBAL_SKILL_KEY;
    }

    private static int levelForXp(int xp, int maxLevel) {
        int normalizedMaxLevel = normalizeMaxLevel(maxLevel);
        int normalizedXp = Math.clamp(xp, 0, maxStoredXp(normalizedMaxLevel));
        int level = 1;
        while (level < normalizedMaxLevel
                && level - 1 < LEVEL_THRESHOLDS.length
                && normalizedXp >= LEVEL_THRESHOLDS[level - 1]) {
            level++;
        }
        return level;
    }

    private static int configuredMaxLevel() {
        return normalizeMaxLevel(ServerConfig.npcMaxLevel());
    }

    private static int normalizeMaxLevel(int maxLevel) {
        return Math.clamp(maxLevel, 1, LEVEL_THRESHOLDS.length + 1);
    }

    private static int maxStoredXp(int maxLevel) {
        int normalizedMaxLevel = normalizeMaxLevel(maxLevel);
        if (normalizedMaxLevel <= 1) {
            return 0;
        }
        return LEVEL_THRESHOLDS[normalizedMaxLevel - 2];
    }

    private static CityJobType normalizeSkillType(CityJobType skillType) {
        if (skillType == null || skillType == CityJobType.UNEMPLOYED) {
            return CityJobType.RESIDENT;
        }
        return skillType;
    }

    private static int readSkillValue(CitizenData data, String key, int fallback) {
        Integer value = data.skills().get(key);
        return value != null ? value : fallback;
    }

    private static void writeSkillValue(CitizenData data, String key, int value) {
        ConcurrentMap<String, Integer> skills = data.skills();
        skills.put(key, Math.max(0, value));
    }

    // readSkillXp: current UI uses global NPC levels, but profession scope remains available.
    private static int readSkillXp(CitizenData data, CityJobType skillType, int maxLevel, LevelScope scope) {
        if (normalizeScope(scope) == LevelScope.PROFESSION) {
            return readProfessionSkillXp(data, skillType, maxLevel);
        }
        return readGlobalSkillXp(data, maxLevel);
    }

    // readGlobalSkillXp: accepts old global data and earlier split profession saves.
    private static int readGlobalSkillXp(CitizenData data, int maxLevel) {
        int xp = Math.max(readSkillValue(data, xpKey(LevelScope.GLOBAL, null), -1), readSkillValue(data, RAW_XP_KEY, -1));
        for (String key : LEGACY_ROLE_SKILL_KEYS) {
            xp = Math.max(xp, readSkillValue(data, key + XP_SUFFIX, -1));
        }
        if (xp >= 0) {
            return xp;
        }
        return xpForCurrentLevel(readGlobalSkillLevel(data, maxLevel));
    }

    // readGlobalSkillLevel: fallback for old data that has level but no xp.
    private static int readGlobalSkillLevel(CitizenData data, int maxLevel) {
        int level = Math.max(readSkillValue(data, levelKey(LevelScope.GLOBAL, null), -1), readSkillValue(data, RAW_LEVEL_KEY, -1));
        for (String key : LEGACY_ROLE_SKILL_KEYS) {
            level = Math.max(level, readSkillValue(data, key + LEVEL_SUFFIX, -1));
        }
        return Math.clamp(level, 1, normalizeMaxLevel(maxLevel));
    }

    // readProfessionSkillXp: direct profession slot for future profession-specific leveling.
    private static int readProfessionSkillXp(CitizenData data, CityJobType skillType, int maxLevel) {
        int directXp = readDirectProfessionSkillXp(data, skillType, maxLevel);
        return hasProfessionSkillValue(data, skillType) ? directXp : readGlobalSkillXp(data, maxLevel);
    }

    private static int readDirectProfessionSkillXp(CitizenData data, CityJobType skillType, int maxLevel) {
        int xp = readProfessionValue(data, skillType, XP_SUFFIX);
        if (xp >= 0) {
            return xp;
        }
        int level = readProfessionValue(data, skillType, LEVEL_SUFFIX);
        if (level >= 0) {
            return xpForCurrentLevel(Math.clamp(level, 1, normalizeMaxLevel(maxLevel)));
        }
        return 0;
    }

    private static int readProfessionValue(CitizenData data, CityJobType skillType, String suffix) {
        int value = readSkillValue(data, professionSkillKey(skillType) + suffix, -1);
        for (String alias : professionAliases(skillType)) {
            value = Math.max(value, readSkillValue(data, alias + suffix, -1));
        }
        return value;
    }

    private static boolean hasProfessionSkillValue(CitizenData data, CityJobType skillType) {
        return readProfessionValue(data, skillType, XP_SUFFIX) >= 0 || readProfessionValue(data, skillType, LEVEL_SUFFIX) >= 0;
    }

    private static void writeSkillSnapshot(CitizenData data, CityJobType skillType, LevelScope scope, int xp, int maxLevel) {
        int cappedXp = Math.clamp(xp, 0, maxStoredXp(maxLevel));
        writeSkillValue(data, levelKey(scope, skillType), levelForXp(cappedXp, maxLevel));
        writeSkillValue(data, xpKey(scope, skillType), cappedXp);
    }

    private static void writeProfessionExperience(CitizenData data, CityJobType skillType, int amount, int maxLevel) {
        int beforeXp = Math.clamp(readDirectProfessionSkillXp(data, skillType, maxLevel), 0, maxStoredXp(maxLevel));
        int afterXp = (int) Math.min((long) beforeXp + amount, maxStoredXp(maxLevel));
        writeSkillSnapshot(data, skillType, LevelScope.PROFESSION, afterXp, maxLevel);
    }

    private static String levelKey(LevelScope scope, CityJobType skillType) {
        return skillKey(skillType, scope) + LEVEL_SUFFIX;
    }

    private static String xpKey(LevelScope scope, CityJobType skillType) {
        return skillKey(skillType, scope) + XP_SUFFIX;
    }

    private static String professionSkillKey(CityJobType skillType) {
        return normalizeSkillType(skillType).name().toLowerCase(Locale.ROOT);
    }

    private static String[] professionAliases(CityJobType skillType) {
        return switch (normalizeSkillType(skillType)) {
            case COMMERCIAL_WORKER -> new String[]{"commercial"};
            case INDUSTRIAL_WORKER -> new String[]{"industrial"};
            case LOGISTICS_WORKER -> new String[]{"logistics"};
            case STORAGE_WORKER -> new String[]{"storage"};
            default -> new String[0];
        };
    }

    private static LevelScope normalizeScope(LevelScope scope) {
        return scope != null ? scope : ACTIVE_LEVEL_SCOPE;
    }

    public record LevelUpdateResult(CitizenSkillSnapshot before, CitizenSkillSnapshot after) {
        private static LevelUpdateResult unchanged(CitizenSkillSnapshot snapshot) {
            return new LevelUpdateResult(snapshot, snapshot);
        }

        public boolean changed() {
            return before.level() != after.level() || before.xp() != after.xp();
        }

        public boolean leveledUp() {
            return after.level() > before.level();
        }
    }
}
