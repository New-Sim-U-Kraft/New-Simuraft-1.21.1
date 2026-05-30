package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("null")
public final class CitizenManager extends SavedData {
    private static final String DATA_NAME = SimuKraft.MOD_ID + "_citizens";
    private static final int AI_BUDGET_PER_TICK = 20;
    private static final int SAVE_DIRTY_INTERVAL_TICKS = 100;
    private static final Factory<CitizenManager> FACTORY = new Factory<>(CitizenManager::new, CitizenManager::load, null);

    // 居民主数据在服务端内存中维护，SQLite 负责持久化，实体只做世界内表现。
    private final ConcurrentMap<UUID, CitizenData> citizens = new ConcurrentHashMap<>();
    // 分帧处理居民状态，避免城市人口变大后单 tick 扫全量。
    private final ConcurrentLinkedQueue<UUID> aiQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger dirtyCounter = new AtomicInteger();
    private volatile boolean sqliteLoaded;
    private volatile ServerLevel level;

    public static CitizenManager get(ServerLevel level) {
        CitizenManager manager = level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        manager.level = level;
        manager.loadFromSqlite(level);
        return manager;
    }

    private static CitizenManager load(CompoundTag tag, HolderLookup.Provider registries) {
        return new CitizenManager();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return tag;
    }

    public synchronized void saveToSqlite(ServerLevel level) {
        if (level == null) {
            return;
        }
    }

    private synchronized void loadFromSqlite(ServerLevel level) {
        if (sqliteLoaded) {
            return;
        }
        sqliteLoaded = true;
        CompoundTag sqliteTag = SimuSqliteStorage.loadCitizens(level);
        if (sqliteTag == null || sqliteTag.isEmpty()) {
            return;
        }
        ListTag citizensTag = sqliteTag.getList("Citizens", CompoundTag.TAG_COMPOUND);
        if (citizensTag.isEmpty()) {
            return;
        }
        // SQLite 加载后重建 AI 队列，保证旧存档居民也会继续参与状态 tick。
        citizens.clear();
        aiQueue.clear();
        for (int i = 0; i < citizensTag.size(); i++) {
            CitizenData data = CitizenData.fromTag(citizensTag.getCompound(i));
            citizens.put(data.uuid(), data);
            if (!data.dead()) {
                aiQueue.offer(data.uuid());
            }
        }
    }

    void saveCitizenNow(UUID citizenId) {
        CitizenData data = citizenId != null ? citizens.get(citizenId) : null;
        if (data != null) {
            saveCitizenIncremental(data);
            setDirty();
        }
    }

    void markChanged() {
        setDirty();
    }

    public void syncEntity(CitizenEntity entity) {
        CitizenData data = entity != null ? citizens.get(entity.getUUID()) : null;
        if (data != null) {
            if (data.dead()) {
                entity.discard();
                return;
            }
            syncEntityFromData(entity, data);
        }
    }

    private void saveCitizenIncremental(CitizenData data) {
        ServerLevel targetLevel = level;
        if (targetLevel != null && data != null) {
            SimuSqliteStorage.saveCitizen(targetLevel, data.toTag());
        }
    }

    private void deleteCitizenIncremental(UUID uuid) {
        ServerLevel targetLevel = level;
        if (targetLevel != null && uuid != null) {
            SimuSqliteStorage.deleteCitizen(targetLevel, uuid);
        }
    }

    public CitizenData getOrCreate(CitizenEntity entity) {
        CitizenData data = citizens.get(entity.getUUID());
        if (data == null) {
            data = createDefaultFromEntity(entity);
            CitizenData existing = citizens.putIfAbsent(entity.getUUID(), data);
            if (existing != null) {
                data = existing;
            } else {
                aiQueue.offer(data.uuid());
                saveCitizenIncremental(data);
                markDirtySoon();
            }
        }
        if (data.dead()) {
            entity.discard();
            return data;
        }
        if (!aiQueue.contains(data.uuid())) {
            aiQueue.offer(data.uuid());
        }
        if (entity.level() instanceof ServerLevel level) {
            CitizenProfileGenerator.fillMissingProfile(data, level.random, level.getDayTime() / 24000L);
        }
        syncEntityFromData(entity, data);
        return data;
    }

    public Optional<CitizenData> getCitizen(UUID uuid) {
        return Optional.ofNullable(citizens.get(uuid));
    }

    public Collection<CitizenData> allCitizens() {
        return citizens.values();
    }

    public long getCityPopulation(UUID cityId) {
        if (cityId == null) {
            return 0L;
        }
        return citizens.values().stream()
                .filter(data -> !data.dead() && Objects.equals(cityId, data.cityId()))
                .count();
    }

    public int getWorldPopulation() {
        long count = citizens.values().stream()
                .filter(data -> !data.dead())
                .count();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, count));
    }

    public void markCitizenDead(UUID uuid, long deathDay) {
        CitizenData data = uuid != null ? citizens.get(uuid) : null;
        if (data == null) {
            return;
        }
        data.markDead(deathDay);
        aiQueue.remove(uuid);
        saveCitizenIncremental(data);
        setDirty();
    }

    public void removeCitizen(UUID uuid) {
        citizens.remove(uuid);
        aiQueue.remove(uuid);
        deleteCitizenIncremental(uuid);
        setDirty();
    }

    public void tick(ServerLevel level) {
        int processed = 0;
        // 轮询队列相当于时间片调度，每 tick 最多处理 AI_BUDGET_PER_TICK 个居民。
        while (processed < AI_BUDGET_PER_TICK) {
            UUID uuid = aiQueue.poll();
            if (uuid == null) {
                break;
            }
            CitizenData data = citizens.get(uuid);
            if (data != null && !data.dead()) {
                tickCitizenData(level, data);
                aiQueue.offer(uuid);
                processed++;
            }
        }
        if (dirtyCounter.get() >= SAVE_DIRTY_INTERVAL_TICKS) {
            dirtyCounter.set(0);
            setDirty();
        }
    }

    private CitizenData createDefaultFromEntity(CitizenEntity entity) {
        CitizenData data = new CitizenData(entity.getUUID());
        data.setName(entity.getCitizenName());
        data.setSkinPath(entity.getSkinPath());
        data.setStatusLabel(entity.getStatusLabel());
        data.setHunger(entity.getHunger());
        data.setAge(entity.getAge());
        data.setLifespan(entity.getLifespan());
        data.setSick(entity.isSick());
        data.setChild(entity.isChildNpc());
        if (entity.level() instanceof ServerLevel level) {
            data.setHappiness(45.0D + level.random.nextDouble() * 20.0D);
        }
        return data;
    }

    private void tickCitizenData(ServerLevel level, CitizenData data) {
        if (data.dead()) {
            return;
        }
        // 通过 UUID 错开居民状态更新时间，避免所有居民同一 tick 一起写库。
        if (level.getGameTime() % 200L == Math.floorMod(data.uuid().getLeastSignificantBits(), 200L)) {
            RandomSource random = level.random;
            data.setHunger(data.hunger() - 0.05D);
            boolean hasAssignedWork = data.workplaceId() != null && data.jobType() != null && data.jobType() != common.cn.kafei.simukraft.job.CityJobType.UNEMPLOYED;
            boolean isWorkingCitizen = data.workStatusType() == CitizenWorkStatus.WORKING;
            if (data.hunger() < 6.0D) {
                data.setStatus("hungry");
                data.setHappiness(data.happiness() - 0.1D);
            } else if (!hasAssignedWork && !isWorkingCitizen && random.nextInt(40) == 0) {
                data.setStatus("idle");
            }
            saveCitizenIncremental(data);
            markDirtySoon();
        }
    }

    private void syncEntityFromData(CitizenEntity entity, CitizenData data) {
        entity.setCitizenName(data.name());
        entity.setJob(data.jobId());
        entity.setStatus(data.status());
        entity.setSkinPath(data.skinPath());
        entity.setWorkStatus(data.workStatus());
        entity.setStatusLabel(data.statusLabel());
        entity.setHunger((int) Math.round(data.hunger()));
        if (data.age() >= 0) {
            entity.setAge(data.age());
        }
        if (data.lifespan() > 0) {
            entity.setLifespan(data.lifespan());
        }
        entity.setSick(data.sick());
        entity.setChildNpc(data.child());
        if (entity.level() instanceof ServerLevel level) {
            CitizenJobVisualService.sync(level, entity, data);
        }
    }

    private void markDirtySoon() {
        dirtyCounter.incrementAndGet();
    }
}
