package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.job.CityJobType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CitizenData {
    private final UUID uuid;
    private String name;
    private String gender;
    private int age;
    private int lifespan;
    private CityJobType jobType;
    private String jobId;
    private String status;
    private CitizenWorkStatus workStatus;
    private String skinPath;
    private UUID cityId;
    private UUID homeId;
    private UUID workplaceId;
    private BlockPos workplacePos;
    private double health;
    private double hunger;
    private double happiness;
    private boolean sick;
    private boolean child;
    private boolean working;
    private boolean dead;
    private String workNeedDetail;
    private String statusLabel;
    private int npcId;
    private long childGrowthDueDay;
    private long bornDay;
    private long deathDay;
    private final ConcurrentMap<String, Integer> skills = new ConcurrentHashMap<>();

    public CitizenData(UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = "";
        this.gender = "unknown";
        this.age = 18;
        this.lifespan = 80;
        this.jobType = CityJobType.UNEMPLOYED;
        this.jobId = CityJobType.UNEMPLOYED.name();
        this.status = "idle";
        this.workStatus = CitizenWorkStatus.IDLE;
        this.workNeedDetail = "";
        this.statusLabel = "";
        this.npcId = -1;
        this.skinPath = "";
        this.health = 20.0D;
        this.hunger = 20.0D;
        this.happiness = 50.0D;
        this.dead = false;
        this.deathDay = 0L;
    }

    public static CitizenData fromTag(CompoundTag tag) {
        CitizenData data = new CitizenData(tag.getUUID("Uuid"));
        data.name = tag.getString("Name");
        data.gender = tag.getString("Gender");
        data.age = tag.getInt("Age");
        data.lifespan = tag.getInt("Lifespan");
        data.jobType = tag.contains("JobType") ? CityJobType.fromName(tag.getString("JobType")) : CityJobType.fromName(tag.getString("JobId"));
        data.jobId = tag.contains("JobId") ? tag.getString("JobId") : data.jobType.name();
        data.status = tag.getString("Status");
        data.workStatus = tag.contains("WorkStatus") ? CitizenWorkStatus.fromName(tag.getString("WorkStatus")) : CitizenWorkStatus.fromName(data.status);
        if (data.workStatus == CitizenWorkStatus.IDLE && tag.contains("WorkSubState")) {
            data.workStatus = CitizenWorkStatus.fromName(tag.getString("WorkSubState"));
        }
        data.workNeedDetail = tag.getString("WorkNeedDetail");
        data.statusLabel = tag.getString("StatusLabel");
        data.working = tag.getBoolean("IsWorking");
        data.npcId = tag.contains("NpcId") ? tag.getInt("NpcId") : -1;
        data.skinPath = tag.getString("SkinPath");
        data.cityId = tag.hasUUID("CityId") ? tag.getUUID("CityId") : null;
        data.homeId = tag.hasUUID("HomeId") ? tag.getUUID("HomeId") : null;
        data.workplaceId = tag.hasUUID("WorkplaceId") ? tag.getUUID("WorkplaceId") : null;
        data.workplacePos = tag.contains("WorkplacePos") ? BlockPos.of(tag.getLong("WorkplacePos")) : null;
        data.health = tag.getDouble("Health");
        data.hunger = tag.getDouble("Hunger");
        data.happiness = tag.getDouble("Happiness");
        data.sick = tag.getBoolean("Sick");
        data.child = tag.getBoolean("Child");
        data.childGrowthDueDay = tag.getLong("ChildGrowthDueDay");
        data.bornDay = tag.getLong("BornDay");
        data.dead = tag.getBoolean("Dead");
        data.deathDay = tag.getLong("DeathDay");
        CompoundTag skillTag = tag.getCompound("Skills");
        for (String key : skillTag.getAllKeys()) {
            data.skills.put(key, skillTag.getInt(key));
        }
        data.normalizeDefaults();
        return data;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Uuid", uuid);
        tag.putString("Name", name);
        tag.putString("Gender", gender);
        tag.putInt("Age", age);
        tag.putInt("Lifespan", lifespan);
        tag.putString("JobType", jobType.name());
        tag.putString("JobId", jobId);
        tag.putString("Status", status);
        tag.putString("WorkStatus", workStatus.translationKey());
        tag.putString("WorkNeedDetail", workNeedDetail);
        tag.putString("StatusLabel", statusLabel);
        tag.putBoolean("IsWorking", working);
        tag.putInt("NpcId", npcId);
        tag.putString("SkinPath", skinPath);
        if (cityId != null) {
            tag.putUUID("CityId", cityId);
        }
        if (homeId != null) {
            tag.putUUID("HomeId", homeId);
        }
        if (workplaceId != null) {
            tag.putUUID("WorkplaceId", workplaceId);
        }
        if (workplacePos != null) {
            tag.putLong("WorkplacePos", workplacePos.asLong());
        }
        tag.putDouble("Health", health);
        tag.putDouble("Hunger", hunger);
        tag.putDouble("Happiness", happiness);
        tag.putBoolean("Sick", sick);
        tag.putBoolean("Child", child);
        tag.putLong("ChildGrowthDueDay", childGrowthDueDay);
        tag.putLong("BornDay", bornDay);
        tag.putBoolean("Dead", dead);
        tag.putLong("DeathDay", deathDay);
        CompoundTag skillTag = new CompoundTag();
        skills.forEach(skillTag::putInt);
        tag.put("Skills", skillTag);
        return tag;
    }

    private void normalizeDefaults() {
        if (gender == null || gender.isBlank()) {
            gender = "male";
        }
        if (!"female".equalsIgnoreCase(gender)) {
            gender = "male";
        } else {
            gender = "female";
        }
        if (jobType == null) {
            jobType = CityJobType.fromName(jobId);
        }
        if (jobId == null || jobId.isBlank()) {
            jobId = jobType.name();
        }
        if (status == null || status.isBlank()) {
            status = "idle";
        }
        if (workStatus == null) {
            workStatus = CitizenWorkStatus.fromName(status);
        }
        working = workStatus == CitizenWorkStatus.WORKING;
        if (workNeedDetail == null) {
            workNeedDetail = "";
        }
        if (statusLabel == null) {
            statusLabel = "";
        }
        if (skinPath == null) {
            skinPath = "";
        }
        if (workplaceId == null) {
            workplacePos = null;
        }
        if (lifespan <= 0) {
            lifespan = 80;
        }
        if (health <= 0.0D) {
            health = 20.0D;
        }
        if (hunger <= 0.0D) {
            hunger = 20.0D;
        }
        if (dead) {
            health = 0.0D;
            deathDay = Math.max(1L, deathDay);
            workStatus = CitizenWorkStatus.DEAD;
            status = workStatus.legacyStatus();
            working = false;
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public String gender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = "female".equalsIgnoreCase(gender) ? "female" : "male";
    }

    public int age() {
        return age;
    }

    public void setAge(int age) {
        this.age = Math.max(0, age);
    }

    public int lifespan() {
        return lifespan;
    }

    public void setLifespan(int lifespan) {
        this.lifespan = Math.max(1, lifespan);
    }

    public long bornDay() {
        return bornDay;
    }

    public void setBornDay(long bornDay) {
        this.bornDay = Math.max(0L, bornDay);
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String jobId() {
        return jobId;
    }

    public CityJobType jobType() {
        return jobType;
    }

    public void setJobType(CityJobType jobType) {
        this.jobType = jobType != null ? jobType : CityJobType.UNEMPLOYED;
        this.jobId = this.jobType.name();
    }

    public void setJobId(String jobId) {
        setJobType(CityJobType.fromName(jobId));
    }

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        if (dead) {
            this.status = CitizenWorkStatus.DEAD.legacyStatus();
            return;
        }
        this.status = (status == null || status.isBlank()) ? "idle" : status;
    }

    public String workStatus() {
        return workStatus.translationKey();
    }

    public CitizenWorkStatus workStatusType() {
        return workStatus;
    }

    public void setWorkStatus(CitizenWorkStatus workStatus) {
        if (dead) {
            this.workStatus = CitizenWorkStatus.DEAD;
            this.status = CitizenWorkStatus.DEAD.legacyStatus();
            this.working = false;
            return;
        }
        this.workStatus = workStatus != null ? workStatus : CitizenWorkStatus.IDLE;
        this.status = this.workStatus.legacyStatus();
        this.working = this.workStatus == CitizenWorkStatus.WORKING;
    }

    public void setWorkStatus(String workStatus) {
        setWorkStatus(CitizenWorkStatus.fromName(workStatus));
    }

    public String workNeedDetail() {
        return workNeedDetail;
    }

    public void setWorkNeedDetail(String workNeedDetail) {
        this.workNeedDetail = workNeedDetail != null ? workNeedDetail : "";
    }

    public String statusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel != null ? statusLabel : "";
    }

    public boolean working() {
        return working;
    }

    public int npcId() {
        return npcId;
    }

    public void setNpcId(int npcId) {
        this.npcId = npcId;
    }

    public String skinPath() {
        return skinPath;
    }

    public void setSkinPath(String skinPath) {
        this.skinPath = skinPath != null ? skinPath : "";
    }

    public UUID cityId() {
        return cityId;
    }

    public void setCityId(UUID cityId) {
        this.cityId = cityId;
    }

    public UUID homeId() {
        return homeId;
    }

    public void setHomeId(UUID homeId) {
        this.homeId = homeId;
    }

    public UUID workplaceId() {
        return workplaceId;
    }

    public void setWorkplaceId(UUID workplaceId) {
        this.workplaceId = workplaceId;
    }

    public BlockPos workplacePos() {
        return workplacePos;
    }

    public void setWorkplacePos(BlockPos workplacePos) {
        this.workplacePos = workplacePos != null ? workplacePos.immutable() : null;
    }

    public double hunger() {
        return hunger;
    }

    public double health() {
        return health;
    }

    public void setHealth(double health) {
        if (dead) {
            this.health = 0.0D;
            return;
        }
        this.health = Math.clamp(health, 0.0D, 20.0D);
    }

    public boolean sick() {
        return sick;
    }

    public void setSick(boolean sick) {
        this.sick = sick;
    }

    public boolean child() {
        return child;
    }

    public void setChild(boolean child) {
        this.child = child;
    }

    public boolean dead() {
        return dead;
    }

    public long deathDay() {
        return deathDay;
    }

    // markDead：保留市民档案，但让其退出人口、岗位和 AI 调度。
    public void markDead(long deathDay) {
        this.dead = true;
        this.deathDay = Math.max(1L, deathDay);
        this.health = 0.0D;
        this.hunger = 0.0D;
        this.workStatus = CitizenWorkStatus.DEAD;
        this.status = CitizenWorkStatus.DEAD.legacyStatus();
        this.working = false;
        this.workNeedDetail = "";
        this.statusLabel = "";
    }

    public long childGrowthDueDay() {
        return childGrowthDueDay;
    }

    public void setChildGrowthDueDay(long childGrowthDueDay) {
        this.childGrowthDueDay = Math.max(0L, childGrowthDueDay);
    }

    public void setHunger(double hunger) {
        if (dead) {
            this.hunger = 0.0D;
            return;
        }
        this.hunger = Math.clamp(hunger, 0.0D, 20.0D);
    }

    public double happiness() {
        return happiness;
    }

    public void setHappiness(double happiness) {
        this.happiness = Math.clamp(happiness, 0.0D, 100.0D);
    }

    public ConcurrentMap<String, Integer> skills() {
        return skills;
    }
}
