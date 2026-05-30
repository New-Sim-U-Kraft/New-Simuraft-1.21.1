package common.cn.kafei.simukraft.network.city.chunk;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.city.CityClaimService;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.network.city.map.CityCoreMapRequestPacket;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record CityChunkPurchasePacket(BlockPos pos, int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<CityChunkPurchasePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_chunk_purchase"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityChunkPurchasePacket> STREAM_CODEC = StreamCodec.of(CityChunkPurchasePacket::encode, CityChunkPurchasePacket::decode);

    public static void encode(RegistryFriendlyByteBuf buffer, CityChunkPurchasePacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeVarInt(packet.chunkX());
        buffer.writeVarInt(packet.chunkZ());
    }

    public static CityChunkPurchasePacket decode(RegistryFriendlyByteBuf buffer) {
        return new CityChunkPurchasePacket(buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(CityChunkPurchasePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!player.blockPosition().closerThan(packet.pos(), 8.0D)) {
            return;
        }
        CityService.findCityByCorePos(level, packet.pos()).ifPresent(city -> {
            CityClaimService.ClaimResult result = CityClaimService.buyChunk(level, player, city, packet.chunkX(), packet.chunkZ());
            if (result.success()) {
                InfoToastService.success(player, result.message());
            } else {
                InfoToastService.warning(player, result.message());
            }
            CityCoreMapRequestPacket.sendMap(level, player, packet.pos());
            if (result.success()) {
                CityChunkSyncService.syncToAll(level);
            }
            HudSyncService.syncToPlayer(player, true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
