package common.cn.kafei.simukraft.network.planner;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.city.FinanceTransactionData;
import common.cn.kafei.simukraft.city.group.CityGroupMessageService;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.economy.EconomyService;
import common.cn.kafei.simukraft.economy.FinanceLedgerService;
import common.cn.kafei.simukraft.job.CitizenEmploymentService;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import common.cn.kafei.simukraft.planner.PlanOperation;
import common.cn.kafei.simukraft.planner.PlannerWorkService;
import common.cn.kafei.simukraft.planner.PlanningTaskData;
import common.cn.kafei.simukraft.planner.PlanningTaskStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("null")
public record CreatePlanningTaskPacket(BlockPos buildBoxPos,
                                       BlockPos min,
                                       BlockPos max,
                                       PlanOperation operation,
                                       String fillBlockId,
                                       String sourceBlockId,
                                       @Nullable BlockPos materialChestPos,
                                       Map<String, String> replacementMap) implements CustomPacketPayload {
    public static final Type<CreatePlanningTaskPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "create_planning_task"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreatePlanningTaskPacket> STREAM_CODEC = StreamCodec.of(CreatePlanningTaskPacket::encode, CreatePlanningTaskPacket::decode);
    private static final int MAX_BLOCK_ID_LENGTH = 128;
    private static final int MAX_REPLACEMENT_MAPPINGS = 256;

    public CreatePlanningTaskPacket(BlockPos buildBoxPos,
                                    BlockPos min,
                                    BlockPos max,
                                    PlanOperation operation,
                                    String fillBlockId,
                                    String sourceBlockId) {
        this(buildBoxPos, min, max, operation, fillBlockId, sourceBlockId, null, Map.of());
    }

    public CreatePlanningTaskPacket {
        buildBoxPos = buildBoxPos.immutable();
        min = min.immutable();
        max = max.immutable();
        fillBlockId = safeString(fillBlockId);
        sourceBlockId = safeString(sourceBlockId);
        materialChestPos = materialChestPos != null ? materialChestPos.immutable() : null;
        replacementMap = immutableReplacementMap(replacementMap);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CreatePlanningTaskPacket packet) {
        buffer.writeBlockPos(packet.buildBoxPos());
        buffer.writeBlockPos(packet.min());
        buffer.writeBlockPos(packet.max());
        buffer.writeEnum(packet.operation());
        buffer.writeUtf(packet.fillBlockId(), MAX_BLOCK_ID_LENGTH);
        buffer.writeUtf(packet.sourceBlockId(), MAX_BLOCK_ID_LENGTH);
        buffer.writeBoolean(packet.materialChestPos() != null);
        if (packet.materialChestPos() != null) {
            buffer.writeBlockPos(packet.materialChestPos());
        }
        writeReplacementMap(buffer, packet.replacementMap());
    }

    public static CreatePlanningTaskPacket decode(RegistryFriendlyByteBuf buffer) {
        BlockPos buildBoxPos = buffer.readBlockPos();
        BlockPos min = buffer.readBlockPos();
        BlockPos max = buffer.readBlockPos();
        PlanOperation operation = buffer.readEnum(PlanOperation.class);
        String fillBlockId = buffer.readUtf(MAX_BLOCK_ID_LENGTH);
        String sourceBlockId = buffer.readUtf(MAX_BLOCK_ID_LENGTH);
        BlockPos materialChestPos = buffer.readBoolean() ? buffer.readBlockPos() : null;
        return new CreatePlanningTaskPacket(buildBoxPos, min, max, operation, fillBlockId, sourceBlockId, materialChestPos, readReplacementMap(buffer));
    }

    public static void handle(CreatePlanningTaskPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos boxPos = packet.buildBoxPos();
        PlannerNetworkValidation.PlannerContext plannerContext = PlannerNetworkValidation.validatePlanner(player, level, boxPos);
        if (plannerContext == null) {
            return;
        }

        BlockPos min = PlannerNetworkValidation.min(packet.min(), packet.max());
        BlockPos max = PlannerNetworkValidation.max(packet.min(), packet.max());
        int volume = PlanningTaskData.volume(min, max);
        if (volume <= 0 || volume > ServerConfig.plannerMaxVolume() || !PlannerNetworkValidation.selectionNearBuildBox(boxPos, min, max)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.too_big", ServerConfig.plannerMaxVolume()));
            return;
        }

        BlockPos materialChest = null;
        Map<String, String> effectiveReplacementMap = effectiveReplacementMap(packet);
        if (packet.operation().needsFillBlock()) {
            materialChest = PlannerNetworkValidation.validateAdjacentContainer(level, boxPos, packet.materialChestPos());
            if (materialChest == null) {
                InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.invalid_material_container"));
                return;
            }
        } else if (packet.materialChestPos() != null) {
            materialChest = PlannerNetworkValidation.validateAdjacentContainer(level, boxPos, packet.materialChestPos());
        }

        if (packet.operation() == PlanOperation.FILL && invalidBlock(packet.fillBlockId())) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.invalid_block"));
            return;
        }
        if (packet.operation() == PlanOperation.REPLACE) {
            if (effectiveReplacementMap.isEmpty()) {
                InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.replacement_map_empty"));
                return;
            }
            for (Map.Entry<String, String> entry : effectiveReplacementMap.entrySet()) {
                if (invalidBlock(entry.getKey()) || invalidBlock(entry.getValue())) {
                    InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.invalid_block"));
                    return;
                }
            }
        }

        UUID cityId = plannerContext.cityId();
        long now = System.currentTimeMillis();
        String sourceBlockId = packet.sourceBlockId();
        String fillBlockId = packet.fillBlockId();
        if (packet.operation() == PlanOperation.REPLACE && !effectiveReplacementMap.isEmpty()) {
            Map.Entry<String, String> firstMapping = effectiveReplacementMap.entrySet().iterator().next();
            sourceBlockId = firstMapping.getKey();
            fillBlockId = firstMapping.getValue();
        }
        PlanningTaskData draftTask = new PlanningTaskData(
                UUID.randomUUID(),
                plannerContext.planner().uuid(),
                cityId,
                level.dimension().location().toString(),
                boxPos,
                min,
                max,
                packet.operation(),
                fillBlockId,
                sourceBlockId,
                materialChest,
                effectiveReplacementMap,
                0,
                volume,
                0,
                volume,
                PlanningTaskStatus.QUEUED.id(),
                now,
                now);
        int targetBlocks = PlannerWorkService.countTargetBlocks(level, draftTask);
        if (targetBlocks <= 0) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.no_targets"));
            return;
        }

        double cost = EconomyService.normalizeAmount(targetBlocks * ServerConfig.plannerMoneyPerBlock(packet.operation()));
        if (cost > 0.0D) {
            if (!EconomyService.canAfford(level, cityId, cost) || !CityService.withdrawFunds(level, cityId, cost)) {
                InfoToastService.warning(player, Component.translatable("message.simukraft.plan_area.not_enough_funds", cost));
                return;
            }
            FinanceLedgerService.record(level, cityId, player, -cost, EconomyService.getCityBalance(level, cityId), FinanceTransactionData.Type.EXPENSE, "planner");
            HudSyncService.syncToCityGroup(level, cityId, true);
        }

        var planner = plannerContext.planner();
        PlannerWorkService.cancelTask(level, planner.uuid());
        PlanningTaskData task = draftTask.withTargetBlocks(targetBlocks);
        PlannerWorkService.startTask(level, task);
        CitizenEmploymentService.hire(level, planner.uuid(), CityJobType.PLANNER, PlannerNetworkValidation.workplaceId(boxPos), boxPos, CitizenWorkStatus.WORKING, "");
        CityGroupMessageService.successToCity(level, cityId, Component.translatable("message.simukraft.plan_area.started", Component.translatable(packet.operation().translationKey()), targetBlocks));
    }

    private static Map<String, String> effectiveReplacementMap(CreatePlanningTaskPacket packet) {
        if (packet.operation() != PlanOperation.REPLACE) {
            return Map.of();
        }
        if (!packet.replacementMap().isEmpty()) {
            return packet.replacementMap();
        }
        if (!packet.sourceBlockId().isBlank() && !packet.fillBlockId().isBlank()) {
            return Map.of(packet.sourceBlockId(), packet.fillBlockId());
        }
        return Map.of();
    }

    private static boolean invalidBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return true;
        }
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        return id == null || !BuiltInRegistries.BLOCK.containsKey(id);
    }

    private static void writeReplacementMap(RegistryFriendlyByteBuf buffer, Map<String, String> map) {
        Map<String, String> safe = immutableReplacementMap(map);
        buffer.writeVarInt(safe.size());
        safe.forEach((source, target) -> {
            buffer.writeUtf(source, MAX_BLOCK_ID_LENGTH);
            buffer.writeUtf(target, MAX_BLOCK_ID_LENGTH);
        });
    }

    private static Map<String, String> readReplacementMap(RegistryFriendlyByteBuf buffer) {
        int size = Math.min(MAX_REPLACEMENT_MAPPINGS, buffer.readVarInt());
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            map.put(buffer.readUtf(MAX_BLOCK_ID_LENGTH), buffer.readUtf(MAX_BLOCK_ID_LENGTH));
        }
        return immutableReplacementMap(map);
    }

    private static Map<String, String> immutableReplacementMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((source, target) -> {
            if (copy.size() < MAX_REPLACEMENT_MAPPINGS && source != null && target != null && !source.isBlank() && !target.isBlank()) {
                copy.put(source, target);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }
}
