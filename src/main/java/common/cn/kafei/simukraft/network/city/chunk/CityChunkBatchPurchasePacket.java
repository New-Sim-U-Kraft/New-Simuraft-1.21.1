package common.cn.kafei.simukraft.network.city.chunk;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.city.CityClaimService;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.city.group.CityGroupMessageService;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.network.city.core.CityCoreAccessValidator;
import common.cn.kafei.simukraft.network.city.map.CityCoreMapRequestPacket;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
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

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("null")
public record CityChunkBatchPurchasePacket(BlockPos pos, List<ChunkEntry> chunks) implements CustomPacketPayload {
    private static final int MAX_CHUNKS = 256;
    public static final Type<CityChunkBatchPurchasePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_chunk_batch_purchase"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityChunkBatchPurchasePacket> STREAM_CODEC = StreamCodec.of(CityChunkBatchPurchasePacket::encode, CityChunkBatchPurchasePacket::decode);

    public CityChunkBatchPurchasePacket {
        chunks = chunks == null ? List.of() : List.copyOf(chunks.size() > MAX_CHUNKS ? chunks.subList(0, MAX_CHUNKS) : chunks);
    }

    // encode：写入中键批量购买区块请求。
    public static void encode(RegistryFriendlyByteBuf buffer, CityChunkBatchPurchasePacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeVarInt(packet.chunks().size());
        for (ChunkEntry chunk : packet.chunks()) {
            buffer.writeVarInt(chunk.chunkX());
            buffer.writeVarInt(chunk.chunkZ());
        }
    }

    // decode：读取中键批量购买区块请求，并限制最大数量避免恶意大包。
    public static CityChunkBatchPurchasePacket decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int encodedSize = buffer.readVarInt();
        if (encodedSize < 0 || encodedSize > MAX_CHUNKS) {
            throw new IllegalArgumentException("Invalid city chunk batch purchase size: " + encodedSize);
        }
        List<ChunkEntry> chunks = new ArrayList<>(encodedSize);
        for (int index = 0; index < encodedSize; index++) {
            int chunkX = buffer.readVarInt();
            int chunkZ = buffer.readVarInt();
            chunks.add(new ChunkEntry(chunkX, chunkZ));
        }
        return new CityChunkBatchPurchasePacket(pos, chunks);
    }

    // handle：服务端顺序购买多个区块，并只发送一次汇总提示。
    public static void handle(CityChunkBatchPurchasePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!CityCoreAccessValidator.canAccess(level, player, packet.pos())) {
            return;
        }
        CityService.findCityByCorePos(level, packet.pos()).ifPresent(city -> {
            int purchased = 0;
            int failed = 0;
            Component firstFailure = null;
            for (ChunkEntry chunk : packet.chunks()) {
                CityClaimService.ClaimResult result = CityClaimService.buyChunk(level, player, city, chunk.chunkX(), chunk.chunkZ());
                if (result.success()) {
                    purchased++;
                } else {
                    failed++;
                    if (firstFailure == null) {
                        firstFailure = result.message();
                    }
                }
            }
            if (purchased > 0) {
                double totalCost = ServerConfig.cityChunkPrice() * purchased;
                Component message = failed > 0
                        ? Component.translatable("message.simukraft.city_chunk.batch_claimed_partial", purchased, failed, totalCost)
                        : Component.translatable("message.simukraft.city_chunk.batch_claimed", purchased, totalCost);
                CityGroupMessageService.successToCity(level, city.cityId(), message);
                CityChunkSyncService.syncToAll(level);
                HudSyncService.syncToCityGroup(level, city.cityId(), true);
            } else {
                InfoToastService.warning(player, firstFailure != null ? firstFailure : Component.translatable("message.simukraft.city_chunk.claim_failed"));
            }
            CityCoreMapRequestPacket.sendMap(level, player, packet.pos());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record ChunkEntry(int chunkX, int chunkZ) {
    }
}
