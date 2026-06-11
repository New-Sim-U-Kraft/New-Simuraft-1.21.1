package common.cn.kafei.simukraft.commercial;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.economy.EconomyService;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CommercialTaxService {
    private static final long TICKS_PER_DAY = 24_000L;
    private static final double ENTERPRISE_TAX_RATE = 0.25D;

    private CommercialTaxService() {
    }

    /** recordShopIncome: 记录商店当天营业收入，不立即进入玩家城市资金。 */
    public static void recordShopIncome(ServerLevel level, UUID cityId, double amount) {
        double income = EconomyService.normalizeAmount(amount);
        if (level == null || cityId == null || income <= 0.0D) {
            return;
        }
        boolean saved = SimuSqliteStorage.addCommercialDailyIncome(level, cityId, incomeDay(level), income);
        if (!saved) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to record commercial income city={} amount={}", cityId, income);
        }
    }

    /** collectDueTaxes: 结算指定 MC 日之前未上交的企业税。 */
    public static Map<UUID, Double> collectDueTaxes(ServerLevel level, long currentDay) {
        if (level == null || currentDay <= 1L) {
            return Map.of();
        }
        Map<UUID, Double> incomeByCity = SimuSqliteStorage.loadUntaxedCommercialIncome(level, currentDay);
        if (incomeByCity.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Double> taxByCity = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : incomeByCity.entrySet()) {
            UUID cityId = entry.getKey();
            double tax = EconomyService.normalizeAmount(entry.getValue() * ENTERPRISE_TAX_RATE);
            if (!SimuSqliteStorage.markCommercialIncomeTaxCollected(level, cityId, currentDay)) {
                SimuKraft.LOGGER.warn("Simukraft: Failed to mark commercial enterprise tax collected city={} day={}", cityId, currentDay);
                continue;
            }
            if (tax <= 0.0D) {
                continue;
            }
            if (EconomyService.depositCityFunds(level, cityId, null, tax, "commercial_enterprise_tax")) {
                taxByCity.put(cityId, tax);
            } else {
                SimuKraft.LOGGER.warn("Simukraft: Failed to deposit commercial enterprise tax city={} amount={}", cityId, tax);
            }
        }
        return Map.copyOf(taxByCity);
    }

    /** incomeDay: 使用原版 dayTime 计算商业收入所属 MC 日。 */
    private static long incomeDay(ServerLevel level) {
        return Math.max(1L, level.getDayTime() / TICKS_PER_DAY + 1L);
    }
}
