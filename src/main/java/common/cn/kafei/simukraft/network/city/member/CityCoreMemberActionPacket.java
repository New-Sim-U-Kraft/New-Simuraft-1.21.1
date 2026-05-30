package common.cn.kafei.simukraft.network.city.member;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityPermissionLevel;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.network.city.core.CityCoreOpenRequestPacket;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public record CityCoreMemberActionPacket(BlockPos pos, Action action, UUID targetId, String targetName, CityPermissionLevel permissionLevel) implements CustomPacketPayload {
    public static final Type<CityCoreMemberActionPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_core_member_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityCoreMemberActionPacket> STREAM_CODEC = StreamCodec.of(CityCoreMemberActionPacket::encode, CityCoreMemberActionPacket::decode);
    public static final UUID EMPTY_PLAYER_ID = new UUID(0L, 0L);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CityCoreMemberActionPacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeUtf(packet.action().name(), 32);
        buffer.writeUUID(packet.targetId());
        buffer.writeUtf(packet.targetName(), 64);
        buffer.writeUtf(packet.permissionLevel().name(), 16);
    }

    public static CityCoreMemberActionPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CityCoreMemberActionPacket(buffer.readBlockPos(), Action.fromName(buffer.readUtf(32)), buffer.readUUID(), buffer.readUtf(64), CityPermissionLevel.fromName(buffer.readUtf(16)));
    }

    public static void handle(CityCoreMemberActionPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            handleAction(level, player, packet);
        }
    }

    private static void handleAction(ServerLevel level, ServerPlayer player, CityCoreMemberActionPacket packet) {
        if (!player.blockPosition().closerThan(packet.pos(), 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.too_far"));
            return;
        }
        Optional<CityData> city = CityService.findCityByCorePosForPlayer(level, packet.pos(), player.getUUID());
        if (city.isEmpty()) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.not_found"));
            return;
        }
        boolean success = switch (packet.action()) {
            case ADD -> addOnlinePlayer(level, player, city.get().cityId(), packet.targetName(), packet.permissionLevel());
            case REMOVE -> CityService.removePlayer(level, city.get().cityId(), player.getUUID(), packet.targetId());
            case SET_PERMISSION -> CityService.setPlayerPermission(level, city.get().cityId(), player.getUUID(), packet.targetId(), packet.permissionLevel());
        };
        Component message = Component.translatable(success ? "message.simukraft.city_core.member_action_success" : "message.simukraft.city_core.member_action_failed");
        if (success) {
            InfoToastService.success(player, message);
        } else {
            InfoToastService.warning(player, message);
        }
        CityCoreMembersRequestPacket.sendMembers(level, player, packet.pos());
        CityCoreOpenRequestPacket.openFor(level, player, packet.pos());
    }

    private static boolean addOnlinePlayer(ServerLevel level, ServerPlayer operator, UUID cityId, String rawTargetName, CityPermissionLevel permissionLevel) {
        if (permissionLevel == CityPermissionLevel.MAYOR) {
            return false;
        }
        String targetName = rawTargetName == null ? "" : rawTargetName.trim();
        if (targetName.isBlank()) {
            return false;
        }
        ServerPlayer target = level.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            InfoToastService.warning(operator, Component.translatable("message.simukraft.city_core.player_not_online", targetName));
            return false;
        }
        return CityService.addPlayer(level, cityId, operator.getUUID(), target.getUUID(), target.getGameProfile().getName(), permissionLevel);
    }

    public enum Action {
        ADD,
        REMOVE,
        SET_PERMISSION;

        public static Action fromName(String name) {
            if (name == null || name.isBlank()) {
                return ADD;
            }
            for (Action action : values()) {
                if (action.name().equalsIgnoreCase(name)) {
                    return action;
                }
            }
            return ADD;
        }
    }
}
