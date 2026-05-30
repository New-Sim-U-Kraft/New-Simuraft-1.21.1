package common.cn.kafei.simukraft;

import com.mojang.logging.LogUtils;
import common.cn.kafei.simukraft.citizen.CitizenDeathService;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.PopulationGrowthService;
import common.cn.kafei.simukraft.city.CityChunkManager;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import common.cn.kafei.simukraft.building.ResidentialBedPoiService;
import common.cn.kafei.simukraft.command.SimuKraftCommand;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.economy.ResidentialRentService;
import common.cn.kafei.simukraft.event.CityPlacementRestrictionHandler;
import common.cn.kafei.simukraft.network.ModNetwork;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkSyncService;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.job.CityJobAssignmentService;
import common.cn.kafei.simukraft.material.WorkMaterialPolicy;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.CitizenWanderService;
import common.cn.kafei.simukraft.registry.ModBlocks;
import common.cn.kafei.simukraft.registry.ModCreativeModeTabs;
import common.cn.kafei.simukraft.registry.ModEntities;
import common.cn.kafei.simukraft.registry.ModEntityAttributes;
import common.cn.kafei.simukraft.registry.ModSoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(SimuKraft.MOD_ID)
public final class SimuKraft {
    public static final String MOD_ID = "simukraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SimuKraft(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModEntities.register(modEventBus);
        ModEntityAttributes.register(modEventBus);
        ModSoundEvents.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modEventBus.register(ModNetwork.class);
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            client.cn.kafei.simukraft.ClientSetup.registerModBusEvents(modEventBus);
        }
        NeoForge.EVENT_BUS.register(CityPlacementRestrictionHandler.class);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        LOGGER.info("Initializing {}", MOD_ID);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SimuKraftCommand.register(event.getDispatcher());
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        clearMaterialPolicyIfServerConfig(event);
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        clearMaterialPolicyIfServerConfig(event);
    }

    private void clearMaterialPolicyIfServerConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == ServerConfig.SPEC) {
            WorkMaterialPolicy.clearCache();
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            CityChunkSyncService.syncToPlayer(player);
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof common.cn.kafei.simukraft.entity.CitizenEntity citizenEntity
                && citizenEntity.level() instanceof net.minecraft.server.level.ServerLevel level) {
            CitizenDeathService.handleDeath(level, citizenEntity);
        }
    }

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            CitizenNavigationService.invalidate(level, event.getPos());
        }
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            CitizenNavigationService.invalidate(level, event.getPos());
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        event.getServer().getAllLevels().forEach(level -> {
            CitizenManager.get(level).tick(level);
            CitizenNavigationService.tick(level);
            CitizenWanderService.tick(level);
            PlacedBuildingService.ensureCityPoisRegistered(level);
            CitizenHomeRestService.tick(level);
            BuilderConstructionService.tick(level);
            PopulationGrowthService.tick(level);
            ResidentialRentService.tick(level);
            HudSyncService.tick(level);
            if (level.getGameTime() % 1200L == 0L) {
                CityManager.get(level).saveToSqlite(level);
                CityChunkManager.get(level).saveToSqlite(level);
                CityPoiManager.get(level).saveToSqlite(level);
                CitizenManager.get(level).saveToSqlite(level);
            }
        });
    }

    private void onServerStopping(ServerStoppingEvent event) {
        event.getServer().getAllLevels().forEach(level -> {
            BuilderConstructionService.flush(level);
            CityManager.get(level).saveToSqlite(level);
            CityChunkManager.get(level).saveToSqlite(level);
            CityPoiManager.get(level).saveToSqlite(level);
            CitizenManager.get(level).saveToSqlite(level);
        });
        BuilderConstructionService.clearServerCaches(event.getServer());
        PlacedBuildingService.clearServerCaches(event.getServer());
        ResidentialBedPoiService.clearServerCaches(event.getServer());
        CitizenHomeRestService.clearServerCaches(event.getServer());
        CityJobAssignmentService.clearServerCaches(event.getServer());
        CitizenNavigationService.clearServerCaches(event.getServer());
        CitizenWanderService.clearServerCaches(event.getServer());
        ResidentialRentService.clearServerCaches(event.getServer());
        HudSyncService.clearServerCaches(event.getServer());
        WorkMaterialPolicy.clearCache();
    }
}
