package common.cn.kafei.simukraft.network.city.core;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.city.CityChunkManager;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.network.city.CityNetworkViewFactory;
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
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record CityCoreCreateCityPacket(BlockPos pos, String cityName) implements CustomPacketPayload {
    public static final Type<CityCoreCreateCityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_core_create_city"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityCoreCreateCityPacket> STREAM_CODEC = StreamCodec.of(CityCoreCreateCityPacket::encode, CityCoreCreateCityPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CityCoreCreateCityPacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeUtf(packet.cityName(), 64);
    }

    public static CityCoreCreateCityPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CityCoreCreateCityPacket(buffer.readBlockPos(), buffer.readUtf(64));
    }

    public static void handle(CityCoreCreateCityPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            createCity(level, player, packet.pos(), packet.cityName());
        }
    }

    private static void createCity(ServerLevel level, ServerPlayer player, BlockPos pos, String rawCityName) {
        if (!player.blockPosition().closerThan(pos, 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.too_far"));
            return;
        }
        if (CityService.hasCityAtCorePos(level, pos)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.already_bound"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        if (CityService.findPlayerCity(level, player.getUUID()).isPresent()) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.player_has_city"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        String cityName = CityService.normalizeCityName(rawCityName);
        if (!CityService.isValidCityName(cityName)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.invalid_name"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        if (CityService.hasCityNamed(level, cityName)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.name_exists"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        CityChunkManager chunkManager = CityChunkManager.get(level);
        ChunkPos centerChunk = new ChunkPos(pos);
        if (!chunkManager.isAreaAvailable(centerChunk)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.chunks_occupied"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        CityData city = CityService.createCity(level, cityName, player.getUUID(), player.getGameProfile().getName(), pos);
        if (!chunkManager.assignInitialArea(city.cityId(), centerChunk)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.chunks_occupied"));
            CityCoreOpenRequestPacket.openFor(level, player, pos);
            return;
        }
        CitizenService.spawnCitizen(level, pos.above(), city.cityId(), true);
        InfoToastService.success(player, Component.translatable("message.simukraft.city_core.created", city.cityName()));
        InfoToastService.send(player, Component.translatable("message.simukraft.city_core.initial_chunks_claimed"));
        PacketDistributor.sendToPlayer(player, CityNetworkViewFactory.buildCreatedCityResponse(level, pos, city, player.getUUID()));
        CityChunkSyncService.syncToAll(level);
    }
}
