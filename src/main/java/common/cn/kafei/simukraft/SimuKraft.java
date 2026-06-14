package common.cn.kafei.simukraft;

import com.mojang.logging.LogUtils;
import common.cn.kafei.simukraft.citizen.CitizenDeathService;
import common.cn.kafei.simukraft.citizen.CitizenDroppedFoodService;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.CitizenSelfFeedingService;
import common.cn.kafei.simukraft.citizen.PopulationGrowthService;
import common.cn.kafei.simukraft.city.CityChunkManager;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.city.CityPermissionInviteService;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.building.BuildingIntegrityService;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import common.cn.kafei.simukraft.building.ResidentialBedPoiService;
import common.cn.kafei.simukraft.commercial.CommercialBoxManager;
import common.cn.kafei.simukraft.building.BuildingCatalog;
import common.cn.kafei.simukraft.commercial.CommercialDefinitionLoader;
import common.cn.kafei.simukraft.commercial.CommercialFoodMarketService;
import common.cn.kafei.simukraft.commercial.CommercialStockManager;
import common.cn.kafei.simukraft.commercial.CommercialWorkService;
import common.cn.kafei.simukraft.command.SimuKraftCommand;
import common.cn.kafei.simukraft.config.ClientConfig;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.economy.ResidentialRentService;
import common.cn.kafei.simukraft.farmland.FarmlandBoxManager;
import common.cn.kafei.simukraft.farmland.FarmlandFarmingService;
import common.cn.kafei.simukraft.industrial.IndustrialBoxManager;
import common.cn.kafei.simukraft.industrial.IndustrialWorkService;
import common.cn.kafei.simukraft.logistics.LogisticsAutoClientService;
import common.cn.kafei.simukraft.logistics.LogisticsManager;
import common.cn.kafei.simukraft.logistics.LogisticsWorkService;
import common.cn.kafei.simukraft.planner.PlannerWorkService;
import common.cn.kafei.simukraft.event.CityPlacementRestrictionHandler;
import common.cn.kafei.simukraft.network.ModNetwork;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkSyncService;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.job.CityJobAssignmentService;
import common.cn.kafei.simukraft.material.WorkMaterialPolicy;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.protection.NpcBlockProtectionPolicy;
import common.cn.kafei.simukraft.path.CitizenWanderService;
import common.cn.kafei.simukraft.registry.ModBlocks;
import common.cn.kafei.simukraft.registry.ModCreativeModeTabs;
import common.cn.kafei.simukraft.registry.ModEntities;
import common.cn.kafei.simukraft.registry.ModEntityAttributes;
import common.cn.kafei.simukraft.registry.ModFluidTypes;
import common.cn.kafei.simukraft.registry.ModFluids;
import common.cn.kafei.simukraft.registry.ModItems;
import common.cn.kafei.simukraft.registry.ModMenuTypes;
import common.cn.kafei.simukraft.registry.ModRecipeSerializers;
import common.cn.kafei.simukraft.registry.ModSoundEvents;
import common.cn.kafei.simukraft.event.PlayerWelcomeService;
import common.cn.kafei.simukraft.storage.SimuSqliteStorage;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

@Mod(SimuKraft.MOD_ID)
public final class SimuKraft {
    public static final String MOD_ID = "simukraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SimuKraft(IEventBus modEventBus, ModContainer modContainer) {
        ModFluidTypes.register(modEventBus);
        ModFluids.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModRecipeSerializers.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModEntities.register(modEventBus);
        ModEntityAttributes.register(modEventBus);
        ModSoundEvents.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modEventBus.register(ModNetwork.class);
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);
        NeoForge.EVENT_BUS.register(CityPlacementRestrictionHandler.class);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onFarmlandTrample);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        LOGGER.info("\nWelcome to\n========================================================================\n███████╗██╗███╗   ███╗██╗   ██╗██╗  ██╗██████╗  █████╗ ███████╗████████╗\n██╔════╝██║████╗ ████║██║   ██║██║ ██╔╝██╔══██╗██╔══██╗██╔════╝╚══██╔══╝\n███████╗██║██╔████╔██║██║   ██║█████╔╝ ██████╔╝███████║█████╗     ██║   \n╚════██║██║██║╚██╔╝██║██║   ██║██╔═██╗ ██╔══██╗██╔══██║██╔══╝     ██║   \n███████║██║██║ ╚═╝ ██║╚██████╔╝██║  ██╗██║  ██║██║  ██║██║        ██║   \n╚══════╝╚═╝╚═╝     ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝        ╚═╝  \n========================================================================\n");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SimuKraftCommand.register(event.getDispatcher());
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        clearServerConfigCaches(event);
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        clearServerConfigCaches(event);
    }

    private void clearServerConfigCaches(ModConfigEvent event) {
        if (event.getConfig().getSpec() == ServerConfig.SPEC) {
            WorkMaterialPolicy.clearCache();
            NpcBlockProtectionPolicy.clearCache();
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            PlayerWelcomeService.handleLogin(player);
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

    private void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof common.cn.kafei.simukraft.entity.CitizenEntity) {
            event.setCanceled(true);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        event.getServer().getAllLevels().forEach(level -> {
            CitizenManager.get(level).tick(level);
            CitizenNavigationService.tick(level);
            CitizenWanderService.tick(level);
            PlacedBuildingService.ensureCityPoisRegistered(level);
            BuildingIntegrityService.tick(level);
            CitizenHomeRestService.tick(level);
            CitizenSelfFeedingService.tick(level);
            BuilderConstructionService.tick(level);
            PlannerWorkService.tick(level);
            IndustrialWorkService.tick(level);
            CommercialWorkService.tick(level);
            LogisticsWorkService.tick(level);
            PopulationGrowthService.tick(level);
            ResidentialRentService.tick(level);
            FarmlandFarmingService.tick(level);
            HudSyncService.tick(level);
            CityPermissionInviteService.tick(level);
            if (level.getGameTime() % 200L == 0L) {
                saveDimensionSqlite(level);
            }
        });
        if (event.getServer().overworld().getGameTime() % 200L == 0L) {
            saveGlobalSqlite(event.getServer());
        }
        PlayerWelcomeService.tick(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        event.getServer().getAllLevels().forEach(level -> {
            BuilderConstructionService.flush(level);
            PlannerWorkService.flush(level);
            IndustrialWorkService.flush(level);
            CommercialWorkService.flush(level);
            saveDimensionSqlite(level);
        });
        saveGlobalSqlite(event.getServer());
        BuilderConstructionService.clearServerCaches(event.getServer());
        PlannerWorkService.clearServerCaches(event.getServer());
        IndustrialWorkService.clearServerCaches(event.getServer());
        CommercialWorkService.clearServerCaches(event.getServer());
        LogisticsWorkService.clearServerCaches(event.getServer());
        LogisticsAutoClientService.clearServerCaches(event.getServer());
        FarmlandFarmingService.clearServerCaches(event.getServer());
        PlacedBuildingService.clearServerCaches(event.getServer());
        ResidentialBedPoiService.clearServerCaches(event.getServer());
        CitizenHomeRestService.clearServerCaches(event.getServer());
        CitizenDroppedFoodService.clearServerCaches(event.getServer());
        CitizenSelfFeedingService.clearServerCaches(event.getServer());
        CommercialFoodMarketService.clearServerCaches(event.getServer());
        CityJobAssignmentService.clearServerCaches(event.getServer());
        CitizenNavigationService.clearServerCaches(event.getServer());
        CitizenWanderService.clearServerCaches(event.getServer());
        ResidentialRentService.clearServerCaches(event.getServer());
        HudSyncService.clearServerCaches(event.getServer());
        CommercialDefinitionLoader.clearCache();
        BuildingCatalog.clearCache();
        WorkMaterialPolicy.clearCache();
        NpcBlockProtectionPolicy.clearCache();
        PlayerWelcomeService.clearServerCaches(event.getServer());
        SimuSqliteStorage.clearServerCache(event.getServer());
    }

    private void saveDimensionSqlite(ServerLevel level) {
        CityChunkManager.get(level).saveToSqlite(level);
        CityPoiManager.get(level).saveToSqlite(level);
        FarmlandBoxManager.get(level).saveToSqlite(level);
        IndustrialBoxManager.get(level).saveToSqlite(level);
        CommercialBoxManager.get(level).saveToSqlite(level);
        CommercialStockManager.get(level).saveToSqlite(level);
        LogisticsManager.get(level).saveToSqlite(level);
    }

    private void saveGlobalSqlite(MinecraftServer server) {
        ServerLevel storageLevel = server.overworld();
        CityManager.get(storageLevel).saveToSqlite(storageLevel);
        CitizenManager.get(storageLevel).saveToSqlite(storageLevel);
    }
}
