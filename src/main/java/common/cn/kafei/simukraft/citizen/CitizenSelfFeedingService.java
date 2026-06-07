package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.commercial.CommercialFoodMarketService;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CitizenSelfFeedingService {
    public static final String GOING_TO_BUY_FOOD_STATUS = "gui.npc.status.going_to_buy_food";
    public static final String BUYING_FOOD_STATUS = "gui.npc.status.buying_food";
    public static final String EATING_FOOD_STATUS = "gui.npc.status.eating_food";
    public static final String TOO_HUNGRY_STRIKE_STATUS = "gui.npc.status.too_hungry_on_strike";
    private static final String FOOD_NEED_PREFIX = "food:";
    private static final double START_HUNGER_THRESHOLD = 4.0D;
    private static final double FULL_HUNGER = 20.0D;
    private static final double ARRIVAL_DISTANCE_SQR = 16.0D;
    private static final long SERVICE_INTERVAL_TICKS = 20L;
    private static final long SEARCH_RETRY_TICKS = 200L;
    private static final long DONE_COOLDOWN_TICKS = 600L;
    private static final long MOVE_RETRY_TICKS = 40L;
    private static final long EAT_VISUAL_TICKS = 40L;
    private static final ConcurrentMap<String, LevelRuntime> RUNTIMES = new ConcurrentHashMap<>();

    private CitizenSelfFeedingService() {
    }

    /** tick: 驱动饥饿 NPC 自动去商业店购买食物。 */
    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide() || level.getGameTime() % SERVICE_INTERVAL_TICKS != 0L) {
            return;
        }
        LevelRuntime runtime = runtime(level);
        if (CitizenHomeRestService.isRestTime(level)) {
            cancelAllForRest(level, runtime);
            return;
        }
        long gameTime = level.getGameTime();
        CitizenManager manager = CitizenManager.get(level);
        for (CitizenData citizen : manager.allCitizens()) {
            FeedingRuntime feeding = runtime.active.get(citizen.uuid());
            if (feeding != null) {
                tickActive(level, manager, citizen, feeding, gameTime);
                continue;
            }
            clearStaleFoodStatus(level, manager, citizen);
            if (shouldStart(level, citizen, gameTime, runtime)) {
                start(level, manager, citizen, runtime, gameTime);
            }
        }
    }

    /** isSelfFeeding: 判断指定 NPC 是否正被买饭流程抢占。 */
    public static boolean isSelfFeeding(ServerLevel level, UUID citizenId) {
        return level != null && citizenId != null && runtime(level).active.containsKey(citizenId);
    }

    /** effectiveStatusLabel: 买饭活跃时返回运行时覆盖状态，否则返回主职业状态。 */
    public static String effectiveStatusLabel(ServerLevel level, UUID citizenId, String fallbackStatusLabel) {
        if (level == null || citizenId == null) {
            return fallbackStatusLabel != null ? fallbackStatusLabel : "";
        }
        FeedingRuntime feeding = runtime(level).active.get(citizenId);
        if (feeding != null && isSelfFeedingStatus(feeding.overlayStatusLabel)) {
            return feeding.overlayStatusLabel;
        }
        return fallbackStatusLabel != null ? fallbackStatusLabel : "";
    }

    /** clearServerCaches: 清理指定存档的买饭运行时缓存。 */
    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server).toLowerCase(Locale.ROOT);
        RUNTIMES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    private static boolean shouldStart(ServerLevel level, CitizenData citizen, long gameTime, LevelRuntime runtime) {
        if (citizen == null || citizen.dead() || citizen.child() || citizen.cityId() == null) {
            return false;
        }
        if (citizen.workStatusType() == CitizenWorkStatus.RESTING || citizen.workStatusType() == CitizenWorkStatus.DEAD) {
            return false;
        }
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity == null || entity.getHungerValue() > START_HUNGER_THRESHOLD) {
            return false;
        }
        Long cooldown = runtime.cooldowns.get(citizen.uuid());
        if (cooldown != null && cooldown > gameTime) {
            return false;
        }
        return true;
    }

    private static void start(ServerLevel level, CitizenManager manager, CitizenData citizen, LevelRuntime runtime, long gameTime) {
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity == null) {
            return;
        }
        CommercialFoodMarketService.PurchasePlan plan = CommercialFoodMarketService.findPurchasePlan(level, citizen, entity);
        FeedingRuntime feeding = new FeedingRuntime(restorableStatusLabel(citizen.statusLabel()),
                restorableWorkNeedDetail(citizen.workNeedDetail()));
        runtime.active.put(citizen.uuid(), feeding);
        if (plan == null) {
            enterStrike(level, manager, citizen, feeding, gameTime);
            return;
        }
        enterTravel(level, manager, citizen, feeding, plan, gameTime);
    }

    private static void tickActive(ServerLevel level, CitizenManager manager, CitizenData citizen, FeedingRuntime feeding, long gameTime) {
        if (citizen.dead()) {
            runtime(level).active.remove(citizen.uuid());
            return;
        }
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity == null) {
            finish(level, manager, citizen, feeding, false);
            return;
        }
        if (entity.getHungerValue() > START_HUNGER_THRESHOLD && feeding.phase != Phase.EATING) {
            finish(level, manager, citizen, feeding, true);
            return;
        }
        switch (feeding.phase) {
            case STRIKE -> tickStrike(level, manager, citizen, entity, feeding, gameTime);
            case TRAVEL -> tickTravel(level, manager, citizen, entity, feeding, gameTime);
            case EATING -> {
                if (gameTime >= feeding.nextTick) {
                    finish(level, manager, citizen, feeding, true);
                }
            }
        }
    }

    private static void tickStrike(ServerLevel level, CitizenManager manager, CitizenData citizen, CitizenEntity entity, FeedingRuntime feeding, long gameTime) {
        if (gameTime < feeding.nextTick) {
            return;
        }
        CommercialFoodMarketService.PurchasePlan plan = CommercialFoodMarketService.findPurchasePlan(level, citizen, entity);
        if (plan == null) {
            enterStrike(level, manager, citizen, feeding, gameTime);
            return;
        }
        enterTravel(level, manager, citizen, feeding, plan, gameTime);
    }

    private static void tickTravel(ServerLevel level, CitizenManager manager, CitizenData citizen, CitizenEntity entity, FeedingRuntime feeding, long gameTime) {
        if (feeding.plan == null) {
            enterStrike(level, manager, citizen, feeding, gameTime);
            return;
        }
        if (entity.position().distanceToSqr(Vec3.atCenterOf(feeding.plan.boxPos())) <= ARRIVAL_DISTANCE_SQR) {
            setStatus(level, manager, citizen, BUYING_FOOD_STATUS, CommercialFoodMarketService.foodDetailKey(feeding.plan));
            CommercialFoodMarketService.PurchaseResult result = CommercialFoodMarketService.executePurchase(level, citizen, feeding.plan);
            if (!result.success()) {
                enterStrike(level, manager, citizen, feeding, gameTime);
                return;
            }
            beginEating(level, manager, citizen, entity, feeding, result.foodStack(), gameTime);
            return;
        }
        if (gameTime >= feeding.nextTick) {
            requestMove(level, citizen.uuid(), feeding.plan);
            feeding.nextTick = gameTime + MOVE_RETRY_TICKS;
        }
    }

    private static void enterTravel(ServerLevel level, CitizenManager manager, CitizenData citizen, FeedingRuntime feeding,
                                    CommercialFoodMarketService.PurchasePlan plan, long gameTime) {
        feeding.phase = Phase.TRAVEL;
        feeding.plan = plan;
        feeding.nextTick = gameTime + MOVE_RETRY_TICKS;
        setStatus(level, manager, citizen, GOING_TO_BUY_FOOD_STATUS, CommercialFoodMarketService.foodDetailKey(plan));
        requestMove(level, citizen.uuid(), plan);
    }

    private static void enterStrike(ServerLevel level, CitizenManager manager, CitizenData citizen, FeedingRuntime feeding, long gameTime) {
        feeding.phase = Phase.STRIKE;
        feeding.plan = null;
        feeding.nextTick = gameTime + SEARCH_RETRY_TICKS;
        CitizenNavigationService.stop(level, citizen.uuid());
        CitizenJobVisualService.clearMainHandOverride(citizen.uuid());
        setStatus(level, manager, citizen, TOO_HUNGRY_STRIKE_STATUS, "");
    }

    private static void beginEating(ServerLevel level, CitizenManager manager, CitizenData citizen, CitizenEntity entity,
                                    FeedingRuntime feeding, ItemStack foodStack, long gameTime) {
        feeding.phase = Phase.EATING;
        feeding.nextTick = gameTime + EAT_VISUAL_TICKS;
        entity.setHunger(FULL_HUNGER);
        setStatus(level, manager, citizen, EATING_FOOD_STATUS, CommercialFoodMarketService.foodDetailKey(feeding.plan));
        CitizenJobVisualService.setMainHandOverride(citizen.uuid(), foodStack);
        level.playSound(null, entity.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8F, 1.0F);
        entity.swing(InteractionHand.MAIN_HAND);
        manager.saveCitizenNow(citizen.uuid());
        manager.syncEntity(entity);
    }

    private static void finish(ServerLevel level, CitizenManager manager, CitizenData citizen, FeedingRuntime feeding, boolean restoreWorkplace) {
        runtime(level).active.remove(citizen.uuid());
        runtime(level).cooldowns.put(citizen.uuid(), level.getGameTime() + DONE_COOLDOWN_TICKS);
        CitizenJobVisualService.clearMainHandOverride(citizen.uuid());
        restoreOwnOverlay(level, manager, citizen, feeding);
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity != null) {
            manager.syncEntity(entity);
        }
        if (restoreWorkplace && citizen.workStatusType() == CitizenWorkStatus.WORKING) {
            CitizenWorkplaceMoveService.returnToWorkplace(level, citizen);
        }
    }

    private static void requestMove(ServerLevel level, UUID citizenId, CommercialFoodMarketService.PurchasePlan plan) {
        if (plan != null) {
            CitizenNavigationService.requestMove(level, citizenId, Vec3.atBottomCenterOf(plan.boxPos().above()), MovementIntent.SELF_FEEDING);
        }
    }

    private static void setStatus(ServerLevel level, CitizenManager manager, CitizenData citizen, String statusLabel, String detailKey) {
        String safeDetail = detailKey != null && !detailKey.isBlank() ? FOOD_NEED_PREFIX + detailKey : "";
        FeedingRuntime feeding = runtime(level).active.get(citizen.uuid());
        if (feeding != null) {
            feeding.overlayStatusLabel = statusLabel;
        }
        if (statusLabel.equals(citizen.statusLabel()) && safeDetail.equals(citizen.workNeedDetail())) {
            return;
        }
        citizen.setStatusLabel(statusLabel);
        citizen.setWorkNeedDetail(safeDetail);
        manager.saveCitizenNow(citizen.uuid());
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        if (entity != null) {
            manager.syncEntity(entity);
        }
    }

    private static void cancelAllForRest(ServerLevel level, LevelRuntime runtime) {
        CitizenManager manager = CitizenManager.get(level);
        runtime.active.forEach((citizenId, feeding) -> CitizenService.findCitizen(level, citizenId)
                .ifPresent(citizen -> {
                    if (citizen.workStatusType() == CitizenWorkStatus.RESTING) {
                        runtime.active.remove(citizenId);
                        runtime.cooldowns.put(citizenId, level.getGameTime() + DONE_COOLDOWN_TICKS);
                        CitizenNavigationService.stop(level, citizenId);
                        CitizenJobVisualService.clearMainHandOverride(citizenId);
                        restoreOwnOverlay(level, manager, citizen, feeding);
                    } else {
                        finish(level, manager, citizen, feeding, false);
                    }
                }));
        runtime.active.clear();
    }

    /** clearStaleFoodStatus: 清理已吃饱 NPC 身上残留的买饭临时状态。 */
    private static boolean clearStaleFoodStatus(ServerLevel level, CitizenManager manager, CitizenData citizen) {
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, citizen.uuid());
        double hunger = entity != null ? entity.getHungerValue() : citizen.hunger();
        if (hunger <= START_HUNGER_THRESHOLD
                || !isSelfFeedingStatus(citizen.statusLabel()) && !isFoodNeedDetail(citizen.workNeedDetail())) {
            return false;
        }
        citizen.setStatusLabel(restorableStatusLabel(citizen.statusLabel()));
        citizen.setWorkNeedDetail(restorableWorkNeedDetail(citizen.workNeedDetail()));
        manager.saveCitizenNow(citizen.uuid());
        if (entity != null) {
            manager.syncEntity(entity);
        }
        return true;
    }

    /** restoreOwnOverlay: 流程结束时只撤销买饭覆盖层，不回滚主职业状态。 */
    private static void restoreOwnOverlay(ServerLevel level, CitizenManager manager, CitizenData citizen, FeedingRuntime feeding) {
        boolean changed = false;
        if (isSelfFeedingStatus(citizen.statusLabel())) {
            citizen.setStatusLabel(restorableStatusLabel(feeding.previousStatusLabel));
            changed = true;
        }
        if (isFoodNeedDetail(citizen.workNeedDetail())) {
            citizen.setWorkNeedDetail(restorableWorkNeedDetail(feeding.previousWorkNeedDetail));
            changed = true;
        }
        if (changed) {
            manager.saveCitizenNow(citizen.uuid());
        }
    }

    /** restorableStatusLabel: 自喂食状态是临时状态，不能作为完成后的恢复目标。 */
    private static String restorableStatusLabel(String statusLabel) {
        return isSelfFeedingStatus(statusLabel) ? "" : statusLabel != null ? statusLabel : "";
    }

    /** restorableWorkNeedDetail: 自喂食详情只服务头顶状态，流程结束后必须移除。 */
    private static String restorableWorkNeedDetail(String workNeedDetail) {
        return isFoodNeedDetail(workNeedDetail) ? "" : workNeedDetail != null ? workNeedDetail : "";
    }

    /** isSelfFeedingStatus: 判断状态是否来自自动买饭流程。 */
    private static boolean isSelfFeedingStatus(String statusLabel) {
        return GOING_TO_BUY_FOOD_STATUS.equals(statusLabel)
                || BUYING_FOOD_STATUS.equals(statusLabel)
                || EATING_FOOD_STATUS.equals(statusLabel)
                || TOO_HUNGRY_STRIKE_STATUS.equals(statusLabel);
    }

    /** isFoodNeedDetail: 判断详情是否来自自动买饭流程。 */
    private static boolean isFoodNeedDetail(String workNeedDetail) {
        return workNeedDetail != null && workNeedDetail.startsWith(FOOD_NEED_PREFIX);
    }

    /** isSelfFeedingStatusLabel: 暴露给显示层判断买饭临时状态是否应覆盖主状态。 */
    public static boolean isSelfFeedingStatusLabel(String statusLabel) {
        return isSelfFeedingStatus(statusLabel);
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(SaveScopedCacheKey.levelKey(level).toLowerCase(Locale.ROOT), ignored -> new LevelRuntime());
    }

    private enum Phase {
        TRAVEL,
        STRIKE,
        EATING
    }

    private static final class LevelRuntime {
        private final ConcurrentMap<UUID, FeedingRuntime> active = new ConcurrentHashMap<>();
        private final ConcurrentMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    }

    private static final class FeedingRuntime {
        private final String previousStatusLabel;
        private final String previousWorkNeedDetail;
        private Phase phase = Phase.STRIKE;
        private CommercialFoodMarketService.PurchasePlan plan;
        private volatile String overlayStatusLabel = "";
        private long nextTick;

        private FeedingRuntime(String previousStatusLabel, String previousWorkNeedDetail) {
            this.previousStatusLabel = previousStatusLabel != null ? previousStatusLabel : "";
            this.previousWorkNeedDetail = previousWorkNeedDetail != null ? previousWorkNeedDetail : "";
        }
    }
}
