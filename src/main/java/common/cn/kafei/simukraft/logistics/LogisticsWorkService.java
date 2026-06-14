package common.cn.kafei.simukraft.logistics;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.economy.EconomyService;
import common.cn.kafei.simukraft.material.GenericContainerAccess;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("null")
public final class LogisticsWorkService {
    private static final ConcurrentMap<String, AtomicInteger> CURSORS = new ConcurrentHashMap<>();
    private static volatile List<LogisticsChannelData> cachedChannels = List.of();
    private static volatile long cachedChannelVersion = Long.MIN_VALUE;
    private static volatile String cachedChannelLevelKey = "";

    private LogisticsWorkService() {
    }

    /** tick: 服务端定期批处理物流路线转运。 */
    public static void tick(ServerLevel level) {
        if (level == null) {
            return;
        }
        int interval = Math.max(20, ServerConfig.logisticsTransferIntervalTicks());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        LogisticsManager manager = LogisticsManager.get(level);
        String levelKey = SaveScopedCacheKey.levelKey(level);
        long channelVersion = manager.getChannelVersion();
        List<LogisticsChannelData> channels;
        if (channelVersion == cachedChannelVersion && levelKey.equals(cachedChannelLevelKey)) {
            channels = cachedChannels;
        } else {
            channels = manager.allChannels().stream()
                    .filter(LogisticsChannelData::enabled)
                    .sorted(Comparator.comparing(LogisticsChannelData::updatedAt))
                    .toList();
            cachedChannels = channels;
            cachedChannelVersion = channelVersion;
            cachedChannelLevelKey = levelKey;
        }
        if (channels.isEmpty()) {
            return;
        }
        AtomicInteger cursor = CURSORS.computeIfAbsent(levelKey, ignored -> new AtomicInteger());
        int start = Math.floorMod(cursor.get(), channels.size());
        int maxChannels = Math.min(channels.size(), Math.max(1, ServerConfig.logisticsMaxChannelsPerTick()));
        int maxTransfers = Math.max(1, ServerConfig.logisticsMaxTransfersPerTick());
        int transfers = 0;
        int processed = 0;
        for (int i = 0; i < maxChannels && transfers < maxTransfers; i++) {
            LogisticsChannelData channel = channels.get((start + i) % channels.size());
            processed++;
            if (transferOne(level, channel)) {
                transfers++;
            }
        }
        cursor.set((start + processed) % channels.size());
    }

    /** clearServerCaches: 清理 tick 游标，避免切档后复用旧维度状态。 */
    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server);
        CURSORS.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
        cachedChannels = List.of();
        cachedChannelVersion = Long.MIN_VALUE;
        cachedChannelLevelKey = "";
    }

    private static boolean transferOne(ServerLevel level, LogisticsChannelData channel) {
        try {
            LogisticsWarehouseData warehouse = LogisticsManager.get(level).warehouse(channel.warehouseId());
            LogisticsClientData client = LogisticsControlBoxService.resolveClient(level, channel.clientId());
            if (!validRoute(warehouse, client) || LogisticsControlBoxService.findAssignedStorageWorker(level, warehouse.boxPos()) == null) {
                return false;
            }
            List<BlockPos> sourcePositions;
            List<BlockPos> targetPositions;
            if (channel.direction() == LogisticsDirection.CLIENT_TO_WAREHOUSE) {
                sourcePositions = clientPortPositions(client, "output");
                targetPositions = warehouse.containers();
            } else {
                sourcePositions = warehouse.containers();
                targetPositions = clientPortPositions(client, "input");
            }
            // 接收端保有量：接收端持有的匹配物品达到上限即停止转运（0 表示无限供应）。
            int targetKeep = channel.keepTargetQuantity();
            int targetRemaining = targetKeep > 0 ? targetKeep - countItems(level, targetPositions, channel) : Integer.MAX_VALUE;
            if (targetRemaining <= 0) {
                return false;
            }
            // 发送端保有量：发送端只外发超出保有量的部分（0 表示不保留）。
            int sourceKeep = channel.keepSourceQuantity();
            int sourceSurplus = sourceKeep > 0 ? countItems(level, sourcePositions, channel) - sourceKeep : Integer.MAX_VALUE;
            if (sourceSurplus <= 0) {
                return false;
            }
            return transfer(level, channel, sourcePositions, targetPositions, warehouse, Math.min(sourceSurplus, targetRemaining));
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Logistics transfer failed for channel {}", channel.channelId(), exception);
            return false;
        }
    }

    private static boolean transfer(ServerLevel level,
                                    LogisticsChannelData channel,
                                    List<BlockPos> sourcePositions,
                                    List<BlockPos> targetPositions,
                                    LogisticsWarehouseData warehouse,
                                    int maxMove) {
        if (sourcePositions.isEmpty() || targetPositions.isEmpty() || maxMove <= 0) {
            return false;
        }
        for (BlockPos source : sourcePositions) {
            if (!level.isLoaded(source)) {
                continue;
            }
            for (GenericContainerAccess.SlotSnapshot snapshot : GenericContainerAccess.snapshotSlots(level, source)) {
                ItemStack stack = snapshot.stack();
                if (stack.isEmpty() || !matches(channel, stack, level)) {
                    continue;
                }
                InsertTarget target = findInsertTarget(level, targetPositions, stack);
                if (target == null || target.amount() <= 0) {
                    continue;
                }
                int moveAmount = Math.min(target.amount(), maxMove);
                if (moveAmount <= 0) {
                    continue;
                }
                double cost = transferCost(warehouse.boxPos(), target.pos());
                if (!charge(level, warehouse.cityId(), cost)) {
                    return false;
                }
                ItemStack extracted = GenericContainerAccess.extractFromSlot(level, source, snapshot.slot(), snapshot.access(), snapshot.side(), moveAmount,
                        current -> ItemStack.isSameItemSameComponents(current, stack) && matches(channel, current, level));
                if (extracted.isEmpty()) {
                    refund(level, warehouse.cityId(), cost);
                    continue;
                }
                ItemStack remaining = GenericContainerAccess.insert(level, target.pos(), extracted);
                if (!remaining.isEmpty()) {
                    ItemStack rollback = GenericContainerAccess.insert(level, source, remaining);
                    if (!rollback.isEmpty()) {
                        SimuKraft.LOGGER.warn("Simukraft: Logistics rollback left {} items at {}", rollback.getCount(), source);
                    }
                    refund(level, warehouse.cityId(), cost);
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean validRoute(LogisticsWarehouseData warehouse, LogisticsClientData client) {
        return warehouse != null
                && client != null
                && warehouse.cityId() != null
                && warehouse.cityId().equals(client.cityId())
                && warehouse.dimensionId().equals(client.dimensionId());
    }

    private static List<BlockPos> clientPortPositions(LogisticsClientData client, String preferredKind) {
        if (client == null || client.ports().isEmpty()) {
            return List.of();
        }
        List<BlockPos> preferred = new ArrayList<>();
        for (LogisticsPortData port : client.ports()) {
            if (preferredKind.equalsIgnoreCase(port.kind())) {
                preferred.add(port.pos());
            }
        }
        if (!preferred.isEmpty()) {
            return List.copyOf(preferred);
        }
        return client.ports().stream().map(LogisticsPortData::pos).toList();
    }

    private static InsertTarget findInsertTarget(ServerLevel level, List<BlockPos> targets, ItemStack stack) {
        for (BlockPos target : targets) {
            if (!level.isLoaded(target)) {
                continue;
            }
            ItemStack candidate = stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
            int amount = GenericContainerAccess.countInsertable(level, target, candidate);
            if (amount > 0) {
                return new InsertTarget(target, Math.min(amount, candidate.getCount()));
            }
        }
        return null;
    }

    private static int countItems(ServerLevel level, List<BlockPos> positions, LogisticsChannelData channel) {
        int total = 0;
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            for (GenericContainerAccess.SlotSnapshot snapshot : GenericContainerAccess.snapshotSlots(level, pos)) {
                if (!snapshot.stack().isEmpty() && matches(channel, snapshot.stack(), level)) {
                    total += snapshot.stack().getCount();
                }
            }
        }
        return total;
    }

    private static boolean matches(LogisticsChannelData channel, ItemStack stack, ServerLevel level) {
        if (channel.filters().isEmpty()) {
            return true;
        }
        return channel.filters().stream().anyMatch(filter -> filter.matches(stack, level.registryAccess()));
    }

    private static boolean charge(ServerLevel level, UUID cityId, double amount) {
        double normalized = EconomyService.normalizeAmount(amount);
        if (!ServerConfig.logisticsChargeEnabled() || normalized <= 0.0D) {
            return true;
        }
        return EconomyService.canAfford(level, cityId, normalized)
                && EconomyService.withdrawCityFunds(level, cityId, null, normalized, "logistics_transfer", true);
    }

    private static void refund(ServerLevel level, UUID cityId, double amount) {
        double normalized = EconomyService.normalizeAmount(amount);
        if (ServerConfig.logisticsChargeEnabled() && normalized > 0.0D) {
            EconomyService.depositCityFunds(level, cityId, null, normalized, "logistics_transfer_refund", true);
        }
    }

    private static double transferCost(BlockPos source, BlockPos target) {
        if (!ServerConfig.logisticsChargeEnabled() || source == null || target == null) {
            return 0.0D;
        }
        double distance = Math.sqrt(source.distSqr(target));
        double cost = ServerConfig.logisticsBaseCost();
        double extraDistance = Math.max(0.0D, distance - ServerConfig.logisticsFreeDistanceBlocks());
        if (extraDistance > 0.0D) {
            int steps = (int) Math.ceil(extraDistance / Math.max(1, ServerConfig.logisticsDistanceStepBlocks()));
            cost += steps * ServerConfig.logisticsStepCost();
        }
        return EconomyService.normalizeAmount(cost);
    }

    private record InsertTarget(BlockPos pos, int amount) {
    }
}
