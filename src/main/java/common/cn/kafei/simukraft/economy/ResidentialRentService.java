package common.cn.kafei.simukraft.economy;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.BuildingCatalog;
import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.city.FinanceTransactionData;
import common.cn.kafei.simukraft.commercial.CommercialTaxService;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import common.cn.kafei.simukraft.registry.ModSoundEvents;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class ResidentialRentService {
    private static final long TICKS_PER_DAY = 24_000L;
    private static final long RENT_COLLECTION_WINDOW_TICKS = 1_200L;
    private static final ConcurrentMap<String, Long> LAST_COLLECTED_RENT_DAY = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Long> LAST_PROCESSED_LEVEL_RENT_DAY = new ConcurrentHashMap<>();

    private ResidentialRentService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!isRentCollectionWindow(level)) {
            return;
        }
        long rentDay = rentDay(level);
        String levelKey = SaveScopedCacheKey.levelKey(level) + "|residential_rent_day";
        Long previous = LAST_PROCESSED_LEVEL_RENT_DAY.putIfAbsent(levelKey, rentDay);
        if (previous != null && previous >= rentDay) {
            return;
        }
        if (previous != null && !LAST_PROCESSED_LEVEL_RENT_DAY.replace(levelKey, previous, rentDay)) {
            return;
        }
        collectRentForDay(level, rentDay);
    }

    private static void collectRentForDay(ServerLevel level, long rentDay) {
        Map<UUID, Double> rentByCity = collectRentByCity(level);
        rentByCity.forEach((cityId, amount) -> collectCityRent(level, cityId, rentDay, amount));
        Map<UUID, Double> taxByCity = CommercialTaxService.collectDueTaxes(level, rentDay);
        taxByCity.keySet().forEach(cityId -> syncCityMembers(level, cityId));
        notifyPlayerIncome(level, rentByCity, taxByCity);
    }

    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server);
        LAST_COLLECTED_RENT_DAY.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
        LAST_PROCESSED_LEVEL_RENT_DAY.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    private static Map<UUID, Double> collectRentByCity(ServerLevel level) {
        PlacedBuildingService.ensureCityPoisRegistered(level);
        CityPoiManager poiManager = CityPoiManager.get(level);
        Set<UUID> occupiedHomes = new HashSet<>();
        for (CitizenData citizen : CitizenManager.get(level).allCitizens()) {
            if (citizen.dead()) {
                continue;
            }
            if (citizen.cityId() == null || citizen.homeId() == null) {
                continue;
            }
            CityPoiData home = poiManager.getPoi(citizen.homeId());
            if (home != null && home.active() && home.type() == CityPoiType.RESIDENTIAL && citizen.cityId().equals(home.cityId())) {
                occupiedHomes.add(home.poiId());
            }
        }
        if (occupiedHomes.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Double> rentByCity = new HashMap<>();
        for (PlacedBuildingRecord building : PlacedBuildingService.getBuildings(level)) {
            if (building.cityId() == null || !isResidential(building) || !hasOccupiedHome(poiManager, occupiedHomes, building)) {
                continue;
            }
            double rent = rentAmount(building);
            if (rent > 0.0D) {
                rentByCity.merge(building.cityId(), rent, Double::sum);
            }
        }
        rentByCity.replaceAll((cityId, rent) -> EconomyService.normalizeAmount(rent));
        rentByCity.entrySet().removeIf(entry -> entry.getValue() <= 0.0D);
        return rentByCity;
    }

    private static boolean hasOccupiedHome(CityPoiManager poiManager, Set<UUID> occupiedHomes, PlacedBuildingRecord building) {
        return building.poiInstances().stream()
                .filter(poi -> poi.poiType() == CityPoiType.RESIDENTIAL)
                .map(poi -> poiManager.getPoiAt(poi.worldPos()))
                .anyMatch(poi -> poi != null && occupiedHomes.contains(poi.poiId()));
    }

    private static void collectCityRent(ServerLevel level, UUID cityId, long rentDay, double amount) {
        String cityKey = SaveScopedCacheKey.levelKey(level) + "|rent_city=" + cityId;
        Long previous = LAST_COLLECTED_RENT_DAY.putIfAbsent(cityKey, rentDay);
        if (previous != null && previous >= rentDay) {
            return;
        }
        if (previous != null && !LAST_COLLECTED_RENT_DAY.replace(cityKey, previous, rentDay)) {
            return;
        }
        if (!EconomyService.depositCityFunds(level, cityId, null, amount, "residential_rent", false)) {
            LAST_COLLECTED_RENT_DAY.computeIfPresent(cityKey, (key, value) -> value == rentDay ? previous : value);
            return;
        }
        FinanceLedgerService.record(level, cityId, null, amount, EconomyService.getCityBalance(level, cityId), FinanceTransactionData.Type.INCOME, "residential_rent");
        syncCityMembers(level, cityId);
        SimuKraft.LOGGER.debug("Simukraft: Collected residential rent day={} city={} amount={}", rentDay, cityId, amount);
    }

    private static void syncCityMembers(ServerLevel level, UUID cityId) {
        HudSyncService.syncToCityGroup(level, cityId, true);
    }

    /** notifyPlayerIncome: 收租窗口触发后立即通知玩家，不再使用延迟计时器。 */
    private static void notifyPlayerIncome(ServerLevel level, Map<UUID, Double> rentByCity, Map<UUID, Double> taxByCity) {
        for (ServerPlayer player : level.players()) {
            UUID cityId = CityService.findPlayerCity(level, player.getUUID())
                    .map(CityData::cityId)
                    .orElse(null);
            double rentAmount = cityId != null ? rentByCity.getOrDefault(cityId, 0.0D) : 0.0D;
            double taxAmount = cityId != null ? taxByCity.getOrDefault(cityId, 0.0D) : 0.0D;
            level.playSound(null, player.blockPosition(), ModSoundEvents.MONEY_COLLECT.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
            InfoToastService.money(player, incomeSummary(rentAmount, taxAmount));
            HudSyncService.syncToPlayer(player, true);
        }
    }

    private static Component incomeSummary(double rentAmount, double taxAmount) {
        double totalAmount = rentAmount + taxAmount;
        return Component.translatable(
                "message.simukraft.daily_income.summary",
                String.format(Locale.ROOT, "%.2f", rentAmount),
                String.format(Locale.ROOT, "%.2f", taxAmount),
                String.format(Locale.ROOT, "%.2f", totalAmount)
        );
    }

    /** isRentCollectionWindow: 只读取原版 dayTime 判断是否处于每日收租窗口。 */
    private static boolean isRentCollectionWindow(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), TICKS_PER_DAY);
        return time < RENT_COLLECTION_WINDOW_TICKS;
    }

    /** rentDay: 使用原版 dayTime 推导自然日编号，避免另起运行计时器。 */
    private static long rentDay(ServerLevel level) {
        return Math.max(1L, level.getDayTime() / TICKS_PER_DAY + 1L);
    }

    private static boolean isResidential(PlacedBuildingRecord building) {
        return building != null && "residential".equalsIgnoreCase(building.category());
    }

    private static double rentAmount(PlacedBuildingRecord building) {
        double storedAmount = EconomyService.parseAmount(building.amount(), "residential_rent");
        if (storedAmount > 0.0D) {
            return storedAmount;
        }
        return BuildingCatalog.findBuilding(building.category(), building.buildingFileName())
                .map(BuildingCatalog.BuildingDefinition::amount)
                .map(amount -> EconomyService.parseAmount(amount, "residential_rent"))
                .orElse(0.0D);
    }

}
