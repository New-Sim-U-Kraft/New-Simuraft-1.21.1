package common.cn.kafei.simukraft.network.city.member;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.network.city.CityNetworkViewFactory;
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

@SuppressWarnings("null")
public record CityCoreMembersRequestPacket(BlockPos pos) implements CustomPacketPayload {
    public static final Type<CityCoreMembersRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "city_core_members_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityCoreMembersRequestPacket> STREAM_CODEC = StreamCodec.of(CityCoreMembersRequestPacket::encode, CityCoreMembersRequestPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CityCoreMembersRequestPacket packet) {
        buffer.writeBlockPos(packet.pos());
    }

    public static CityCoreMembersRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CityCoreMembersRequestPacket(buffer.readBlockPos());
    }

    public static void handle(CityCoreMembersRequestPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            sendMembers(level, player, packet.pos());
        }
    }

    public static void sendMembers(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!player.blockPosition().closerThan(pos, 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.too_far"));
            return;
        }
        CityCoreMembersResponsePacket response = CityNetworkViewFactory.buildMembersResponse(level, pos, player.getUUID());
        if (response != null) {
            PacketDistributor.sendToPlayer(player, response);
        } else {
            InfoToastService.warning(player, Component.translatable("message.simukraft.city_core.not_found"));
        }
    }
}
