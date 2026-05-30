package common.cn.kafei.simukraft.network.building.controlbox;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.controlbox.ResidentialControlBoxService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import common.cn.kafei.simukraft.registry.ModBlocks;
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
public record ResidentialControlBoxOpenRequestPacket(BlockPos pos) implements CustomPacketPayload {
    public static final Type<ResidentialControlBoxOpenRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "residential_control_box_open_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentialControlBoxOpenRequestPacket> STREAM_CODEC = StreamCodec.of(ResidentialControlBoxOpenRequestPacket::encode, ResidentialControlBoxOpenRequestPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, ResidentialControlBoxOpenRequestPacket packet) {
        buffer.writeBlockPos(packet.pos());
    }

    public static ResidentialControlBoxOpenRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new ResidentialControlBoxOpenRequestPacket(buffer.readBlockPos());
    }

    public static void handle(ResidentialControlBoxOpenRequestPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            openFor(level, player, packet.pos());
        }
    }

    public static void openFor(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!player.blockPosition().closerThan(pos, 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.residential_control_box.too_far"));
            return;
        }
        if (!level.getBlockState(pos).is(ModBlocks.RESIDENTIAL_CONTROL_BOX.get())) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.residential_control_box.not_found"));
            return;
        }
        PacketDistributor.sendToPlayer(player, ResidentialControlBoxOpenResponsePacket.from(ResidentialControlBoxService.buildView(level, pos)));
    }
}
