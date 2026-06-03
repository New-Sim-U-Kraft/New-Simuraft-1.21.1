package common.cn.kafei.simukraft.network.building;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.BuildingBlockData;
import common.cn.kafei.simukraft.building.BuildingStructure;
import common.cn.kafei.simukraft.building.BuildingTaskStatus;
import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.building.BuildingStructureService;
import common.cn.kafei.simukraft.building.BuildingTerritoryValidator;
import common.cn.kafei.simukraft.building.BuildingTaskData;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.city.FinanceTransactionData;
import common.cn.kafei.simukraft.economy.EconomyService;
import common.cn.kafei.simukraft.economy.FinanceLedgerService;
import common.cn.kafei.simukraft.job.CitizenEmploymentService;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
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
            InfoToastService.warning(player, Component.translatable("message.simukraft.build_box.too_far"));
            return;
        }
        UUID citizenId = CitizenService.findAssignedCitizen(level, CitizenEmploymentService.workplaceId("build_box", "builder", packet.buildBoxPos()));
        if (citizenId == null) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.hire_npc.not_found"));
            return;
        }
        Optional<CitizenData> citizenOptional = CitizenService.findCitizen(level, citizenId);
        if (citizenOptional.isEmpty()) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.hire_npc.not_found"));
            return;
        }
        CitizenData citizen = citizenOptional.get();
        if (citizen.dead()) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.hire_npc.not_found"));
            return;
        }
        Optional<BuildingStructure> structureOptional = BuildingStructureService.loadStructure(packet.category(), packet.buildingFileName());
        if (structureOptional.isEmpty()) {
            InfoToastService.error(player, Component.translatable("message.simukraft.build_box.structure_not_found"));
            return;
        }
        BuildingStructure structure = structureOptional.get();
        UUID cityId = citizen.cityId();
        List<BuildingBlockData> placedBlocks = BuildingStructureService.resolvePlacedBlocks(structure, packet.origin(), packet.rotationDegrees());
        if (!BuildingTerritoryValidator.blockBoundsInCity(level, cityId, placedBlocks)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.construction.outside_city"));
            return;
        }
        double constructionCost = EconomyService.parseAmount(structure.amount(), "construction");
        if (constructionCost > 0.0D) {
            if (!EconomyService.canAfford(level, cityId, constructionCost) || !CityService.withdrawFunds(level, cityId, constructionCost)) {
                InfoToastService.warning(player, Component.translatable("message.simukraft.build_box.not_enough_funds", constructionCost));
                return;
            }
            FinanceLedgerService.record(level, cityId, player, -constructionCost, EconomyService.getCityBalance(level, cityId), FinanceTransactionData.Type.EXPENSE, "construction");
            HudSyncService.syncToPlayer(player, true);
        }
        BuilderConstructionService.cancelTask(level, citizen.uuid());
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
        CitizenEmploymentService.hire(level, citizen.uuid(), CityJobType.BUILDER, CitizenEmploymentService.workplaceId("build_box", "builder", packet.buildBoxPos()), packet.buildBoxPos(), CitizenWorkStatus.WORKING, structure.displayName());
        citizen.setWorkNeedDetail("build:" + task.taskId());
        citizen.setStatusLabel("建造中: " + structure.displayName());
        CitizenService.save(level, citizen.uuid());
        InfoToastService.success(player, Component.translatable("message.simukraft.build_box.construction_started", structure.displayName()));
    }

}
