package common.cn.kafei.simukraft.network;

import common.cn.kafei.simukraft.network.building.BuildingCacheReloadPacket;
import common.cn.kafei.simukraft.network.building.BuildBoxStartConstructionPacket;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxBoundsUpdatePacket;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxDemolishPacket;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxOccupancyPacket;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxOpenRequestPacket;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxOpenResponsePacket;
import common.cn.kafei.simukraft.network.building.controlbox.ResidentialControlBoxViewUpdatePacket;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkPurchasePacket;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkSyncPacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreCreateCityPacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreManageCityPacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreOpenRequestPacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreOpenResponsePacket;
import common.cn.kafei.simukraft.network.city.map.CityCoreMapRequestPacket;
import common.cn.kafei.simukraft.network.city.map.CityCoreMapResponsePacket;
import common.cn.kafei.simukraft.network.city.member.CityCoreMemberActionPacket;
import common.cn.kafei.simukraft.network.city.member.CityCoreMembersRequestPacket;
import common.cn.kafei.simukraft.network.city.member.CityCoreMembersResponsePacket;
import common.cn.kafei.simukraft.network.citizen.info.CitizenInfoResponsePacket;
import common.cn.kafei.simukraft.network.hud.HudSyncPacket;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireAssignPacket;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireFirePacket;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireListRequestPacket;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireListResponsePacket;
import common.cn.kafei.simukraft.network.npc.state.EmploymentStateRequestPacket;
import common.cn.kafei.simukraft.network.npc.state.EmploymentStateResponsePacket;
import common.cn.kafei.simukraft.network.path.NpcPathDebugRequestPacket;
import common.cn.kafei.simukraft.network.path.NpcPathDebugSyncPacket;
import common.cn.kafei.simukraft.network.toast.InfoToastPacket;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private static final String NETWORK_VERSION = "4";

    private ModNetwork() {
    }

    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(CityCoreOpenRequestPacket.TYPE, CityCoreOpenRequestPacket.STREAM_CODEC, CityCoreOpenRequestPacket::handle);
        registrar.playToServer(CityCoreCreateCityPacket.TYPE, CityCoreCreateCityPacket.STREAM_CODEC, CityCoreCreateCityPacket::handle);
        registrar.playToServer(CityCoreManageCityPacket.TYPE, CityCoreManageCityPacket.STREAM_CODEC, CityCoreManageCityPacket::handle);
        registrar.playToServer(CityCoreMembersRequestPacket.TYPE, CityCoreMembersRequestPacket.STREAM_CODEC, CityCoreMembersRequestPacket::handle);
        registrar.playToServer(CityCoreMemberActionPacket.TYPE, CityCoreMemberActionPacket.STREAM_CODEC, CityCoreMemberActionPacket::handle);
        registrar.playToServer(CityCoreMapRequestPacket.TYPE, CityCoreMapRequestPacket.STREAM_CODEC, CityCoreMapRequestPacket::handle);
        registrar.playToServer(CityChunkPurchasePacket.TYPE, CityChunkPurchasePacket.STREAM_CODEC, CityChunkPurchasePacket::handle);
        registrar.playToServer(EmploymentStateRequestPacket.TYPE, EmploymentStateRequestPacket.STREAM_CODEC, EmploymentStateRequestPacket::handle);
        registrar.playToServer(NpcHireListRequestPacket.TYPE, NpcHireListRequestPacket.STREAM_CODEC, NpcHireListRequestPacket::handle);
        registrar.playToServer(NpcHireAssignPacket.TYPE, NpcHireAssignPacket.STREAM_CODEC, NpcHireAssignPacket::handle);
        registrar.playToServer(NpcHireFirePacket.TYPE, NpcHireFirePacket.STREAM_CODEC, NpcHireFirePacket::handle);
        registrar.playToServer(BuildBoxStartConstructionPacket.TYPE, BuildBoxStartConstructionPacket.STREAM_CODEC, BuildBoxStartConstructionPacket::handle);
        registrar.playToServer(ResidentialControlBoxOpenRequestPacket.TYPE, ResidentialControlBoxOpenRequestPacket.STREAM_CODEC, ResidentialControlBoxOpenRequestPacket::handle);
        registrar.playToServer(ResidentialControlBoxDemolishPacket.TYPE, ResidentialControlBoxDemolishPacket.STREAM_CODEC, ResidentialControlBoxDemolishPacket::handle);
        registrar.playToServer(ResidentialControlBoxOccupancyPacket.TYPE, ResidentialControlBoxOccupancyPacket.STREAM_CODEC, ResidentialControlBoxOccupancyPacket::handle);
        registrar.playToServer(NpcPathDebugRequestPacket.TYPE, NpcPathDebugRequestPacket.STREAM_CODEC, NpcPathDebugRequestPacket::handle);
        registrar.playToClient(CityCoreOpenResponsePacket.TYPE, CityCoreOpenResponsePacket.STREAM_CODEC, CityCoreOpenResponsePacket::handle);
        registrar.playToClient(CityCoreMembersResponsePacket.TYPE, CityCoreMembersResponsePacket.STREAM_CODEC, CityCoreMembersResponsePacket::handle);
        registrar.playToClient(CityCoreMapResponsePacket.TYPE, CityCoreMapResponsePacket.STREAM_CODEC, CityCoreMapResponsePacket::handle);
        registrar.playToClient(CityChunkSyncPacket.TYPE, CityChunkSyncPacket.STREAM_CODEC, CityChunkSyncPacket::handle);
        registrar.playToClient(CitizenInfoResponsePacket.TYPE, CitizenInfoResponsePacket.STREAM_CODEC, CitizenInfoResponsePacket::handle);
        registrar.playToClient(NpcHireListResponsePacket.TYPE, NpcHireListResponsePacket.STREAM_CODEC, NpcHireListResponsePacket::handle);
        registrar.playToClient(EmploymentStateResponsePacket.TYPE, EmploymentStateResponsePacket.STREAM_CODEC, EmploymentStateResponsePacket::handle);
        registrar.playToClient(HudSyncPacket.TYPE, HudSyncPacket.STREAM_CODEC, HudSyncPacket::handle);
        registrar.playToClient(BuildingCacheReloadPacket.TYPE, BuildingCacheReloadPacket.STREAM_CODEC, BuildingCacheReloadPacket::handle);
        registrar.playToClient(ResidentialControlBoxBoundsUpdatePacket.TYPE, ResidentialControlBoxBoundsUpdatePacket.STREAM_CODEC, ResidentialControlBoxBoundsUpdatePacket::handle);
        registrar.playToClient(ResidentialControlBoxViewUpdatePacket.TYPE, ResidentialControlBoxViewUpdatePacket.STREAM_CODEC, ResidentialControlBoxViewUpdatePacket::handle);
        registrar.playToClient(ResidentialControlBoxOpenResponsePacket.TYPE, ResidentialControlBoxOpenResponsePacket.STREAM_CODEC, ResidentialControlBoxOpenResponsePacket::handle);
        registrar.playToClient(NpcPathDebugSyncPacket.TYPE, NpcPathDebugSyncPacket.STREAM_CODEC, NpcPathDebugSyncPacket::handle);
        registrar.playToClient(InfoToastPacket.TYPE, InfoToastPacket.STREAM_CODEC, InfoToastPacket::handle);
    }
}
