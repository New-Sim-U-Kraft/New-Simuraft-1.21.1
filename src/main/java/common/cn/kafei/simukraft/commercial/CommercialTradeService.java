package common.cn.kafei.simukraft.commercial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.economy.EconomyService;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings("null")
public final class CommercialTradeService {
    private static final int MAX_TRADE_COUNT = 64;

    private CommercialTradeService() {
    }

    /** executePlayerTrade: 执行玩家发起的商业交易。 */
    public static synchronized TradeResult executePlayerTrade(ServerLevel level, ServerPlayer player, BlockPos boxPos, String offerId, int count) {
        return executePlayerTrade(level, player, boxPos, offerId, count, true);
    }

    /** executePlayerTrade: 根据点击方式执行玩家商业交易。 */
    public static synchronized TradeResult executePlayerTrade(ServerLevel level, ServerPlayer player, BlockPos boxPos, String offerId, int count, boolean quickMove) {
        int times = Math.clamp(count, 1, MAX_TRADE_COUNT);
        if (level == null || player == null || boxPos == null) {
            return TradeResult.fail("message.simukraft.commercial.invalid_trade");
        }
        PlacedBuildingRecord building = CommercialControlBoxService.resolveBuilding(level, boxPos);
        if (building == null || building.cityId() == null) {
            return TradeResult.fail("message.simukraft.commercial.no_building");
        }
        TradeResult stateValidation = validateBoxState(level, boxPos);
        if (!stateValidation.success()) {
            return stateValidation;
        }
        CityData playerCity = CityService.findPlayerCity(level, player.getUUID()).orElse(null);
        if (playerCity == null || !building.cityId().equals(playerCity.cityId())) {
            return TradeResult.fail("message.simukraft.commercial.not_city_member");
        }
        CommercialDefinitionLoader.LoadResult loadResult = CommercialDefinitionLoader.loadForBuilding(building);
        if (!loadResult.valid()) {
            return TradeResult.fail("message.simukraft.commercial.invalid_definition");
        }
        CommercialDefinition definition = loadResult.definition();
        CommercialOffer offer = definition.offerById(offerId);
        if (offer == null || !offer.visibleToPlayer()) {
            return TradeResult.fail("message.simukraft.commercial.offer_unavailable");
        }
        CommercialStockService.restock(level, boxPos, definition);
        TradeResult validation = validatePlayer(level, player, building.cityId(), boxPos, offer, times, quickMove);
        if (!validation.success()) {
            return validation;
        }
        return applyPlayerTrade(level, player, building.cityId(), boxPos, offer, times, quickMove);
    }

    /** executeNpcOffer: 执行 NPC 自动经营可见的商业报价。 */
    public static synchronized TradeResult executeNpcOffer(ServerLevel level, BlockPos boxPos, CommercialDefinition definition, CommercialOffer offer) {
        if (level == null || boxPos == null || definition == null || offer == null || !offer.visibleToNpc()) {
            return TradeResult.fail("message.simukraft.commercial.offer_unavailable");
        }
        PlacedBuildingRecord building = CommercialControlBoxService.resolveBuilding(level, boxPos);
        if (building == null || building.cityId() == null) {
            return TradeResult.fail("message.simukraft.commercial.no_building");
        }
        TradeResult stateValidation = validateBoxState(level, boxPos);
        if (!stateValidation.success()) {
            return stateValidation;
        }
        CommercialStockService.restock(level, boxPos, definition);
        Map<String, Integer> stockDeltas = stockDeltas(offer, 1);
        if (stockDeltas.isEmpty()) {
            return TradeResult.fail("message.simukraft.commercial.npc_trade_unsupported");
        }
        TradeResult supplyValidation = CommercialTradeSupplyService.validate(level, boxPos, offer, 1);
        if (!supplyValidation.success()) {
            return supplyValidation;
        }
        if (totalMoney(offer.result(), 1) > 0.0D) {
            return TradeResult.fail("message.simukraft.commercial.npc_trade_unsupported");
        }
        if (!CommercialTradeSupplyService.apply(level, boxPos, offer, 1)) {
            return TradeResult.fail("message.simukraft.commercial.insufficient_materials");
        }
        double income = totalMoney(offer.cost(), 1);
        if (income > 0.0D) {
            CommercialTaxService.recordShopIncome(level, building.cityId(), income);
        }
        return TradeResult.success("message.simukraft.commercial.npc_trade_done");
    }

    private static TradeResult validateBoxState(ServerLevel level, BlockPos boxPos) {
        CommercialBoxData data = CommercialBoxManager.get(level).get(boxPos);
        if (data != null && !data.running()) {
            return TradeResult.fail("gui.simukraft.commercial.status.closed");
        }
        if (CommercialControlBoxService.findAssignedWorker(level, boxPos) == null) {
            return TradeResult.fail("gui.simukraft.commercial.status.no_worker");
        }
        return TradeResult.success("message.simukraft.commercial.ready");
    }

    private static TradeResult validatePlayer(ServerLevel level, ServerPlayer player, UUID cityId, BlockPos boxPos, CommercialOffer offer, int times, boolean quickMove) {
        TradeResult supplyValidation = CommercialTradeSupplyService.validate(level, boxPos, offer, times);
        if (!supplyValidation.success()) {
            return supplyValidation;
        }
        double moneyCost = totalMoney(offer.cost(), times);
        if (moneyCost > 0.0D && !EconomyService.canAfford(level, cityId, moneyCost)) {
            return TradeResult.fail("message.simukraft.commercial.not_enough_funds");
        }
        for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : itemTotals(offer.cost(), times).entrySet()) {
            if (countPlayerItems(player, entry.getKey()) < entry.getValue()) {
                return TradeResult.fail("message.simukraft.commercial.not_enough_items");
            }
        }
        TradeResult deliveryValidation = validateResultDelivery(player, offer, times, quickMove);
        if (!deliveryValidation.success()) {
            return deliveryValidation;
        }
        return TradeResult.success("message.simukraft.commercial.ready");
    }

    private static TradeResult applyPlayerTrade(ServerLevel level, ServerPlayer player, UUID cityId, BlockPos boxPos, CommercialOffer offer, int times, boolean quickMove) {
        List<ItemStack> resultItems = resultItemStacks(offer, times);
        ItemStack carriedStack = quickMove || resultItems.isEmpty() ? player.containerMenu.getCarried().copy() : carriedResultStack(player, resultItems);
        double moneyCost = totalMoney(offer.cost(), times);
        if (moneyCost > 0.0D && !EconomyService.withdrawCityFunds(level, cityId, player, moneyCost, "commercial_trade")) {
            return TradeResult.fail("message.simukraft.commercial.not_enough_funds");
        }
        if (!CommercialTradeSupplyService.apply(level, boxPos, offer, times)) {
            if (moneyCost > 0.0D) {
                EconomyService.depositCityFunds(level, cityId, player, moneyCost, "commercial_trade_refund");
            }
            return TradeResult.fail("message.simukraft.commercial.insufficient_materials");
        }
        for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : itemTotals(offer.cost(), times).entrySet()) {
            removePlayerItems(player, entry.getKey(), entry.getValue());
        }
        double moneyResult = totalMoney(offer.result(), times);
        if (moneyResult > 0.0D) {
            EconomyService.depositCityFunds(level, cityId, player, moneyResult, "commercial_trade");
        }
        if (quickMove) {
            for (ItemStack stack : resultItems) {
                giveItem(player, stack);
            }
        }
        HudSyncService.syncToPlayer(player, true);
        return TradeResult.success(Component.translatable("message.simukraft.commercial.trade_success", times), carriedStack);
    }

    private static TradeResult validateResultDelivery(ServerPlayer player, CommercialOffer offer, int times, boolean quickMove) {
        List<ItemStack> resultItems = resultItemStacks(offer, times);
        if (resultItems.isEmpty()) {
            return TradeResult.success("message.simukraft.commercial.ready");
        }
        if (quickMove) {
            return canInsertAll(player, resultItems)
                    ? TradeResult.success("message.simukraft.commercial.ready")
                    : TradeResult.fail("message.simukraft.commercial.inventory_full");
        }
        if (resultItems.size() != 1) {
            return TradeResult.fail("message.simukraft.commercial.invalid_trade");
        }
        ItemStack result = resultItems.getFirst();
        ItemStack carried = player.containerMenu.getCarried();
        if (result.getCount() > result.getMaxStackSize()) {
            return TradeResult.fail("message.simukraft.commercial.carried_not_empty");
        }
        if (carried.isEmpty()) {
            return TradeResult.success("message.simukraft.commercial.ready");
        }
        if (!ItemStack.isSameItemSameComponents(carried, result) || carried.getCount() + result.getCount() > carried.getMaxStackSize()) {
            return TradeResult.fail("message.simukraft.commercial.carried_not_empty");
        }
        return TradeResult.success("message.simukraft.commercial.ready");
    }

    private static List<ItemStack> resultItemStacks(CommercialOffer offer, int times) {
        List<ItemStack> stacks = new ArrayList<>();
        for (CommercialResource resource : offer.result()) {
            if (resource.type() == CommercialResource.Type.ITEM) {
                stacks.add(resource.stack(times));
            }
        }
        return stacks;
    }

    private static ItemStack carriedResultStack(ServerPlayer player, List<ItemStack> resultItems) {
        if (resultItems.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = resultItems.getFirst();
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            return result.copy();
        }
        ItemStack merged = carried.copy();
        merged.grow(result.getCount());
        return merged;
    }

    private static boolean canInsertAll(ServerPlayer player, List<ItemStack> stacks) {
        List<ItemStack> slots = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            slots.add(player.getInventory().getItem(slot).copy());
        }
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            simulateMerge(slots, remaining);
            simulateEmptySlotInsert(slots, remaining);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void simulateMerge(List<ItemStack> slots, ItemStack remaining) {
        for (ItemStack slot : slots) {
            if (remaining.isEmpty()) {
                return;
            }
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, remaining)) {
                continue;
            }
            int max = Math.min(slot.getMaxStackSize(), remaining.getMaxStackSize());
            int moved = Math.min(remaining.getCount(), Math.max(0, max - slot.getCount()));
            if (moved > 0) {
                slot.grow(moved);
                remaining.shrink(moved);
            }
        }
    }

    private static void simulateEmptySlotInsert(List<ItemStack> slots, ItemStack remaining) {
        for (int i = 0; i < slots.size() && !remaining.isEmpty(); i++) {
            if (!slots.get(i).isEmpty()) {
                continue;
            }
            int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            slots.set(i, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }
    }

    private static double totalMoney(java.util.List<CommercialResource> resources, int times) {
        double total = 0.0D;
        for (CommercialResource resource : resources) {
            if (resource.type() == CommercialResource.Type.MONEY) {
                total += resource.moneyFor(times);
            }
        }
        return EconomyService.normalizeAmount(total);
    }

    private static Map<String, Integer> stockDeltas(CommercialOffer offer, int times) {
        Map<String, Integer> deltas = new LinkedHashMap<>();
        for (CommercialResource resource : offer.cost()) {
            if (resource.type() == CommercialResource.Type.ITEM) {
                deltas.merge(resource.itemId(), resource.countFor(times), Integer::sum);
            }
        }
        for (CommercialResource resource : offer.result()) {
            if (resource.type() == CommercialResource.Type.ITEM) {
                deltas.merge(resource.itemId(), -resource.countFor(times), Integer::sum);
            }
        }
        deltas.entrySet().removeIf(entry -> entry.getValue() == 0);
        return deltas;
    }

    private static Map<net.minecraft.world.item.Item, Integer> itemTotals(java.util.List<CommercialResource> resources, int times) {
        Map<net.minecraft.world.item.Item, Integer> totals = new LinkedHashMap<>();
        for (CommercialResource resource : resources) {
            if (resource.type() == CommercialResource.Type.ITEM) {
                totals.merge(resource.item(), resource.countFor(times), Integer::sum);
            }
        }
        return totals;
    }

    private static int countPlayerItems(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removePlayerItems(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
        int remaining = Math.max(0, amount);
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
    }

    private static void giveItem(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = stack.copy();
        boolean inserted = player.getInventory().add(remaining);
        if (!inserted || !remaining.isEmpty()) {
            player.drop(remaining, false);
        }
    }

    public record TradeResult(boolean success, Component message, ItemStack carriedStack) {
        /** success: 创建交易成功结果。 */
        public static TradeResult success(String key) {
            return success(Component.translatable(key));
        }

        /** success: 创建带自定义消息的交易成功结果。 */
        public static TradeResult success(Component message) {
            return success(message, ItemStack.EMPTY);
        }

        /** success: 创建带鼠标手持结果的交易成功结果。 */
        public static TradeResult success(Component message, ItemStack carriedStack) {
            return new TradeResult(true, Objects.requireNonNullElse(message, Component.empty()), Objects.requireNonNullElse(carriedStack, ItemStack.EMPTY));
        }

        /** fail: 创建交易失败结果。 */
        public static TradeResult fail(String key) {
            return new TradeResult(false, Component.translatable(key), ItemStack.EMPTY);
        }

        /** money: 格式化资金文本。 */
        public static String money(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }
}
