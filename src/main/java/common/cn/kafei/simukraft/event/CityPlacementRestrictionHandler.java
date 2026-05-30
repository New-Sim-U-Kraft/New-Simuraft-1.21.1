package common.cn.kafei.simukraft.event;

import common.cn.kafei.simukraft.city.CityChunkManager;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.city.poi.CityPoiService;
import common.cn.kafei.simukraft.city.poi.CityPoiType;
import common.cn.kafei.simukraft.building.ResidentialBedPoiService;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import common.cn.kafei.simukraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("null")
public final class CityPlacementRestrictionHandler {
    private CityPlacementRestrictionHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        ItemStack itemStack = event.getItemStack();
        if (!(itemStack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        Direction face = event.getFace();
        if (face == null) {
            return;
        }
        BlockPos targetPos = resolveTargetPos(event.getPos(), face, level);
        if (!shouldBlockPlacement(level, targetPos, blockItem.getBlock(), event.getEntity())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Block block = event.getPlacedBlock().getBlock();
        Level level = (Level) event.getLevel();
        if (shouldBlockPlacement(level, event.getPos(), block, player)) {
            event.setCanceled(true);
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                serverLevel.removeBlock(event.getPos(), false);
            }
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            ResidentialBedPoiService.handleBlockPlaced(serverLevel, event.getPos(), event.getPlacedBlock());
            registerPoiForPlacedBlock(serverLevel, event.getPos(), block, player);
        }
    }

    @SubscribeEvent
    public static void onMultiBlockPlaced(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Level level = (Level) event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (net.neoforged.neoforge.common.util.BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockState placedState = snapshot.getCurrentState();
            Block block = placedState.getBlock();
            if (shouldBlockPlacement(level, snapshot.getPos(), block, player)) {
                event.setCanceled(true);
                return;
            }
            ResidentialBedPoiService.handleBlockPlaced(serverLevel, snapshot.getPos(), placedState);
            registerPoiForPlacedBlock(serverLevel, snapshot.getPos(), block, player);
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState brokenState = serverLevel.getBlockState(event.getPos());
        ResidentialBedPoiService.handleBlockBroken(serverLevel, event.getPos(), brokenState);
        Block block = brokenState.getBlock();
        if (poiTypeForBlock(block).isPresent()) {
            CityPoiService.deactivatePoi(serverLevel, event.getPos());
        }
    }

    private static BlockPos resolveTargetPos(BlockPos clickedPos, Direction face, Level level) {
        BlockState clickedState = level.getBlockState(clickedPos);
        return clickedState.canBeReplaced() ? clickedPos : clickedPos.relative(face);
    }

    private static boolean shouldBlockPlacement(Level level, BlockPos targetPos, Block block, Player player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (player.isCreative()) {
            return false;
        }
        if (!isRestrictedBlock(block)) {
            return false;
        }
        return getPlacementCity(serverLevel, targetPos, player).isEmpty();
    }

    private static Optional<CityData> getPlacementCity(ServerLevel serverLevel, BlockPos targetPos, Player player) {
        Optional<CityData> playerCity = CityManager.get(serverLevel).getPlayerCity(player.getUUID());
        if (playerCity.isEmpty()) {
            sendDeniedMessage(player);
            return Optional.empty();
        }
        UUID ownerCityId = CityChunkManager.get(serverLevel).getChunkOwner(new ChunkPos(targetPos).toLong());
        if (playerCity.get().cityId().equals(ownerCityId)) {
            return playerCity;
        }
        sendDeniedMessage(player);
        return Optional.empty();
    }

    private static void registerPoiForPlacedBlock(ServerLevel level, BlockPos pos, Block block, Player player) {
        Optional<CityPoiType> poiType = poiTypeForBlock(block);
        if (poiType.isEmpty()) {
            return;
        }
        getPlacementCity(level, pos, player).ifPresent(city -> CityPoiService.registerPoi(level, city.cityId(), pos, poiType.get(), poiCapacity(poiType.get())));
    }

    private static Optional<CityPoiType> poiTypeForBlock(Block block) {
        if (block == ModBlocks.RESIDENTIAL_CONTROL_BOX.get()) {
            return Optional.empty();
        }
        if (block == ModBlocks.COMMERCIAL_CONTROL_BOX.get()) {
            return Optional.of(CityPoiType.COMMERCIAL);
        }
        if (block == ModBlocks.INDUSTRIAL_CONTROL_BOX.get()) {
            return Optional.of(CityPoiType.INDUSTRIAL);
        }
        if (block == ModBlocks.OTHER_CONTROL_BOX.get()) {
            return Optional.of(CityPoiType.OTHER);
        }
        if (block == ModBlocks.NSUK_FARMLAND_BOX.get()) {
            return Optional.of(CityPoiType.FARMLAND);
        }
        if (block == ModBlocks.LOGISTICS_SERVER_BOX.get() || block == ModBlocks.LOGISTICS_CLIENT_BOX.get()) {
            return Optional.of(CityPoiType.LOGISTICS);
        }
        return Optional.empty();
    }

    private static int poiCapacity(CityPoiType type) {
        return switch (type) {
            case RESIDENTIAL -> 0;
            case COMMERCIAL, INDUSTRIAL, FARMLAND -> 2;
            case LOGISTICS, STORAGE, GATHERING, DEFENSE, OTHER -> 1;
        };
    }

    private static boolean isRestrictedBlock(Block block) {
        return block == ModBlocks.BUILD_BOX.get()
                || block == ModBlocks.NSUK_FARMLAND_BOX.get()
                || block == ModBlocks.LOGISTICS_SERVER_BOX.get()
                || block == ModBlocks.RESIDENTIAL_CONTROL_BOX.get()
                || block == ModBlocks.COMMERCIAL_CONTROL_BOX.get()
                || block == ModBlocks.INDUSTRIAL_CONTROL_BOX.get()
                || block == ModBlocks.OTHER_CONTROL_BOX.get();
    }

    private static void sendDeniedMessage(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            InfoToastService.warning(serverPlayer, Component.translatable("message.simukraft.city_placement.outside_city"));
        }
    }
}
