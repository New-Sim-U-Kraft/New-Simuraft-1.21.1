package common.cn.kafei.simukraft.network.city.core;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.network.city.CityNetworkViewFactory;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record CityCoreOpenRequestPacket(BlockPos pos) implements CustomPacketPayload {
    public static final Type<CityCoreOpenRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_core_open_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityCoreOpenRequestPacket> STREAM_CODEC = StreamCodec.of(CityCoreOpenRequestPacket::encode, CityCoreOpenRequestPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CityCoreOpenRequestPacket packet) {
        buffer.writeBlockPos(packet.pos());
    }

    public static CityCoreOpenRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CityCoreOpenRequestPacket(buffer.readBlockPos());
    }

    public static void handle(CityCoreOpenRequestPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            openFor(level, player, packet.pos());
        }
    }

    public static void openFor(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!player.blockPosition().closerThan(pos, 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.too_far"));
            return;
        }
        PacketDistributor.sendToPlayer(player, CityNetworkViewFactory.buildOpenResponse(level, pos, player.getUUID()));
    }
}
