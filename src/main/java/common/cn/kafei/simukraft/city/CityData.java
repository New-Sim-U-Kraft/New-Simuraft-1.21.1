package common.cn.kafei.simukraft.city;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CityData {
    private final UUID cityId;
    private String cityName;
    private BlockPos cityCorePos;
    private double funds;
    private int cityLevel;
    private final ConcurrentMap<UUID, CityMemberData> members = new ConcurrentHashMap<>();
    private final List<FinanceTransactionData> financeTransactions = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CityData(UUID cityId, String cityName, UUID mayorId, String mayorName, BlockPos cityCorePos) {
        this.cityId = cityId;
        this.cityName = cityName != null && !cityName.isBlank() ? cityName : "未命名城市";
        this.cityCorePos = cityCorePos != null ? cityCorePos.immutable() : BlockPos.ZERO;
        this.funds = 20.0D;
        addOrUpdateMember(mayorId, mayorName, CityPermissionLevel.MAYOR);
    }

    private CityData(UUID cityId) {
        this.cityId = cityId;
        this.cityName = "未命名城市";
        this.cityCorePos = BlockPos.ZERO;
        this.funds = 20.0D;
    }

    public static CityData fromTag(CompoundTag tag) {
        CityData data = new CityData(tag.getUUID("CityId"));
        data.cityName = tag.getString("CityName");
        data.cityCorePos = new BlockPos(tag.getInt("CoreX"), tag.getInt("CoreY"), tag.getInt("CoreZ"));
        data.funds = tag.getDouble("Funds");
        data.cityLevel = tag.getInt("CityLevel");
        ListTag memberTags = tag.getList("Members", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < memberTags.size(); i++) {
            CityMemberData member = CityMemberData.fromTag(memberTags.getCompound(i));
            data.members.put(member.playerId(), member);
        }
        ListTag financeTags = tag.getList("FinanceTransactions", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < financeTags.size(); i++) {
            data.financeTransactions.add(FinanceTransactionData.fromTag(financeTags.getCompound(i)));
        }
        return data;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("CityId", cityId);
        tag.putString("CityName", cityName);
        tag.putInt("CoreX", cityCorePos.getX());
        tag.putInt("CoreY", cityCorePos.getY());
        tag.putInt("CoreZ", cityCorePos.getZ());
        tag.putDouble("Funds", funds);
        tag.putInt("CityLevel", cityLevel);
        ListTag memberTags = new ListTag();
        members.values().forEach(member -> memberTags.add(member.toTag()));
        tag.put("Members", memberTags);
        ListTag financeTags = new ListTag();
        financeTransactions.forEach(transaction -> financeTags.add(transaction.toTag()));
        tag.put("FinanceTransactions", financeTags);
        return tag;
    }

    public UUID cityId() {
        return cityId;
    }

    public String cityName() {
        return cityName;
    }

    public void setCityName(String cityName) {
        if (cityName != null && !cityName.isBlank()) {
            this.cityName = cityName.trim();
        }
    }

    public BlockPos cityCorePos() {
        return cityCorePos;
    }

    public double funds() {
        return funds;
    }

    public synchronized void setFunds(double funds) {
        this.funds = normalizeFunds(funds);
    }

    public synchronized boolean depositFunds(double amount) {
        double normalized = normalizeAmount(amount);
        if (normalized <= 0.0D) {
            return false;
        }
        funds = normalizeFunds(funds + normalized);
        return true;
    }

    public synchronized boolean withdrawFunds(double amount) {
        double normalized = normalizeAmount(amount);
        if (normalized <= 0.0D || funds < normalized) {
            return false;
        }
        funds = normalizeFunds(funds - normalized);
        return true;
    }

    public int cityLevel() {
        return cityLevel;
    }

    public Collection<CityMemberData> members() {
        return members.values();
    }

    public List<FinanceTransactionData> financeTransactions() {
        return List.copyOf(financeTransactions);
    }

    public synchronized void addFinanceTransaction(FinanceTransactionData transaction, int maxRecords) {
        if (transaction == null) {
            return;
        }
        financeTransactions.add(0, transaction);
        while (financeTransactions.size() > Math.max(1, maxRecords)) {
            financeTransactions.remove(financeTransactions.size() - 1);
        }
    }

    public Optional<CityMemberData> member(UUID playerId) {
        return Optional.ofNullable(members.get(playerId));
    }

    public void addOrUpdateMember(UUID playerId, String playerName, CityPermissionLevel permissionLevel) {
        if (playerId == null) {
            return;
        }
        members.compute(playerId, (id, existing) -> {
            if (existing == null) {
                return new CityMemberData(id, playerName, permissionLevel);
            }
            existing.setPlayerName(playerName);
            existing.setPermissionLevel(permissionLevel);
            return existing;
        });
    }

    public boolean removeMember(UUID playerId) {
        CityMemberData member = members.get(playerId);
        if (member != null && member.permissionLevel() == CityPermissionLevel.MAYOR) {
            return false;
        }
        return members.remove(playerId) != null;
    }

    public boolean setPermission(UUID playerId, CityPermissionLevel permissionLevel) {
        CityMemberData member = members.get(playerId);
        if (member == null || member.permissionLevel() == CityPermissionLevel.MAYOR || permissionLevel == CityPermissionLevel.MAYOR) {
            return false;
        }
        member.setPermissionLevel(permissionLevel);
        return true;
    }

    // transferMayor: 原子切换市长身份，避免城市同时出现多个市长。
    public synchronized boolean transferMayor(UUID currentMayorId, UUID targetId, String targetName) {
        if (currentMayorId == null || targetId == null || currentMayorId.equals(targetId)) {
            return false;
        }
        CityMemberData currentMayor = members.get(currentMayorId);
        if (currentMayor == null || currentMayor.permissionLevel() != CityPermissionLevel.MAYOR) {
            return false;
        }
        currentMayor.setPermissionLevel(CityPermissionLevel.OFFICIAL);
        members.compute(targetId, (id, existing) -> {
            if (existing == null) {
                return new CityMemberData(id, targetName, CityPermissionLevel.MAYOR);
            }
            existing.setPlayerName(targetName);
            existing.setPermissionLevel(CityPermissionLevel.MAYOR);
            return existing;
        });
        return true;
    }

    public boolean hasPermission(UUID playerId, CityPermissionLevel required) {
        CityMemberData member = members.get(playerId);
        return member != null && member.permissionLevel().atLeast(required);
    }

    private static double normalizeAmount(double amount) {
        if (!Double.isFinite(amount)) {
            return 0.0D;
        }
        return normalizeFunds(Math.max(0.0D, amount));
    }

    private static double normalizeFunds(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return BigDecimal.valueOf(Math.max(0.0D, value)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
