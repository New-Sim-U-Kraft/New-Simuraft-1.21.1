package common.cn.kafei.simukraft.city;

import common.cn.kafei.simukraft.city.group.CityGroupMessageService;
import common.cn.kafei.simukraft.city.group.CityUserGroup;
import common.cn.kafei.simukraft.city.group.CityUserGroupService;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkSyncService;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CityPermissionInviteService {
    private static final long INVITE_TTL_MILLIS = 5L * 60L * 1000L;
    private static final ConcurrentMap<UUID, PermissionInvite> INVITES = new ConcurrentHashMap<>();

    private CityPermissionInviteService() {
    }

    // request: 创建城市权限任命邀请，并向目标玩家发送可点击聊天文本。
    public static RequestResult request(ServerLevel level, ServerPlayer operator, CityData city, ServerPlayer target, CityPermissionLevel targetPermission) {
        cleanupExpired();
        if (level == null || operator == null || city == null || target == null || targetPermission == null) {
            return RequestResult.failed(Component.translatable("message.simukraft.city_core.member_action_failed"));
        }
        Optional<CityMemberData> existingMember = city.member(target.getUUID());
        CityPermissionLevel currentPermission = existingMember.map(CityMemberData::permissionLevel).orElse(null);
        if (currentPermission == targetPermission) {
            return RequestResult.failed(Component.translatable("message.simukraft.city_core.permission_invite_already_role", target.getGameProfile().getName(), city.cityName(), permissionName(targetPermission)));
        }
        if (!canInvite(city, operator.getUUID(), existingMember.isPresent(), targetPermission)) {
            return RequestResult.failed(Component.translatable("message.simukraft.city_core.member_action_failed"));
        }
        Optional<CityData> targetCity = CityService.findPlayerCity(level, target.getUUID());
        if (targetCity.isPresent() && !targetCity.get().cityId().equals(city.cityId())) {
            return RequestResult.failed(Component.translatable("message.simukraft.city_core.target_has_city", target.getGameProfile().getName()));
        }
        removePreviousInvite(city.cityId(), target.getUUID());
        UUID inviteId = UUID.randomUUID();
        PermissionInvite invite = new PermissionInvite(
                inviteId,
                city.cityId(),
                city.cityName(),
                operator.getUUID(),
                operator.getGameProfile().getName(),
                target.getUUID(),
                target.getGameProfile().getName(),
                targetPermission,
                currentPermission,
                System.currentTimeMillis() + INVITE_TTL_MILLIS
        );
        INVITES.put(inviteId, invite);
        target.sendSystemMessage(inviteMessage(invite));
        return RequestResult.succeeded(Component.translatable("message.simukraft.city_core.permission_invite_sent", target.getGameProfile().getName(), city.cityName(), permissionName(targetPermission)));
    }

    // respond: 处理聊天栏点击后的接受或拒绝命令。
    public static boolean respond(ServerPlayer target, String rawInviteId, boolean accepted) {
        cleanupExpired();
        if (target == null) {
            return false;
        }
        UUID inviteId = parseInviteId(rawInviteId);
        if (inviteId == null) {
            InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.permission_invite_invalid"));
            return false;
        }
        PermissionInvite invite = INVITES.remove(inviteId);
        if (invite == null || invite.expired()) {
            InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.permission_invite_invalid"));
            return false;
        }
        if (!invite.targetId().equals(target.getUUID())) {
            InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.permission_invite_not_target"));
            return false;
        }
        if (!accepted) {
            notifyRejected(target, invite);
            return true;
        }
        return accept(target, invite);
    }

    // tick: 定期清理过期邀请，避免长时间运行时累积无效数据。
    public static void tick(ServerLevel level) {
        if (level != null && level.getGameTime() % 1200L == 0L) {
            cleanupExpired();
        }
    }

    // accept: 接受任命后再次通过城市服务写入目标权限。
    private static boolean accept(ServerPlayer target, PermissionInvite invite) {
        ServerLevel level = target.serverLevel();
        Optional<CityData> city = CityService.findCity(level, invite.cityId());
        if (city.isEmpty()) {
            InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.permission_invite_invalid"));
            return false;
        }
        Optional<CityData> targetCity = CityService.findPlayerCity(level, target.getUUID());
        if (targetCity.isPresent() && !targetCity.get().cityId().equals(invite.cityId())) {
            InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.target_has_city", target.getGameProfile().getName()));
            return false;
        }
        boolean changed = applyPermission(level, city.get(), invite, target);
        if (!changed) {
            InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.permission_invite_failed"));
            return false;
        }
        Collection<ServerPlayer> recipients = CityUserGroupService.onlinePlayers(level, CityUserGroup.members(invite.cityId()));
        Component message = acceptedGroupMessage(target, city.get(), invite);
        CityGroupMessageService.sendResolved(recipients, Component.translatable("toast.simukraft.title"), message, "success", ItemStack.EMPTY);
        HudSyncService.syncResolvedGroup(recipients, true);
        CityChunkSyncService.syncResolvedGroup(recipients);
        InfoToastService.success(target, Component.translatable("message.simukraft.city_core.permission_invite_accepted_self", city.get().cityName(), permissionName(invite.targetPermission())));
        return true;
    }

    // applyPermission: 根据邀请目标执行加入、调权或市长转让。
    private static boolean applyPermission(ServerLevel level, CityData city, PermissionInvite invite, ServerPlayer target) {
        if (invite.targetPermission() == CityPermissionLevel.MAYOR) {
            return CityService.transferMayor(level, invite.cityId(), invite.operatorId(), target.getUUID(), target.getGameProfile().getName());
        }
        boolean existingMember = city.member(target.getUUID()).isPresent();
        if (existingMember) {
            return CityService.setPlayerPermission(level, invite.cityId(), invite.operatorId(), target.getUUID(), invite.targetPermission());
        }
        return CityService.addPlayer(level, invite.cityId(), invite.operatorId(), target.getUUID(), target.getGameProfile().getName(), invite.targetPermission());
    }

    // acceptedGroupMessage: 生成接受后的城市用户组广播。
    private static Component acceptedGroupMessage(ServerPlayer target, CityData city, PermissionInvite invite) {
        if (invite.targetPermission() == CityPermissionLevel.MAYOR) {
            return Component.translatable("message.simukraft.city_core.mayor_transferred", target.getGameProfile().getName(), city.cityName());
        }
        if (invite.targetPermission() == CityPermissionLevel.OFFICIAL) {
            return Component.translatable("message.simukraft.city_core.official_added", target.getGameProfile().getName(), city.cityName());
        }
        if (invite.previousPermission() == CityPermissionLevel.OFFICIAL) {
            return Component.translatable("message.simukraft.city_core.official_removed", target.getGameProfile().getName(), city.cityName());
        }
        return Component.translatable("message.simukraft.city_core.member_added", target.getGameProfile().getName(), city.cityName(), permissionName(CityPermissionLevel.CITIZEN));
    }

    // notifyRejected: 拒绝任命时通知本人和在线邀请者，不修改城市数据。
    private static void notifyRejected(ServerPlayer target, PermissionInvite invite) {
        InfoToastService.warning(target, Component.translatable("message.simukraft.city_core.permission_invite_rejected_self", invite.cityName(), permissionName(invite.targetPermission())));
        ServerPlayer operator = target.getServer().getPlayerList().getPlayer(invite.operatorId());
        if (operator != null) {
            InfoToastService.warning(operator, Component.translatable("message.simukraft.city_core.permission_invite_rejected_operator", target.getGameProfile().getName(), invite.cityName(), permissionName(invite.targetPermission())));
        }
    }

    // inviteMessage: 组合聊天栏邀请正文和可点击操作文本。
    private static Component inviteMessage(PermissionInvite invite) {
        return Component.translatable("message.simukraft.city_core.permission_invite_received", invite.operatorName(), invite.cityName(), permissionName(invite.targetPermission()))
                .append(" ")
                .append(actionComponent("message.simukraft.city_core.permission_invite_accept", ChatFormatting.GREEN, acceptCommand(invite.inviteId()), "message.simukraft.city_core.permission_invite_accept_hover"))
                .append(" ")
                .append(actionComponent("message.simukraft.city_core.permission_invite_reject", ChatFormatting.RED, rejectCommand(invite.inviteId()), "message.simukraft.city_core.permission_invite_reject_hover"));
    }

    // actionComponent: 生成 RUN_COMMAND 点击文本。
    private static Component actionComponent(String labelKey, ChatFormatting color, String command, String hoverKey) {
        return Component.translatable(labelKey).withStyle(style -> style
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(hoverKey))));
    }

    // canInvite: 按目标权限判断操作者是否可发起邀请。
    private static boolean canInvite(CityData city, UUID operatorId, boolean existingMember, CityPermissionLevel targetPermission) {
        if (targetPermission == CityPermissionLevel.CITIZEN && !existingMember) {
            return city.hasPermission(operatorId, CityPermissionLevel.OFFICIAL);
        }
        return city.hasPermission(operatorId, CityPermissionLevel.MAYOR);
    }

    // permissionName: 生成权限等级的本地化显示名。
    private static Component permissionName(CityPermissionLevel permissionLevel) {
        return Component.translatable("permission.simukraft." + permissionLevel.name().toLowerCase(java.util.Locale.ROOT));
    }

    // removePreviousInvite: 同一城市对同一玩家只保留最新邀请。
    private static void removePreviousInvite(UUID cityId, UUID targetId) {
        INVITES.entrySet().removeIf(entry -> entry.getValue().cityId().equals(cityId) && entry.getValue().targetId().equals(targetId));
    }

    // cleanupExpired: 清理超过有效期的邀请。
    private static void cleanupExpired() {
        INVITES.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    // parseInviteId: 解析聊天命令传入的邀请码。
    private static UUID parseInviteId(String rawInviteId) {
        try {
            return rawInviteId == null || rawInviteId.isBlank() ? null : UUID.fromString(rawInviteId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String acceptCommand(UUID inviteId) {
        return "/simukraft city permission accept " + inviteId;
    }

    private static String rejectCommand(UUID inviteId) {
        return "/simukraft city permission reject " + inviteId;
    }

    public record RequestResult(boolean success, Component message) {
        // succeeded: 表示邀请已成功发送。
        public static RequestResult succeeded(Component message) {
            return new RequestResult(true, message);
        }

        // failed: 表示邀请创建失败并携带失败原因。
        public static RequestResult failed(Component message) {
            return new RequestResult(false, message);
        }
    }

    private record PermissionInvite(UUID inviteId, UUID cityId, String cityName, UUID operatorId,
                                    String operatorName, UUID targetId, String targetName,
                                    CityPermissionLevel targetPermission, CityPermissionLevel previousPermission,
                                    long expiresAtMillis) {
        // expired: 判断邀请是否已经超过有效期。
        private boolean expired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }
}
