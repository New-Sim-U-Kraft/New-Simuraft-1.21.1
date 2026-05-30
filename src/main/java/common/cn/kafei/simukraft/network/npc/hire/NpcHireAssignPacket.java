package common.cn.kafei.simukraft.network.npc.hire;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.job.CityJobMobilityService;
import common.cn.kafei.simukraft.job.CityJobType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public record NpcHireAssignPacket(BlockPos sourcePos, String sourceType, String role, UUID citizenId) implements CustomPacketPayload {
    public static final Type<NpcHireAssignPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "npc_hire_assign"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcHireAssignPacket> STREAM_CODEC = StreamCodec.of(NpcHireAssignPacket::encode, NpcHireAssignPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, NpcHireAssignPacket packet) {
        buffer.writeBlockPos(packet.sourcePos());
        buffer.writeUtf(packet.sourceType(), 32);
        buffer.writeUtf(packet.role(), 32);
        buffer.writeUUID(packet.citizenId());
    }

    public static NpcHireAssignPacket decode(RegistryFriendlyByteBuf buffer) {
        return new NpcHireAssignPacket(buffer.readBlockPos(), buffer.readUtf(32), buffer.readUtf(32), buffer.readUUID());
    }

    public static void handle(NpcHireAssignPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            if (!player.blockPosition().closerThan(packet.sourcePos(), 16.0D)) {
                player.displayClientMessage(Component.translatable("message.simukraft.build_box.too_far"), true);
                return;
            }
            Optional<CitizenData> citizenOptional = CitizenService.findCitizen(level, packet.citizenId());
            if (citizenOptional.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.simukraft.hire_npc.not_found"), true);
                return;
            }
            CitizenData citizen = citizenOptional.get();
            if (!CitizenService.isHireable(citizen)) {
                player.displayClientMessage(Component.translatable("message.simukraft.hire_npc.unavailable", citizen.name()), true);
                return;
            }
            CityJobType jobType = CityJobMobilityService.resolveHireRole(packet.role());
            UUID workplaceId = UUID.nameUUIDFromBytes((packet.sourceType() + ":" + packet.role() + "@" + packet.sourcePos().toShortString()).getBytes(StandardCharsets.UTF_8));
            CitizenService.applyEmployment(level, citizen.uuid(), jobType, workplaceId, packet.sourcePos(), "");
            CityJobMobilityService.teleportCitizenToWorkplace(level, citizen.uuid(), packet.sourcePos(), jobType, CitizenWorkStatus.WORKING, "");
            player.displayClientMessage(Component.translatable("message.simukraft.hire_npc.success", citizen.name()), true);
        }
    }
}
