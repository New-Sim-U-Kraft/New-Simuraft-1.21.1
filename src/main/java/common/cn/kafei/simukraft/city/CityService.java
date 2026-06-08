package common.cn.kafei.simukraft.city;

import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CityService {
    private CityService() {
    }

    public static CityData createCity(ServerLevel level, String cityName, UUID mayorId, String mayorName, BlockPos cityCorePos) {
        if (level == null || mayorId == null || cityCorePos == null) {
            return null;
        }
        return CityManager.get(level).createCity(cityName, mayorId, mayorName, cityCorePos);
    }

    public static boolean renameCity(ServerLevel level, UUID cityId, UUID operatorId, String cityName) {
        if (level == null || cityId == null || operatorId == null) {
            return false;
        }
        return CityManager.get(level).renameCity(cityId, operatorId, cityName);
    }

    public static boolean deleteCity(ServerLevel level, UUID cityId, UUID operatorId, CityChunkManager chunkManager, CityPoiManager poiManager) {
        if (level == null || cityId == null || operatorId == null) {
            return false;
        }
        return CityManager.get(level).deleteCity(cityId, operatorId, chunkManager, poiManager);
    }

    public static Optional<CityData> findCity(ServerLevel level, UUID cityId) {
        if (level == null || cityId == null) {
            return Optional.empty();
        }
        return CityManager.get(level).getCity(cityId);
    }

    public static Optional<CityData> findPlayerCity(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) {
            return Optional.empty();
        }
        return CityManager.get(level).getPlayerCity(playerId);
    }

    // 查找玩家拥有官员以上权限的城市，对齐旧版便携式城市核心的市长/官员限制。
    public static Optional<CityData> findManagedPlayerCity(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) {
            return Optional.empty();
        }
        return CityManager.get(level).getManagedPlayerCity(playerId);
    }

    public static Optional<CityData> findCityByCorePos(ServerLevel level, BlockPos cityCorePos) {
        if (level == null || cityCorePos == null) {
            return Optional.empty();
        }
        return CityManager.get(level).getCityByCorePos(cityCorePos);
    }

    public static Optional<CityData> findCityByCorePosForPlayer(ServerLevel level, BlockPos cityCorePos, UUID playerId) {
        if (level == null || cityCorePos == null || playerId == null) {
            return Optional.empty();
        }
        return findCityByCorePos(level, cityCorePos);
    }

    public static boolean hasCityAtCorePos(ServerLevel level, BlockPos cityCorePos) {
        return level != null && cityCorePos != null && CityManager.get(level).hasCityAtCorePos(cityCorePos);
    }

    public static boolean hasCityNamed(ServerLevel level, String cityName) {
        return level != null && cityName != null && CityManager.get(level).hasCityNamed(cityName);
    }

    public static String normalizeCityName(String rawCityName) {
        return rawCityName == null ? "" : rawCityName.trim();
    }

    public static boolean isValidCityName(String cityName) {
        return cityName != null
                && cityName.length() >= 2
                && cityName.length() <= 20
                && cityName.matches("[a-zA-Z0-9\\u4e00-\\u9fa5\\s]+")
                && !cityName.matches(".*\\s{2,}.*");
    }

    public static Collection<CityData> allCities(ServerLevel level) {
        if (level == null) {
            return Set.of();
        }
        return CityManager.get(level).allCities();
    }

    public static boolean addPlayer(ServerLevel level, UUID cityId, UUID operatorId, UUID targetId, String targetName, CityPermissionLevel permissionLevel) {
        if (level == null || cityId == null || operatorId == null || targetId == null) {
            return false;
        }
        return CityManager.get(level).addPlayerToCity(cityId, operatorId, targetId, targetName, permissionLevel);
    }

    public static boolean removePlayer(ServerLevel level, UUID cityId, UUID operatorId, UUID targetId) {
        if (level == null || cityId == null || operatorId == null || targetId == null) {
            return false;
        }
        return CityManager.get(level).removePlayerFromCity(cityId, operatorId, targetId);
    }

    public static boolean setPlayerPermission(ServerLevel level, UUID cityId, UUID operatorId, UUID targetId, CityPermissionLevel permissionLevel) {
        if (level == null || cityId == null || operatorId == null || targetId == null || permissionLevel == null) {
            return false;
        }
        return CityManager.get(level).setPlayerPermission(cityId, operatorId, targetId, permissionLevel);
    }

    // transferMayor: 对外提供市长转让入口，保持网络层不直接操作管理器。
    public static boolean transferMayor(ServerLevel level, UUID cityId, UUID operatorId, UUID targetId, String targetName) {
        if (level == null || cityId == null || operatorId == null || targetId == null) {
            return false;
        }
        return CityManager.get(level).transferMayor(cityId, operatorId, targetId, targetName);
    }

    public static boolean hasPermission(ServerLevel level, UUID cityId, UUID playerId, CityPermissionLevel required) {
        if (level == null || cityId == null || playerId == null || required == null) {
            return false;
        }
        return CityManager.get(level).hasPermission(cityId, playerId, required);
    }

    public static CityPermissionLevel getPlayerPermission(ServerLevel level, UUID cityId, UUID playerId) {
        if (level == null || cityId == null || playerId == null) {
            return CityPermissionLevel.CITIZEN;
        }
        return findCity(level, cityId)
                .flatMap(city -> city.member(playerId).map(CityMemberData::permissionLevel))
                .orElse(CityPermissionLevel.CITIZEN);
    }

    public static CityPermissionLevel getPlayerPermission(CityData city, UUID playerId) {
        if (city == null || playerId == null) {
            return CityPermissionLevel.CITIZEN;
        }
        return city.member(playerId)
                .map(CityMemberData::permissionLevel)
                .orElse(CityPermissionLevel.CITIZEN);
    }

    public static boolean canManageCity(CityData city, UUID playerId) {
        return city != null && playerId != null && city.hasPermission(playerId, CityPermissionLevel.OFFICIAL);
    }

    public static boolean canManageCity(ServerLevel level, UUID cityId, UUID playerId) {
        if (level == null || cityId == null || playerId == null) {
            return false;
        }
        return hasPermission(level, cityId, playerId, CityPermissionLevel.OFFICIAL);
    }

    public static Collection<CityMemberData> getMembers(ServerLevel level, UUID cityId) {
        if (level == null || cityId == null) {
            return Set.of();
        }
        return CityManager.get(level).getMembers(cityId);
    }

    public static boolean depositFunds(ServerLevel level, UUID cityId, double amount) {
        if (level == null || cityId == null) {
            return false;
        }
        return CityManager.get(level).depositFunds(cityId, amount);
    }

    public static boolean withdrawFunds(ServerLevel level, UUID cityId, double amount) {
        if (level == null || cityId == null) {
            return false;
        }
        return CityManager.get(level).withdrawFunds(cityId, amount);
    }

    public static boolean setFunds(ServerLevel level, UUID cityId, double funds) {
        if (level == null || cityId == null) {
            return false;
        }
        return CityManager.get(level).setFunds(cityId, funds);
    }

    public static boolean addFinanceTransaction(ServerLevel level, UUID cityId, FinanceTransactionData transaction, int maxRecords) {
        if (level == null || cityId == null || transaction == null) {
            return false;
        }
        return CityManager.get(level).addFinanceTransaction(cityId, transaction, maxRecords);
    }
}
