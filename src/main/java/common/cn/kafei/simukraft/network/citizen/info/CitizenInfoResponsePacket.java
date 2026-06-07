package common.cn.kafei.simukraft.network.citizen.info;

import common.cn.kafei.simukraft.network.clientbound.ClientboundNetworkBridge;
import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenSelfFeedingService;
import common.cn.kafei.simukraft.citizen.CitizenSkillSnapshot;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.industrial.IndustrialControlBoxService;
import common.cn.kafei.simukraft.industrial.IndustrialDefinition;
import common.cn.kafei.simukraft.industrial.IndustrialDefinitionLoader;
import common.cn.kafei.simukraft.job.CityJobType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

@SuppressWarnings("null")
public record CitizenInfoResponsePacket(UUID citizenId, String name, String gender, int age, int lifespan,
                                        double health, double hunger, boolean sick, boolean child,
                                        String workStatus, String statusLabel, String jobType, String jobId, String jobName, String cityName, String homeName,
                                        String workplaceName, int skillLevel, int skillXp, int skillMaxLevel) implements CustomPacketPayload {
    public static final Type<CitizenInfoResponsePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "citizen_info_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CitizenInfoResponsePacket> STREAM_CODEC = StreamCodec.of(CitizenInfoResponsePacket::encode, CitizenInfoResponsePacket::decode);

    public static CitizenInfoResponsePacket from(ServerLevel level, CitizenEntity entity, CitizenData data) {
        String cityName = data.cityId() != null ? CityManager.get(level).getCity(data.cityId()).map(CityData::cityName).orElse("") : "";
        CityPoiManager poiManager = CityPoiManager.get(level);
        String homeName = poiName(poiManager, data.homeId());
        String workplaceName = poiName(poiManager, data.workplaceId());
        CitizenSkillSnapshot skill = CitizenLevelService.snapshot(data, data.jobType());
        return new CitizenInfoResponsePacket(
                data.uuid(),
                data.name(),
                data.gender(),
                data.age(),
                data.lifespan(),
                data.health(),
                entity.getHungerValue(),
                data.sick(),
                data.child(),
                data.workStatus(),
                CitizenSelfFeedingService.effectiveStatusLabel(level, data.uuid(), data.statusLabel()),
                data.jobType().name(),
                data.jobId(),
                displayJobName(level, data),
                cityName,
                homeName,
                workplaceName,
                skill.level(),
                skill.xp(),
                skill.maxLevel()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, CitizenInfoResponsePacket packet) {
        buffer.writeUUID(packet.citizenId());
        buffer.writeUtf(packet.name(), 64);
        buffer.writeUtf(packet.gender(), 16);
        buffer.writeVarInt(packet.age());
        buffer.writeVarInt(packet.lifespan());
        buffer.writeDouble(packet.health());
        buffer.writeDouble(packet.hunger());
        buffer.writeBoolean(packet.sick());
        buffer.writeBoolean(packet.child());
        buffer.writeUtf(packet.workStatus(), 32);
        buffer.writeUtf(packet.statusLabel(), 128);
        buffer.writeUtf(packet.jobType(), 32);
        buffer.writeUtf(packet.jobId(), 64);
        buffer.writeUtf(packet.jobName(), 128);
        buffer.writeUtf(packet.cityName(), 64);
        buffer.writeUtf(packet.homeName(), 96);
        buffer.writeUtf(packet.workplaceName(), 96);
        buffer.writeVarInt(packet.skillLevel());
        buffer.writeVarInt(packet.skillXp());
        buffer.writeVarInt(packet.skillMaxLevel());
    }

    public static CitizenInfoResponsePacket decode(RegistryFriendlyByteBuf buffer) {
        return new CitizenInfoResponsePacket(
                buffer.readUUID(),
                buffer.readUtf(64),
                buffer.readUtf(16),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readUtf(32),
                buffer.readUtf(128),
                buffer.readUtf(32),
                buffer.readUtf(64),
                buffer.readUtf(128),
                buffer.readUtf(64),
                buffer.readUtf(96),
                buffer.readUtf(96),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
    }

    public static void handle(CitizenInfoResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientboundNetworkBridge.handleCitizenInfoResponse(packet));
    }

    private static String poiName(CityPoiManager poiManager, UUID poiId) {
        if (poiId == null) {
            return "";
        }
        CityPoiData poi = poiManager.getPoi(poiId);
        return poi != null ? formatPoiName(poi) : "";
    }

    private static String formatPoiName(CityPoiData poi) {
        return poi.type().name() + " @ " + poi.pos().getX() + ", " + poi.pos().getY() + ", " + poi.pos().getZ();
    }

    private static String displayJobName(ServerLevel level, CitizenData data) {
        if (level == null || data == null || data.jobType() != CityJobType.INDUSTRIAL_WORKER || data.workplacePos() == null) {
            return "";
        }
        IndustrialDefinition definition = IndustrialDefinitionLoader.loadForBuilding(IndustrialControlBoxService.resolveBuilding(level, data.workplacePos())).definition();
        return definition != null ? definition.jobName() : "";
    }
}
