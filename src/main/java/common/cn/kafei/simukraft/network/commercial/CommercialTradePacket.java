package common.cn.kafei.simukraft.network.commercial;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.commercial.CommercialControlBoxService;
import common.cn.kafei.simukraft.commercial.CommercialTradeAccessValidator;
import common.cn.kafei.simukraft.commercial.CommercialTradeService;
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

import java.util.UUID;

@SuppressWarnings("null")
public record CommercialTradePacket(BlockPos pos, UUID workerId, String offerId, int count, boolean quickMove) implements CustomPacketPayload {
    public static final Type<CommercialTradePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "commercial_trade"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CommercialTradePacket> STREAM_CODEC = StreamCodec.of(CommercialTradePacket::encode, CommercialTradePacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public CommercialTradePacket(BlockPos pos, UUID workerId, String offerId, int count) {
        this(pos, workerId, offerId, count, true);
    }

    /** encode: 写入玩家商业交易请求。 */
    public static void encode(RegistryFriendlyByteBuf buffer, CommercialTradePacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeUUID(packet.workerId());
        buffer.writeUtf(packet.offerId(), 128);
        buffer.writeVarInt(packet.count());
        buffer.writeBoolean(packet.quickMove());
    }

    /** decode: 读取玩家商业交易请求。 */
    public static CommercialTradePacket decode(RegistryFriendlyByteBuf buffer) {
        return new CommercialTradePacket(buffer.readBlockPos(), buffer.readUUID(), buffer.readUtf(128), buffer.readVarInt(), buffer.readBoolean());
    }

    /** handle: 在服务端执行 NPC 商业交易并刷新交易视图。 */
    public static void handle(CommercialTradePacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            if (!CommercialTradeAccessValidator.isValidWorker(level, packet.pos(), packet.workerId())
                    || !CommercialTradeAccessValidator.isTradeReachable(level, player, packet.pos(), packet.workerId())) {
                InfoToastService.warning(player, Component.translatable("message.simukraft.commercial_control_box.too_far"));
                return;
            }
            if (!level.getBlockState(packet.pos()).is(ModBlocks.COMMERCIAL_CONTROL_BOX.get())) {
                InfoToastService.warning(player, Component.translatable("message.simukraft.commercial_control_box.not_found"));
                return;
            }
            CommercialTradeService.TradeResult result = CommercialTradeService.executePlayerTrade(level, player, packet.pos(), packet.offerId(), packet.count(), packet.quickMove());
            if (result.success()) {
                InfoToastService.success(player, result.message());
                if (!result.carriedStack().isEmpty()) {
                    player.containerMenu.setCarried(result.carriedStack().copy());
                }
                player.containerMenu.broadcastChanges();
                player.inventoryMenu.broadcastChanges();
                PacketDistributor.sendToPlayer(player, CommercialTradeOpenResponsePacket.from(CommercialControlBoxService.buildTradeView(level, packet.pos(), packet.workerId())));
            } else {
                InfoToastService.warning(player, result.message());
            }
        }
    }

}
