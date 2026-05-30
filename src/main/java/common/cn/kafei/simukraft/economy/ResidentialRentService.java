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
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.registry.ModSoundEvents;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final long TICKS_PER_RENT_DAY = 24_000L;
    private static final long MONEY_COLLECT_DELAY_TICKS = 60L;
    private static final int MORNING_START = 0;
    private static final int MORNING_END_EXCLUSIVE = 1_200;
    private static final ConcurrentMap<String, Long> LAST_COLLECTED_RENT_DAY = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Long> LAST_PROCESSED_LEVEL_RENT_DAY = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, PendingIncomeNotice> PENDING_INCOME_NOTICES = new ConcurrentHashMap<>();

    private ResidentialRentService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        processPendingIncomeNotices(level);
        if (!isMorning(level)) {
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
        schedulePlayerIncomeNotices(level, rentByCity);
    }

    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server);
        LAST_COLLECTED_RENT_DAY.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
        LAST_PROCESSED_LEVEL_RENT_DAY.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
        PENDING_INCOME_NOTICES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
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
        for (ServerPlayer player : level.players()) {
            if (CityService.findPlayerCity(level, player.getUUID())
                    .map(city -> cityId.equals(city.cityId()))
                    .orElse(false)) {
                HudSyncService.syncToPlayer(player, true);
            }
        }
    }

    private static void schedulePlayerIncomeNotices(ServerLevel level, Map<UUID, Double> rentByCity) {
        long triggerTick = level.getGameTime() + MONEY_COLLECT_DELAY_TICKS;
        for (ServerPlayer player : level.players()) {
            double rentAmount = CityService.findPlayerCity(level, player.getUUID())
                    .map(CityData::cityId)
                    .map(cityId -> rentByCity.getOrDefault(cityId, 0.0D))
                    .orElse(0.0D);
            level.playSound(null, player.blockPosition(), ModSoundEvents.PLAYER_WAKE_UP.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
            PENDING_INCOME_NOTICES.put(incomeNoticeKey(player), new PendingIncomeNotice(player.getUUID(), triggerTick, rentAmount, 0.0D));
        }
    }

    private static void processPendingIncomeNotices(ServerLevel level) {
        long gameTime = level.getGameTime();
        PENDING_INCOME_NOTICES.entrySet().removeIf(entry -> {
            PendingIncomeNotice notice = entry.getValue();
            if (notice.triggerTick() > gameTime) {
                return false;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(notice.playerId());
            if (player != null && player.serverLevel() == level) {
                level.playSound(null, player.blockPosition(), ModSoundEvents.MONEY_COLLECT.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
                player.sendSystemMessage(incomeSummary(notice.rentAmount(), notice.taxAmount()));
                HudSyncService.syncToPlayer(player, true);
            }
            return true;
        });
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

    private static String incomeNoticeKey(ServerPlayer player) {
        return SaveScopedCacheKey.playerKey(player) + "|income_notice";
    }

    private static boolean isMorning(ServerLevel level) {
        long time = Math.floorMod(level.getDayTime(), TICKS_PER_RENT_DAY);
        return time >= MORNING_START && time < MORNING_END_EXCLUSIVE;
    }

    private static long rentDay(ServerLevel level) {
        return Math.max(1L, level.getDayTime() / TICKS_PER_RENT_DAY + 1L);
    }

    private static boolean isResidential(PlacedBuildingRecord building) {
        return building != null && "residential".equalsIgnoreCase(building.category());
    }

    private static double rentAmount(PlacedBuildingRecord building) {
        double storedAmount = parseAmount(building.amount());
        if (storedAmount > 0.0D) {
            return storedAmount;
        }
        return BuildingCatalog.findBuilding(building.category(), building.buildingFileName())
                .map(BuildingCatalog.BuildingDefinition::amount)
                .map(ResidentialRentService::parseAmount)
                .orElse(0.0D);
    }

    private static double parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return 0.0D;
        }
        String normalized = value.trim().replace(',', '.');
        StringBuilder numeric = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                numeric.append(c);
            }
        }
        if (numeric.isEmpty()) {
            return 0.0D;
        }
        try {
            return BigDecimal.valueOf(Double.parseDouble(numeric.toString()))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
        } catch (NumberFormatException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Invalid residential rent amount '{}'", value);
            return 0.0D;
        }
    }

    private record PendingIncomeNotice(UUID playerId, long triggerTick, double rentAmount, double taxAmount) {
    }
}
