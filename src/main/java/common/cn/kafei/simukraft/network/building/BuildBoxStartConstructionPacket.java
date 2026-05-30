package common.cn.kafei.simukraft.network.building;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.BuildingStructure;
import common.cn.kafei.simukraft.building.BuildingTaskStatus;
import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.building.BuildingStructureService;
import common.cn.kafei.simukraft.building.BuildingTaskData;
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

import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public record BuildBoxStartConstructionPacket(BlockPos buildBoxPos,
                                              String category,
                                              String buildingFileName,
                                              BlockPos origin,
                                              int rotationDegrees) implements CustomPacketPayload {
    public static final Type<BuildBoxStartConstructionPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "build_box_start_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildBoxStartConstructionPacket> STREAM_CODEC = StreamCodec.of(BuildBoxStartConstructionPacket::encode, BuildBoxStartConstructionPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, BuildBoxStartConstructionPacket packet) {
        buffer.writeBlockPos(packet.buildBoxPos());
        buffer.writeUtf(packet.category(), 32);
        buffer.writeUtf(packet.buildingFileName(), 128);
        buffer.writeBlockPos(packet.origin());
        buffer.writeInt(packet.rotationDegrees());
    }

    public static BuildBoxStartConstructionPacket decode(RegistryFriendlyByteBuf buffer) {
        return new BuildBoxStartConstructionPacket(buffer.readBlockPos(), buffer.readUtf(32), buffer.readUtf(128), buffer.readBlockPos(), buffer.readInt());
    }

    public static void handle(BuildBoxStartConstructionPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!player.blockPosition().closerThan(packet.buildBoxPos(), 24.0D)) {
            player.displayClientMessage(Component.translatable("message.simukraft.build_box.too_far"), true);
            return;
        }
        UUID citizenId = CitizenService.findAssignedCitizen(level, workplaceId(packet.buildBoxPos(), "builder"));
        if (citizenId == null) {
            player.displayClientMessage(Component.translatable("message.simukraft.hire_npc.not_found"), true);
            return;
        }
        Optional<CitizenData> citizenOptional = CitizenService.findCitizen(level, citizenId);
        if (citizenOptional.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.simukraft.hire_npc.not_found"), true);
            return;
        }
        CitizenData citizen = citizenOptional.get();
        if (citizen.dead()) {
            player.displayClientMessage(Component.translatable("message.simukraft.hire_npc.not_found"), true);
            return;
        }
        BuilderConstructionService.cancelTask(level, citizen.uuid());
        Optional<BuildingStructure> structureOptional = BuildingStructureService.loadStructure(packet.category(), packet.buildingFileName());
        if (structureOptional.isEmpty()) {
            player.displayClientMessage(Component.literal("未找到建筑结构或蓝图解析失败"), true);
            return;
        }
        BuildingStructure structure = structureOptional.get();
        long now = System.currentTimeMillis();
        BuildingTaskData task = new BuildingTaskData(
                UUID.randomUUID(),
                citizen.uuid(),
                citizen.cityId(),
                level.dimension().location().toString(),
                packet.buildBoxPos(),
                packet.category(),
                packet.buildingFileName(),
                structure.displayName(),
                structure.amount(),
                structure.structureFileName(),
                packet.origin(),
                packet.rotationDegrees(),
                0,
                structure.blockCount(),
                BuildingTaskStatus.QUEUED.id(),
                now,
                now,
                structure.poiDefinitions()
        );
        BuilderConstructionService.startTask(level, task);
        CitizenService.applyEmployment(level, citizen.uuid(), CityJobType.BUILDER, workplaceId(packet.buildBoxPos(), "builder"), packet.buildBoxPos(), structure.displayName());
        citizen.setWorkNeedDetail("build:" + task.taskId());
        citizen.setStatusLabel("建造中: " + structure.displayName());
        CitizenService.save(level, citizen.uuid());
        CityJobMobilityService.teleportCitizenToWorkplace(level, citizen.uuid(), packet.buildBoxPos(), CityJobType.BUILDER, CitizenWorkStatus.WORKING, structure.displayName());
        player.displayClientMessage(Component.literal("已开始建造: " + structure.displayName()), false);
    }

    private static UUID workplaceId(BlockPos pos, String role) {
        return UUID.nameUUIDFromBytes(("build_box:" + role + "@" + pos.toShortString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
