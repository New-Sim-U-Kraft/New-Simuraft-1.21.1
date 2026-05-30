package common.cn.kafei.simukraft.network.building.controlbox;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.building.controlbox.ResidentialControlBoxService;
import common.cn.kafei.simukraft.citizen.CitizenHousingService;
import common.cn.kafei.simukraft.config.ServerConfig;
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
public record ResidentialControlBoxOccupancyPacket(BlockPos pos, Action action) implements CustomPacketPayload {
    public static final Type<ResidentialControlBoxOccupancyPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "residential_control_box_occupancy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentialControlBoxOccupancyPacket> STREAM_CODEC = StreamCodec.of(ResidentialControlBoxOccupancyPacket::encode, ResidentialControlBoxOccupancyPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, ResidentialControlBoxOccupancyPacket packet) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeUtf(packet.action().name(), 24);
    }

    public static ResidentialControlBoxOccupancyPacket decode(RegistryFriendlyByteBuf buffer) {
        return new ResidentialControlBoxOccupancyPacket(buffer.readBlockPos(), Action.fromName(buffer.readUtf(24)));
    }

    public static void handle(ResidentialControlBoxOccupancyPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            handleFor(level, player, packet);
        }
    }

    private static void handleFor(ServerLevel level, ServerPlayer player, ResidentialControlBoxOccupancyPacket packet) {
        if (!player.blockPosition().closerThan(packet.pos(), 8.0D)) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.residential_control_box.too_far"));
            return;
        }
        if (!level.getBlockState(packet.pos()).is(ModBlocks.RESIDENTIAL_CONTROL_BOX.get())) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.residential_control_box.not_found"));
            return;
        }
        PlacedBuildingRecord building = ResidentialControlBoxService.findBuilding(level, packet.pos());
        if (building == null || building.cityId() == null) {
            InfoToastService.warning(player, Component.translatable("message.simukraft.residential_control_box.no_building"));
            return;
        }
        int changed = switch (packet.action()) {
            case ASSIGN_EXISTING -> CitizenHousingService.fillVacantHomes(level, building.cityId());
            case SPAWN_NEW -> CitizenHousingService.spawnCitizensForVacantHomes(level, building.cityId(), building.worldOrigin(), ServerConfig.populationGrowthMaxPerInterval());
        };
        InfoToastService.success(player, Component.translatable(packet.action().messageKey(), changed));
        PacketDistributor.sendToPlayer(player, ResidentialControlBoxOpenResponsePacket.from(ResidentialControlBoxService.buildView(level, packet.pos())));
    }

    public enum Action {
        ASSIGN_EXISTING("message.simukraft.residential_control_box.assigned_existing"),
        SPAWN_NEW("message.simukraft.residential_control_box.spawned_new");

        private final String messageKey;

        Action(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }

        public static Action fromName(String name) {
            for (Action action : values()) {
                if (action.name().equalsIgnoreCase(name)) {
                    return action;
                }
            }
            return ASSIGN_EXISTING;
        }
    }
}
