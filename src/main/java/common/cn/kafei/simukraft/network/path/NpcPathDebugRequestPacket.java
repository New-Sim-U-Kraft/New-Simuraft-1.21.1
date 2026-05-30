package common.cn.kafei.simukraft.network.path;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record NpcPathDebugRequestPacket(boolean visible) implements CustomPacketPayload {
    public static final Type<NpcPathDebugRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "npc_path_debug_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcPathDebugRequestPacket> STREAM_CODEC = StreamCodec.of(NpcPathDebugRequestPacket::encode, NpcPathDebugRequestPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, NpcPathDebugRequestPacket packet) {
        buffer.writeBoolean(packet.visible());
    }

    public static NpcPathDebugRequestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new NpcPathDebugRequestPacket(buffer.readBoolean());
    }

    public static void handle(NpcPathDebugRequestPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level && packet.visible()) {
            CitizenNavigationService.syncDebugPaths(level, player);
        }
    }
}
