package common.cn.kafei.simukraft.commercial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.CitizenJobVisualService;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenSelfFeedingService;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CommercialWorkService {
    private static final ConcurrentMap<String, LevelRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final long IDLE_RETRY_TICKS = 80L;
    private static final long NPC_TRADE_INTERVAL_TICKS = 1200L;

    private CommercialWorkService() {
    }

    /** tick: 处理商业箱补货与 NPC 自动经营。 */
    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        CommercialBoxManager manager = CommercialBoxManager.get(level);
        LevelRuntime runtime = runtime(level);
        long gameTime = level.getGameTime();
        for (CommercialBoxData data : manager.all()) {
            BoxRuntime boxRuntime = runtime.boxes.computeIfAbsent(data.boxPos(), ignored -> new BoxRuntime());
            if (gameTime < boxRuntime.nextTick) {
                continue;
            }
            tickBox(level, manager, data, boxRuntime, gameTime);
        }
    }

    /** flush: 立即保存商业箱和库存。 */
    public static void flush(ServerLevel level) {
        if (level != null) {
            CommercialBoxManager.get(level).saveToSqlite(level);
            CommercialStockManager.get(level).saveToSqlite(level);
        }
    }

    /** clearServerCaches: 清理指定存档的商业运行时缓存。 */
    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server).toLowerCase(Locale.ROOT);
        RUNTIMES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    private static void tickBox(ServerLevel level, CommercialBoxManager manager, CommercialBoxData data, BoxRuntime runtime, long gameTime) {
        if (!CommercialControlBoxService.isCommercialControlBox(level, data.boxPos())) {
            manager.remove(data.boxPos());
            CommercialStockService.removeBox(level, data.boxPos());
            return;
        }
        PlacedBuildingRecord building = CommercialControlBoxService.resolveBuilding(level, data.boxPos());
        if (building == null) {
            setStatus(manager, data, "gui.simukraft.commercial.status.no_building", "");
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        CommercialDefinitionLoader.LoadResult loadResult = CommercialDefinitionLoader.loadForBuilding(building);
        CommercialDefinition definition = loadResult.definition();
        if (!loadResult.valid() || definition == null) {
            setStatus(manager, data, "gui.simukraft.commercial.status.invalid_definition", String.join(",", loadResult.errors()));
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        CommercialStockService.restock(level, data.boxPos(), definition);
        if (!data.running()) {
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        var worker = CommercialControlBoxService.findAssignedWorker(level, data.boxPos());
        if (worker == null) {
            setStatus(manager, data, "gui.simukraft.commercial.status.no_worker", "");
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        if (CitizenHomeRestService.isRestTime(level)) {
            CitizenJobVisualService.clearMainHandOverride(worker.uuid());
            setStatus(manager, data, "gui.simukraft.commercial.status.resting", "");
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        if (!definition.workTime().openAt(level.getDayTime())) {
            CitizenJobVisualService.clearMainHandOverride(worker.uuid());
            setStatus(manager, data, "gui.simukraft.commercial.status.closed", "");
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        if (CitizenSelfFeedingService.isSelfFeeding(level, worker.uuid())) {
            runtime.nextTick = gameTime + IDLE_RETRY_TICKS;
            return;
        }
        applyHeldItem(worker.uuid(), definition.job().heldItem());
        processNpcOffer(level, data, definition, runtime, gameTime);
    }

    private static void processNpcOffer(ServerLevel level, CommercialBoxData data, CommercialDefinition definition, BoxRuntime runtime, long gameTime) {
        List<CommercialOffer> offers = definition.npcOffers();
        if (offers.isEmpty()) {
            setStatus(CommercialBoxManager.get(level), data, "gui.simukraft.commercial.status.open", "");
            runtime.nextTick = gameTime + NPC_TRADE_INTERVAL_TICKS;
            return;
        }
        CommercialOffer offer = offers.get(Math.floorMod(runtime.offerCursor++, offers.size()));
        CommercialTradeService.TradeResult result = CommercialTradeService.executeNpcOffer(level, data.boxPos(), definition, offer);
        if (result.success()) {
            var worker = CommercialControlBoxService.findAssignedWorker(level, data.boxPos());
            if (worker != null) {
                CitizenLevelService.addExperience(level, worker.uuid(), CityJobType.COMMERCIAL_WORKER, 1);
                worker.setStatusLabel("gui.simukraft.commercial.status.open");
                CitizenService.save(level, worker.uuid());
            }
            setStatus(CommercialBoxManager.get(level), data, "gui.simukraft.commercial.status.open", "");
        }
        runtime.nextTick = gameTime + NPC_TRADE_INTERVAL_TICKS;
    }

    private static void applyHeldItem(java.util.UUID citizenId, String itemId) {
        Item item = itemById(itemId);
        if (item == Items.AIR) {
            return;
        }
        CitizenJobVisualService.setMainHandOverride(citizenId, new ItemStack(item));
    }

    private static Item itemById(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Items.AIR;
        }
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(Items.AIR);
        } catch (Exception exception) {
            return Items.AIR;
        }
    }

    private static void setStatus(CommercialBoxManager manager, CommercialBoxData data, String statusKey, String statusText) {
        String safeText = statusText != null ? statusText : "";
        if (statusKey.equals(data.statusKey()) && safeText.equals(data.statusText())) {
            return;
        }
        data.setStatusKey(statusKey);
        data.setStatusText(safeText);
        manager.persist(data);
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(SaveScopedCacheKey.levelKey(level).toLowerCase(Locale.ROOT), ignored -> new LevelRuntime());
    }

    private static final class LevelRuntime {
        private final ConcurrentMap<BlockPos, BoxRuntime> boxes = new ConcurrentHashMap<>();
    }

    private static final class BoxRuntime {
        private long nextTick;
        private int offerCursor;
    }
}
