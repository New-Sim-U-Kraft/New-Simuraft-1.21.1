package common.cn.kafei.simukraft.network.city.core;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.city.CityChunkManager;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityPermissionLevel;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkSyncService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public record CityCoreManageCityPacket(BlockPos pos, Action action, String value) implements CustomPacketPayload {
    public static final Type<CityCoreManageCityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_core_manage_city"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityCoreManageCityPacket> STREAM_CODEC = StreamCodec.of(CityCoreManageCityPacket::encode, CityCoreManageCityPacket::decode);
    private static final int MIN_CITY_NAME_LENGTH = 2;
    private static final int MAX_CITY_NAME_LENGTH = 20;
    private static final String CITY_NAME_PATTERN = "[a-zA-Z0-9\\u4e00-\\u9fa5\\s]+";

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CityCoreManageCityPacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeUtf(packet.action().name(), 32);
        buffer.writeUtf(packet.value(), 64);
    }

    public static CityCoreManageCityPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CityCoreManageCityPacket(buffer.readBlockPos(), Action.fromName(buffer.readUtf(32)), buffer.readUtf(64));
    }

    public static void handle(CityCoreManageCityPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            handleAction(level, player, packet);
        }
    }

    private static void handleAction(ServerLevel level, ServerPlayer player, CityCoreManageCityPacket packet) {
        if (!player.blockPosition().closerThan(packet.pos(), 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.too_far"));
            return;
        }
        Optional<CityData> city = CityService.findCityByCorePosForPlayer(level, packet.pos(), player.getUUID());
        if (city.isEmpty()) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.not_found"));
            return;
        }
        UUID cityId = city.get().cityId();
        if (packet.action() == Action.RENAME) {
            renameCity(level, player, cityId, packet.pos(), packet.value());
        } else if (packet.action() == Action.DELETE) {
            deleteCity(level, player, cityId, packet.pos(), packet.value());
        }
    }

    private static void renameCity(ServerLevel level, ServerPlayer player, UUID cityId, BlockPos pos, String rawName) {
        String cityName = normalizeCityName(rawName);
        if (!isValidCityName(cityName)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.invalid_name"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        boolean renamed = CityService.renameCity(level, cityId, player.getUUID(), cityName);
        Component message = Component.translatable(renamed ? "message.simukraft.city_core.renamed" : "message.simukraft.city_core.rename_failed", cityName);
        if (renamed) {
            InfoToastService.success(player, message);
        } else {
            InfoToastService.warning(player, message);
        }
        CityCoreOpenRequestPacket.openFor(level, player, pos);
    }

    private static void deleteCity(ServerLevel level, ServerPlayer player, UUID cityId, BlockPos pos, String confirmation) {
        Optional<CityData> city = CityService.findCity(level, cityId);
        if (city.isEmpty() || !city.get().cityName().equals(confirmation)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.delete_confirm_failed"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        boolean deleted = CityService.deleteCity(level, cityId, player.getUUID(), CityChunkManager.get(level), CityPoiManager.get(level));
        Component message = Component.translatable(deleted ? "message.simukraft.city_core.deleted" : "message.simukraft.city_core.delete_failed", confirmation);
        if (deleted) {
            InfoToastService.success(player, message);
        } else {
            InfoToastService.warning(player, message);
        }
        PacketDistributor.sendToPlayer(player, CityCoreOpenResponsePacket.from(pos, Optional.empty(), CityPermissionLevel.CITIZEN, false, false));
        if (deleted) {
            CityChunkSyncService.syncToAll(level);
        }
    }

    private static String normalizeCityName(String rawCityName) {
        return rawCityName == null ? "" : rawCityName.trim();
    }

    private static boolean isValidCityName(String cityName) {
        return cityName.length() >= MIN_CITY_NAME_LENGTH
                && cityName.length() <= MAX_CITY_NAME_LENGTH
                && cityName.matches(CITY_NAME_PATTERN)
                && !cityName.matches(".*\\s{2,}.*");
    }

    public enum Action {
        RENAME,
        DELETE;

        public static Action fromName(String name) {
            if (name == null || name.isBlank()) {
                return RENAME;
            }
            for (Action action : values()) {
                if (action.name().equalsIgnoreCase(name)) {
                    return action;
                }
            }
            return RENAME;
        }
    }
}
