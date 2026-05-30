package common.cn.kafei.simukraft.network.npc.state;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.citizen.CitizenService;
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

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public record EmploymentStateResponsePacket(BlockPos sourcePos, String sourceType, UUID builderCitizenId, UUID plannerCitizenId, String statusKey) implements CustomPacketPayload {
    public static final Type<EmploymentStateResponsePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "employment_state_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EmploymentStateResponsePacket> STREAM_CODEC = StreamCodec.of(EmploymentStateResponsePacket::encode, EmploymentStateResponsePacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, EmploymentStateResponsePacket packet) {
        buffer.writeBlockPos(packet.sourcePos());
        buffer.writeUtf(packet.sourceType(), 32);
        buffer.writeBoolean(packet.builderCitizenId() != null);
        if (packet.builderCitizenId() != null) {
            buffer.writeUUID(packet.builderCitizenId());
        }
        buffer.writeBoolean(packet.plannerCitizenId() != null);
        if (packet.plannerCitizenId() != null) {
            buffer.writeUUID(packet.plannerCitizenId());
        }
        buffer.writeUtf(packet.statusKey(), 64);
    }

    public static EmploymentStateResponsePacket decode(RegistryFriendlyByteBuf buffer) {
        BlockPos sourcePos = buffer.readBlockPos();
        String sourceType = buffer.readUtf(32);
        UUID builderCitizenId = buffer.readBoolean() ? buffer.readUUID() : null;
        UUID plannerCitizenId = buffer.readBoolean() ? buffer.readUUID() : null;
        String statusKey = buffer.readUtf(64);
        return new EmploymentStateResponsePacket(sourcePos, sourceType, builderCitizenId, plannerCitizenId, statusKey);
    }

    public static void handleRequest(EmploymentStateRequestPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            if (!player.blockPosition().closerThan(packet.sourcePos(), 16.0D)) {
                player.displayClientMessage(Component.translatable("message.simukraft.build_box.too_far"), true);
                return;
            }
            CitizenManager manager = CitizenManager.get(level);
            UUID builderWorkplaceId = workplaceId(packet.sourceType(), "builder", packet.sourcePos());
            UUID plannerWorkplaceId = workplaceId(packet.sourceType(), "planner", packet.sourcePos());
            Optional<CitizenData> builderCitizen = findCitizenByWorkplace(manager, builderWorkplaceId);
            Optional<CitizenData> plannerCitizen = findCitizenByWorkplace(manager, plannerWorkplaceId);
            builderCitizen.ifPresent(citizen -> backfillWorkplacePos(level, citizen, packet.sourcePos()));
            plannerCitizen.ifPresent(citizen -> backfillWorkplacePos(level, citizen, packet.sourcePos()));
            UUID builderCitizenId = builderCitizen.map(CitizenData::uuid).orElse(null);
            UUID plannerCitizenId = plannerCitizen.map(CitizenData::uuid).orElse(null);
            String statusKey = builderCitizenId != null || plannerCitizenId != null ? "gui.build_box.status_working" : "gui.build_box.status_idle";
            PacketDistributor.sendToPlayer(player, new EmploymentStateResponsePacket(packet.sourcePos(), packet.sourceType(), builderCitizenId, plannerCitizenId, statusKey));
        }
    }

    public static void handle(EmploymentStateResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> client.cn.kafei.simukraft.client.buildbox.BuildBoxScreenOpener.applyEmploymentState(packet));
    }

    public boolean hasAnyEmployee() {
        return builderCitizenId != null || plannerCitizenId != null;
    }

    private static Optional<CitizenData> findCitizenByWorkplace(CitizenManager manager, UUID workplaceId) {
        return manager.allCitizens().stream()
                .filter(data -> !data.dead())
                .filter(data -> workplaceId.equals(data.workplaceId()))
                .findFirst();
    }

    // backfillWorkplacePos：打开工作方块界面时修复旧存档缺失的岗位坐标。
    private static void backfillWorkplacePos(ServerLevel level, CitizenData citizen, BlockPos workplacePos) {
        if (citizen == null || workplacePos == null || workplacePos.equals(citizen.workplacePos())) {
            return;
        }
        citizen.setWorkplacePos(workplacePos);
        CitizenService.save(level, citizen.uuid());
    }

    private static UUID workplaceId(String sourceType, String role, BlockPos pos) {
        return UUID.nameUUIDFromBytes((sourceType + ":" + role + "@" + pos.toShortString()).getBytes(StandardCharsets.UTF_8));
    }
}
