package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CitizenEmploymentService;
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
    // CITIZEN_STATUS_UPDATE_INTERVAL_TICKS：居民状态轮询间隔，用 UUID 错峰执行。
    private static final long CITIZEN_STATUS_UPDATE_INTERVAL_TICKS = 200L;
    // HUNGER_DECAY_INTERVAL_TICKS：整数饥饿值自然下降间隔，约 6 分钟扣 1 点。
    private static final long HUNGER_DECAY_INTERVAL_TICKS = 7200L;
    // HUNGER_DECAY_PER_UPDATE：每次自然下降扣 1 点，保持原版 0-20 整数风格。
    private static final double HUNGER_DECAY_PER_UPDATE = 1.0D;
    private static final Factory<CitizenManager> FACTORY = new Factory<>(CitizenManager::new, CitizenManager::load, null);

    // 居民主数据在服务端内存中维护，SQLite 负责档案持久化，饱食度独立保存在实体 NBT。
    private final ConcurrentMap<UUID, CitizenData> citizens = new ConcurrentHashMap<>();
    // 分帧处理居民状态，避免城市人口变大后单 tick 扫全量。
    private final ConcurrentLinkedQueue<UUID> aiQueue = new ConcurrentLinkedQueue<>();
    private final java.util.Set<UUID> aiQueueSet = ConcurrentHashMap.newKeySet();
    private final AtomicInteger dirtyCounter = new AtomicInteger();
    private volatile boolean sqliteLoaded;
    private volatile ServerLevel level;

    public static CitizenManager get(ServerLevel level) {
        ServerLevel storageLevel = storageLevel(level);
        CitizenManager manager = storageLevel.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        manager.level = storageLevel;
        manager.loadFromSqlite(storageLevel);
        return manager;
    }

    // storageLevel：居民是整存档数据，统一挂主世界，避免不同维度的旧快照互相覆盖 SQLite。
    private static ServerLevel storageLevel(ServerLevel level) {
        if (level != null && level.getServer() != null) {
            return level.getServer().overworld();
        }
        return level;
    }

    private static CitizenManager load(CompoundTag tag, HolderLookup.Provider registries) {
        CitizenManager manager = new CitizenManager();
        ListTag citizensTag = tag.getList("Citizens", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < citizensTag.size(); i++) {
            CitizenData data = CitizenData.fromTag(citizensTag.getCompound(i));
            manager.putLoadedCitizen(data);
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag citizensTag = new ListTag();
        citizens.values().forEach(data -> citizensTag.add(data.toTag()));
        tag.put("Citizens", citizensTag);
        return tag;
    }

    public synchronized void saveToSqlite(ServerLevel level) {
        if (level == null) {
            return;
        }
        if (this.level != null && this.level != level) {
            return;
        }
        if (!citizens.isEmpty()) {
            SimuSqliteStorage.saveCitizens(level, save(new CompoundTag(), level.registryAccess()));
        }
    }

    public synchronized void reloadFromSqlite(ServerLevel level) {
        citizens.clear();
        aiQueue.clear();
        aiQueueSet.clear();
        sqliteLoaded = false;
        loadFromSqlite(storageLevel(level));
    }

    private synchronized void loadFromSqlite(ServerLevel level) {
        if (sqliteLoaded) {
            return;
        }
        CompoundTag sqliteTag = SimuSqliteStorage.loadCitizens(level);
        if (sqliteTag == null) {
            SimuKraft.LOGGER.warn("Simukraft: Citizen SQLite data was not loaded; delaying entity-to-citizen fallback to avoid overwriting jobs.");
            return;
        }
        sqliteLoaded = true;
        if (sqliteTag.isEmpty()) {
            return;
        }
        ListTag citizensTag = sqliteTag.getList("Citizens", CompoundTag.TAG_COMPOUND);
        if (citizensTag.isEmpty()) {
            return;
        }
        // SQLite 加载后重建 AI 队列，保证旧存档居民也会继续参与状态 tick。
        citizens.clear();
        aiQueue.clear();
        aiQueueSet.clear();
        for (int i = 0; i < citizensTag.size(); i++) {
            CompoundTag citizenTag = citizensTag.getCompound(i);
            boolean repairedDeadHousing = deadCitizenHasHome(citizenTag);
            CitizenData data = CitizenData.fromTag(citizenTag);
            boolean repaired = CitizenEmploymentService.repairLoadedEmployment(level, data);
            putLoadedCitizen(data);
            if (repaired || repairedDeadHousing) {
                if (repaired) {
                    SimuKraft.LOGGER.info("Simukraft: Repaired citizen {} employment during load", data.uuid());
                }
                if (repairedDeadHousing) {
                    SimuKraft.LOGGER.info("Simukraft: Cleared dead citizen {} home during load", data.uuid());
                }
                saveCitizenIncremental(data);
            }
        }
    }

    private static boolean deadCitizenHasHome(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("HomeId")) {
            return false;
        }
        return tag.getBoolean("Dead")
                || CitizenWorkStatus.fromName(tag.getString("WorkStatus")) == CitizenWorkStatus.DEAD
                || CitizenWorkStatus.fromName(tag.getString("Status")) == CitizenWorkStatus.DEAD
                || CitizenWorkStatus.fromName(tag.getString("JobId")) == CitizenWorkStatus.DEAD;
    }

    // putLoadedCitizen：加载 SQLite/SavedData 时统一恢复居民索引和 AI 队列。
    private void putLoadedCitizen(CitizenData data) {
        if (data == null) {
            return;
        }
        citizens.put(data.uuid(), data);
        if (!data.dead()) {
            aiQueue.offer(data.uuid());
            aiQueueSet.add(data.uuid());
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
            if (!sqliteLoaded) {
                if (entity.level() instanceof ServerLevel serverLevel) {
                    loadFromSqlite(serverLevel);
                    data = citizens.get(entity.getUUID());
                }
                if (data == null && !sqliteLoaded) {
                    return null;
                }
            }
        }
        if (data == null) {
            data = createDefaultFromEntity(entity);
            CitizenData existing = citizens.putIfAbsent(entity.getUUID(), data);
            if (existing != null) {
                data = existing;
            } else {
                aiQueue.offer(data.uuid());
                aiQueueSet.add(data.uuid());
                saveCitizenIncremental(data);
                markDirtySoon();
            }
        }
        if (data.dead()) {
            entity.discard();
            return data;
        }
        if (!aiQueueSet.contains(data.uuid())) {
            aiQueue.offer(data.uuid());
            aiQueueSet.add(data.uuid());
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
        aiQueueSet.remove(uuid);
        saveCitizenIncremental(data);
        setDirty();
    }

    public void removeCitizen(UUID uuid) {
        citizens.remove(uuid);
        aiQueue.remove(uuid);
        aiQueueSet.remove(uuid);
        deleteCitizenIncremental(uuid);
        setDirty();
    }

    public void releaseCity(UUID cityId, ServerLevel level) {
        citizens.values().stream()
                .filter(d -> Objects.equals(cityId, d.cityId()))
                .map(CitizenData::uuid)
                .toList()
                .forEach(uuid -> {
                    CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, uuid);
                    if (entity != null) entity.discard();
                    removeCitizen(uuid);
                });
    }

    public void tick(ServerLevel level) {
        if (level == null || this.level != null && this.level != level) {
            return;
        }
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
            } else {
                aiQueueSet.remove(uuid);
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
        data.setAge(entity.getAge());
        data.setLifespan(entity.getLifespan());
        data.setSick(entity.isSick());
        data.setChild(entity.isChildNpc());
        data.setDimensionId(entity.level().dimension().location().toString());
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
        long gameTime = level.getGameTime();
        long uuidBits = data.uuid().getLeastSignificantBits();
        if (gameTime % CITIZEN_STATUS_UPDATE_INTERVAL_TICKS == Math.floorMod(uuidBits, CITIZEN_STATUS_UPDATE_INTERVAL_TICKS)) {
            RandomSource random = level.random;
            CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, data.uuid());
            boolean dataChanged = false;
            boolean shouldDecayHunger = gameTime % HUNGER_DECAY_INTERVAL_TICKS == Math.floorMod(uuidBits, HUNGER_DECAY_INTERVAL_TICKS);
            if (entity == null) {
                return;
            }
            if (shouldDecayHunger) {
                entity.setHunger(entity.getHungerValue() - HUNGER_DECAY_PER_UPDATE);
            }
            double hunger = entity.getHungerValue();
            boolean hasAssignedWork = data.workplaceId() != null && data.jobType() != null && data.jobType() != common.cn.kafei.simukraft.job.CityJobType.UNEMPLOYED;
            boolean isWorkingCitizen = data.workStatusType() == CitizenWorkStatus.WORKING;
            if (hunger < 6.0D) {
                data.setStatus("hungry");
                data.setHappiness(data.happiness() - 0.1D);
                dataChanged = true;
            } else if (!hasAssignedWork && !isWorkingCitizen && random.nextInt(40) == 0) {
                data.setStatus("idle");
                dataChanged = true;
            }
            if (dataChanged) {
                saveCitizenIncremental(data);
                markDirtySoon();
            }
        }
    }

    private void syncEntityFromData(CitizenEntity entity, CitizenData data) {
        entity.setCitizenName(data.name());
        entity.setJob(data.jobId());
        entity.setStatus(data.status());
        entity.setSkinPath(data.skinPath());
        entity.setWorkStatus(data.workStatus());
        if (entity.level() instanceof ServerLevel level) {
            entity.setStatusLabel(CitizenSelfFeedingService.effectiveStatusLabel(level, data.uuid(), data.statusLabel()));
        } else {
            entity.setStatusLabel(data.statusLabel());
        }
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
