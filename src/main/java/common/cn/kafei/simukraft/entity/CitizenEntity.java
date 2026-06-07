package common.cn.kafei.simukraft.entity;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenDroppedFoodService;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.commercial.CommercialControlBoxService;
import common.cn.kafei.simukraft.network.citizen.info.CitizenInfoResponsePacket;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

@SuppressWarnings("null")
public class CitizenEntity extends PathfinderMob {
    // SynchedEntityData 会自动同步到客户端，用于渲染名字、职业、状态和皮肤。
    private static final EntityDataAccessor<String> DATA_CITIZEN_NAME = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_JOB = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_STATUS = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN_PATH = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WORK_STATUS = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_STATUS_LABEL = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_HUNGER = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_AGE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LIFESPAN = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_IS_SICK = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_IS_CHILD = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_HAS_ACTIVE_TASK = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_WORK_SWING_PULSE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final String TAG_HUNGER = "Hunger";
    private static final int WORK_SWING_DURATION_TICKS = 6;
    private static final int WALL_RESCUE_INTERVAL_TICKS = 20;
    private static final double WALL_RESCUE_COLLISION_DEFLATE = 0.0625D;
    private double hunger = 20.0D;
    private boolean hungerInitialized;
    private int lastWorkSwingPulse = -1;
    private int workSwingStartTick = -WORK_SWING_DURATION_TICKS;

    public CitizenEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level() instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer && player.distanceToSqr(this) <= 64.0D) {
            CitizenData data = CitizenService.ensureCitizen(serverLevel, this);
            if (data != null) {
                if (CommercialControlBoxService.openForWorker(serverLevel, serverPlayer, data)) {
                    return InteractionResult.sidedSuccess(level().isClientSide());
                }
                PacketDistributor.sendToPlayer(serverPlayer, CitizenInfoResponsePacket.from(serverLevel, this, data));
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isSuffocationDamage(source)) {
            rescueFromWall(true);
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CITIZEN_NAME, "");
        builder.define(DATA_JOB, "unemployed");
        builder.define(DATA_STATUS, "idle");
        builder.define(DATA_SKIN_PATH, "");
        builder.define(DATA_WORK_STATUS, CitizenWorkStatus.IDLE.translationKey());
        builder.define(DATA_STATUS_LABEL, "");
        builder.define(DATA_HUNGER, 20);
        builder.define(DATA_AGE, -1);
        builder.define(DATA_LIFESPAN, -1);
        builder.define(DATA_IS_SICK, false);
        builder.define(DATA_IS_CHILD, false);
        builder.define(DATA_HAS_ACTIVE_TASK, false);
        builder.define(DATA_WORK_SWING_PULSE, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        syncClientWorkSwingPulse();
        if (!canSyncCitizenData()) {
            return;
        }
        if (level() instanceof ServerLevel serverLevel) {
            // 同 UUID 重复实体只保留一个，防止未加载区块中的旧实体回来后复制居民。
            if (CitizenTeleportService.reconcileLoadedCitizenEntities(serverLevel, getUUID(), null) != this) {
                return;
            }
            rescueFromWall(false);
            // 实体每 tick 确保自己有 CitizenData，数据缺失时会自动补全。
            CitizenData data = CitizenService.ensureCitizen(serverLevel, this);
            CitizenDroppedFoodService.tryEatNearbyFood(serverLevel, this, data);
        }
    }

    // isSuffocationDamage：只屏蔽墙内窒息伤害，保留其它真实伤害来源。
    private boolean isSuffocationDamage(DamageSource source) {
        return source != null && source.is(DamageTypes.IN_WALL);
    }

    // rescueFromWall：检测到方块碰撞重叠时停止旧导航，并把 NPC 移到附近安全落点。
    private void rescueFromWall(boolean immediate) {
        if (!(level() instanceof ServerLevel serverLevel) || !canSyncCitizenData()) {
            return;
        }
        if (!immediate && tickCount % WALL_RESCUE_INTERVAL_TICKS != 0) {
            return;
        }
        AABB collisionBox = getBoundingBox().deflate(WALL_RESCUE_COLLISION_DEFLATE);
        if (serverLevel.noBlockCollision(this, collisionBox)) {
            return;
        }
        CitizenNavigationService.stop(serverLevel, getUUID());
        CitizenTeleportService.teleportCitizenToNearbySafePosition(serverLevel, this);
    }

    // syncClientWorkSwingPulse：客户端收到服务端脉冲后走原版 swing 动画，不再手写僵硬曲线。
    private void syncClientWorkSwingPulse() {
        if (!level().isClientSide()) {
            return;
        }
        int pulse = this.entityData.get(DATA_WORK_SWING_PULSE);
        if (lastWorkSwingPulse < 0) {
            lastWorkSwingPulse = pulse;
            if (pulse != 0) {
                startClientWorkSwing();
            }
            return;
        }
        if (pulse != lastWorkSwingPulse) {
            lastWorkSwingPulse = pulse;
            startClientWorkSwing();
        }
    }

    private void startClientWorkSwing() {
        workSwingStartTick = tickCount;
        if (!swinging) {
            swing(InteractionHand.MAIN_HAND);
        }
    }

    // canSyncCitizenData：死亡实体在真正移除前仍可能 tick，不能在这个窗口重建 CitizenData。
    private boolean canSyncCitizenData() {
        return !isRemoved() && isAlive() && getHealth() > 0.0F;
    }

    @Override
    public float getAttackAnim(float partialTick) {
        return Math.max(super.getAttackAnim(partialTick), workSwingProgress(partialTick));
    }

    private float workSwingProgress(float partialTick) {
        int elapsed = tickCount - workSwingStartTick;
        if (elapsed < 0 || elapsed >= WORK_SWING_DURATION_TICKS) {
            return 0.0F;
        }
        return Mth.clamp((elapsed + partialTick) / WORK_SWING_DURATION_TICKS, 0.0F, 1.0F);
    }

    @Override
    public Component getDisplayName() {
        String citizenName = getCitizenName();
        return citizenName.isEmpty() ? Component.translatable("entity.simukraft.citizen") : Component.literal(citizenName);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putString("CitizenName", getCitizenName());
        compound.putString("SkinPath", getSkinPath());
        compound.putString("StatusLabel", getStatusLabel());
        compound.putDouble(TAG_HUNGER, getHungerValue());
        compound.putInt("Age", getAge());
        compound.putInt("Lifespan", getLifespan());
        compound.putBoolean("IsSick", isSick());
        compound.putBoolean("IsChildNpc", isChildNpc());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        setCitizenName(compound.getString("CitizenName"));
        setSkinPath(compound.getString("SkinPath"));
        setStatusLabel(compound.getString("StatusLabel"));
        if (compound.contains(TAG_HUNGER)) {
            setHungerInternal(compound.getDouble(TAG_HUNGER), true);
        } else {
            setHungerInternal(20.0D, false);
        }
        setAge(compound.contains("Age") ? compound.getInt("Age") : -1);
        setLifespan(compound.contains("Lifespan") ? compound.getInt("Lifespan") : -1);
        setSick(compound.getBoolean("IsSick"));
        setChildNpc(compound.getBoolean("IsChildNpc"));
    }

    public String getCitizenName() {
        return this.entityData.get(DATA_CITIZEN_NAME);
    }

    public void setCitizenName(String citizenName) {
        String safeName = citizenName != null ? citizenName : "";
        this.entityData.set(DATA_CITIZEN_NAME, safeName);
        if (!safeName.isBlank()) {
            this.setCustomName(Component.literal(safeName));
        } else {
            this.setCustomName(Component.translatable("entity.simukraft.citizen"));
        }
        this.setCustomNameVisible(true);
    }

    public String getJob() {
        return this.entityData.get(DATA_JOB);
    }

    public void setJob(String job) {
        this.entityData.set(DATA_JOB, job != null && !job.isBlank() ? job : "unemployed");
    }

    public String getStatus() {
        return this.entityData.get(DATA_STATUS);
    }

    public void setStatus(String status) {
        this.entityData.set(DATA_STATUS, status != null && !status.isBlank() ? status : "idle");
    }

    public String getSkinPath() {
        return this.entityData.get(DATA_SKIN_PATH);
    }

    public void setSkinPath(String skinPath) {
        this.entityData.set(DATA_SKIN_PATH, skinPath != null ? skinPath : "");
    }

    public String getWorkStatus() {
        return this.entityData.get(DATA_WORK_STATUS);
    }

    public void setWorkStatus(String workStatus) {
        CitizenWorkStatus status = CitizenWorkStatus.fromName(workStatus);
        this.entityData.set(DATA_WORK_STATUS, status.translationKey());
    }

    public String getStatusLabel() {
        return this.entityData.get(DATA_STATUS_LABEL);
    }

    public void setStatusLabel(String statusLabel) {
        this.entityData.set(DATA_STATUS_LABEL, statusLabel != null ? statusLabel : "");
    }

    public int getHunger() {
        return this.entityData.get(DATA_HUNGER);
    }

    public void setHunger(int hunger) {
        setHunger((double) hunger);
    }

    public double getHungerValue() {
        return level().isClientSide() ? getHunger() : hunger;
    }

    public void setHunger(double hunger) {
        setHungerInternal(hunger, true);
    }

    // initializeHungerFromData：仅旧数据/新生成实体缺少实体 NBT 饱食度时，才用 CitizenData 兜底初始化。
    public void initializeHungerFromData(double hunger) {
        if (!hungerInitialized) {
            setHungerInternal(hunger, true);
        }
    }

    private void setHungerInternal(double hunger, boolean initialized) {
        double normalized = Math.clamp(hunger, 0.0D, 20.0D);
        this.hunger = normalized;
        this.entityData.set(DATA_HUNGER, Math.clamp((int) Math.round(normalized), 0, 20));
        this.hungerInitialized = initialized;
    }

    public int getAge() {
        return this.entityData.get(DATA_AGE);
    }

    public void setAge(int age) {
        this.entityData.set(DATA_AGE, age);
    }

    public int getLifespan() {
        return this.entityData.get(DATA_LIFESPAN);
    }

    public void setLifespan(int lifespan) {
        this.entityData.set(DATA_LIFESPAN, lifespan);
    }

    public boolean isSick() {
        return this.entityData.get(DATA_IS_SICK);
    }

    public void setSick(boolean sick) {
        this.entityData.set(DATA_IS_SICK, sick);
    }

    public boolean isChildNpc() {
        return this.entityData.get(DATA_IS_CHILD);
    }

    public void setChildNpc(boolean childNpc) {
        this.entityData.set(DATA_IS_CHILD, childNpc);
    }

    public boolean hasActiveVisualTask() {
        return this.entityData.get(DATA_HAS_ACTIVE_TASK);
    }

    // setHasActiveVisualTask：同步客户端手臂动作开关，参考旧版 DATA_HAS_ACTIVE_TASK。
    public void setHasActiveVisualTask(boolean active) {
        this.entityData.set(DATA_HAS_ACTIVE_TASK, active);
    }

    // triggerWorkSwing：服务端触发一次短挥手，保证职业工具使用动作同步到客户端。
    public void triggerWorkSwing(InteractionHand hand) {
        InteractionHand normalizedHand = hand != null ? hand : InteractionHand.MAIN_HAND;
        if (level().isClientSide()) {
            swing(normalizedHand);
            return;
        }
        swing(normalizedHand, true);
        this.entityData.set(DATA_WORK_SWING_PULSE, this.entityData.get(DATA_WORK_SWING_PULSE) + 1);
    }

    public String getHungerLevelKey() {
        int hunger = getHunger();
        if (hunger >= 16) {
            return "gui.npc.hunger.level.full";
        }
        if (hunger >= 10) {
            return "gui.npc.hunger.level.bit_hungry";
        }
        if (hunger >= 4) {
            return "gui.npc.hunger.level.very_hungry";
        }
        return "gui.npc.hunger.level.starving";
    }
}
