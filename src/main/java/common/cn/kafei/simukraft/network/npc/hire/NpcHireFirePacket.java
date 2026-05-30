package common.cn.kafei.simukraft.network.npc.hire;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.job.CityJobMobilityService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public record NpcHireFirePacket(BlockPos sourcePos, String sourceType, String role, UUID citizenId) implements CustomPacketPayload {
    public static final Type<NpcHireFirePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "npc_hire_fire"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcHireFirePacket> STREAM_CODEC = StreamCodec.of(NpcHireFirePacket::encode, NpcHireFirePacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, NpcHireFirePacket packet) {
        buffer.writeBlockPos(packet.sourcePos());
        buffer.writeUtf(packet.sourceType(), 32);
        buffer.writeUtf(packet.role(), 32);
        buffer.writeUUID(packet.citizenId());
    }

    public static NpcHireFirePacket decode(RegistryFriendlyByteBuf buffer) {
        return new NpcHireFirePacket(buffer.readBlockPos(), buffer.readUtf(32), buffer.readUtf(32), buffer.readUUID());
    }

    public static void handle(NpcHireFirePacket packet, IPayloadContext context) {
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
            if (citizen.dead()) {
                player.displayClientMessage(Component.translatable("message.simukraft.fire_npc.unavailable", citizen.name()), true);
                return;
            }
            if (citizen.child()) {
                player.displayClientMessage(Component.translatable("message.simukraft.fire_npc.unavailable", citizen.name()), true);
                return;
            }
            if ("build_box".equalsIgnoreCase(packet.sourceType()) && "builder".equalsIgnoreCase(packet.role())) {
                BuilderConstructionService.interruptTask(level, citizen.uuid(), "builder_fired");
            }
            CitizenService.clearEmployment(level, citizen.uuid());
            CityJobMobilityService.resetCitizenAfterFire(level, citizen.uuid());
            player.displayClientMessage(Component.translatable("message.simukraft.fire_npc.success", citizen.name()), true);
        }
    }
}
