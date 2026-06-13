package common.cn.kafei.simukraft.farmland;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenSelfFeedingService;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.config.ServerConfig;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.material.GenericContainerAccess;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.MovementIntent;
import common.cn.kafei.simukraft.registry.ModBlocks;
import common.cn.kafei.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 农田盒耕作服务：按“挖水槽 -> 耕地 -> 播种 -> 收割”的阶段逐格推进。
 * NPC 必须先移动到目标格旁边，再由服务端提交对应方块变化，避免瞬间批量改田。
 */

@SuppressWarnings("null")
public final class FarmlandFarmingService {
    private static final ConcurrentMap<String, LevelRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final long IDLE_INTERVAL_TICKS = 40L;
    private static final long MOVE_INTERVAL_TICKS = 8L;

    private FarmlandFarmingService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        long gameTime = level.getGameTime();
        FarmlandBoxManager manager = FarmlandBoxManager.get(level);
        LevelRuntime runtime = runtime(level);
        for (FarmlandBoxData data : manager.all()) {
            if (!data.running()) {
                runtime.boxes.remove(data.boxPos());
                continue;
            }
            BoxRuntime boxRuntime = runtime.boxes.computeIfAbsent(data.boxPos(), pos -> new BoxRuntime());
            FarmlandFarmerVisualService.refresh(level, data, boxRuntime.visualTool, boxRuntime.visualActive);
            if (gameTime < boxRuntime.nextActionTick) {
                continue;
            }
            tickBox(level, manager, data, boxRuntime, gameTime);
        }
    }

    public static void clearServerCaches(MinecraftServer server) {
        String serverKey = SaveScopedCacheKey.serverKey(server).toLowerCase(Locale.ROOT);
        RUNTIMES.keySet().removeIf(key -> key.startsWith(serverKey + "|"));
    }

    private static void tickBox(ServerLevel level, FarmlandBoxManager manager, FarmlandBoxData data, BoxRuntime boxRuntime, long gameTime) {
        BlockPos boxPos = data.boxPos();
        if (!level.isLoaded(boxPos)) {
            idle(boxRuntime, gameTime);
            return;
        }
        if (!level.getBlockState(boxPos).is(ModBlocks.NSUK_FARMLAND_BOX.get())) {
            data.setRunning(false);
            manager.persist(data);
            clearActiveTarget(boxRuntime);
            return;
        }
        if (!data.isConfigured()) {
            data.setRunning(false);
            manager.persist(data);
            clearActiveTarget(boxRuntime);
            return;
        }

        CitizenData farmer = FarmlandBoxService.findAssignedFarmer(level, boxPos);
        if (farmer == null) {
            data.setRunning(false);
            manager.persist(data);
            clearActiveTarget(boxRuntime);
            return;
        }
        if (CitizenHomeRestService.isRestTime(level)) {
            clearActiveTarget(boxRuntime);
            boxRuntime.setVisual(ItemStack.EMPTY, false);
            idle(boxRuntime, gameTime);
            return;
        }
        if (CitizenSelfFeedingService.isSelfFeeding(level, farmer.uuid())) {
            boxRuntime.setVisual(ItemStack.EMPTY, false);
            idle(boxRuntime, gameTime);
            return;
        }
        CitizenEntity farmerEntity = CitizenTeleportService.findCitizenEntity(level, farmer.uuid());
        if (farmerEntity == null) {
            idle(boxRuntime, gameTime);
            return;
        }

        BlockPos chestPos = FarmlandBoxService.resolveAdjacentChest(level, boxPos);
        if (chestPos == null) {
            clearActiveTarget(boxRuntime);
            setFarmerStatus(level, farmer, boxRuntime, "等待仓储箱: " + cropLabel(data.crop()), CitizenWorkStatus.WORKING, "missing_chest");
            boxRuntime.setVisual(ItemStack.EMPTY, false);
            idle(boxRuntime, gameTime);
            return;
        }

        FarmlandWorkTarget target = resolveTarget(level, data, chestPos, boxRuntime);
        if (target == null) {
            String phaseKey = hasSeedlessPlantingWork(level, data, chestPos) ? "missing_seed" : "waiting_growth";
            String label = "missing_seed".equals(phaseKey)
                    ? "等待种子: " + cropLabel(data.crop())
                    : "等待作物成熟: " + cropLabel(data.crop());
            setFarmerStatus(level, farmer, boxRuntime, label, CitizenWorkStatus.WORKING, phaseKey);
            boxRuntime.setVisual(ItemStack.EMPTY, false);
            idle(boxRuntime, gameTime);
            return;
        }

        setFarmerStatus(level, farmer, boxRuntime, target.phase().label() + ": " + cropLabel(data.crop()), CitizenWorkStatus.WORKING, target.phase().id());
        boxRuntime.setVisual(FarmlandFarmerVisualService.toolFor(data.crop(), target.phase()), false);
        FarmlandFarmerVisualService.apply(farmerEntity, boxRuntime.visualTool, boxRuntime.visualActive);
        if (!isCloseEnoughToWork(level, boxPos, farmer, farmerEntity, target)) {
            boxRuntime.nextActionTick = gameTime + MOVE_INTERVAL_TICKS;
            return;
        }

        FarmlandWorkResult result = applyTarget(level, data, chestPos, target);
        if (result == FarmlandWorkResult.PROCESSED) {
            farmerEntity.triggerWorkSwing(InteractionHand.MAIN_HAND);
            clearActiveTarget(boxRuntime);
            boxRuntime.nextActionTick = gameTime + Math.max(1, ServerConfig.farmWorkIntervalTicks());
        } else if (result == FarmlandWorkResult.WAITING_SEED) {
            clearActiveTarget(boxRuntime);
            setFarmerStatus(level, farmer, boxRuntime, "等待种子: " + cropLabel(data.crop()), CitizenWorkStatus.WORKING, "missing_seed");
            boxRuntime.nextActionTick = gameTime + IDLE_INTERVAL_TICKS;
        } else {
            clearActiveTarget(boxRuntime);
            boxRuntime.nextActionTick = gameTime + 1L;
        }
    }

    private static FarmlandWorkTarget resolveTarget(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BoxRuntime boxRuntime) {
        FarmlandWorkTarget active = boxRuntime.activeTarget;
        if (active != null && needsWork(level, data, chestPos, active.phase(), active.cropPos())) {
            return active;
        }
        clearActiveTarget(boxRuntime);
        for (FarmlandWorkPhase phase : FarmlandWorkPhase.ORDERED) {
            if (phase == FarmlandWorkPhase.PLANT && !hasSeed(level, chestPos, data.crop())) {
                continue;
            }
            if (phase == FarmlandWorkPhase.BONEMEAL && !hasBoneMeal(level, chestPos)) {
                continue;
            }
            FarmlandWorkTarget target = findTarget(level, data, chestPos, boxRuntime, phase);
            if (target != null) {
                boxRuntime.activeTarget = target;
                return target;
            }
        }
        return null;
    }

    private static FarmlandWorkTarget findTarget(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BoxRuntime boxRuntime, FarmlandWorkPhase phase) {
        FarmlandPlot plot = data.plot();
        int cells = scanCellCount(plot, phase);
        int start = boxRuntime.cursor(phase);
        for (int inspected = 0; inspected < cells; inspected++) {
            int index = start + inspected;
            BlockPos cropPos = scanCellAt(plot, phase, index);
            if (isSkippedBoxCell(data.boxPos(), cropPos) || !level.isLoaded(cropPos)) {
                continue;
            }
            if (needsWork(level, data, chestPos, phase, cropPos)) {
                boxRuntime.setCursor(phase, index + 1);
                return new FarmlandWorkTarget(phase, cropPos.immutable());
            }
        }
        boxRuntime.setCursor(phase, start + cells);
        return null;
    }

    private static int scanCellCount(FarmlandPlot plot, FarmlandWorkPhase phase) {
        return usesGroupedFarmScan(phase) ? FarmlandWorkGeometry.groupedFarmCellCount(plot) : Math.max(1, plot.cellCount());
    }

    private static BlockPos scanCellAt(FarmlandPlot plot, FarmlandWorkPhase phase, int index) {
        return usesGroupedFarmScan(phase) ? FarmlandWorkGeometry.groupedFarmCellAt(plot, index) : plot.cellAt(index);
    }

    // usesGroupedFarmScan：这些阶段必须整块做完，再跨过水槽进入下一块。
    private static boolean usesGroupedFarmScan(FarmlandWorkPhase phase) {
        return phase == FarmlandWorkPhase.TILL || phase == FarmlandWorkPhase.PLANT
                || phase == FarmlandWorkPhase.BONEMEAL || phase == FarmlandWorkPhase.HARVEST;
    }

    private static boolean needsWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, FarmlandWorkPhase phase, BlockPos cropPos) {
        return switch (phase) {
            case DIG_WATER -> needsWaterWork(level, data, chestPos, cropPos);
            case TILL -> needsTillWork(level, data, cropPos);
            case PLANT -> needsPlantWork(level, data, cropPos);
            case BONEMEAL -> needsBonemealWork(level, data, chestPos, cropPos);
            case HARVEST -> needsHarvestWork(level, data, cropPos);
        };
    }

    private static boolean needsWaterWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BlockPos cropPos) {
        FarmlandPlot plot = data.plot();
        if (!FarmlandWorkGeometry.isWaterTrough(plot, cropPos)) {
            return false;
        }
        BlockPos soilPos = cropPos.below();
        if (isProtected(level, cropPos, level.getBlockState(cropPos), data.boxPos(), chestPos)
                || isProtected(level, soilPos, level.getBlockState(soilPos), data.boxPos(), chestPos)) {
            return false;
        }
        BlockState cropState = level.getBlockState(cropPos);
        BlockState soilState = level.getBlockState(soilPos);
        return !cropState.isAir() || !soilState.is(Blocks.WATER);
    }

    private static boolean needsTillWork(ServerLevel level, FarmlandBoxData data, BlockPos cropPos) {
        FarmCrop crop = data.crop();
        if (FarmlandWorkGeometry.isWaterTrough(data.plot(), cropPos) || crop == null || !crop.shouldPlantAt(cropPos.getX(), cropPos.getZ())) {
            return false;
        }
        BlockState cropState = level.getBlockState(cropPos);
        BlockState soilState = level.getBlockState(cropPos.below());
        return isCropCellFree(cropState) && isTillable(soilState);
    }

    private static boolean needsPlantWork(ServerLevel level, FarmlandBoxData data, BlockPos cropPos) {
        FarmCrop crop = data.crop();
        if (FarmlandWorkGeometry.isWaterTrough(data.plot(), cropPos) || crop == null || !crop.shouldPlantAt(cropPos.getX(), cropPos.getZ())) {
            return false;
        }
        BlockState cropState = level.getBlockState(cropPos);
        BlockState soilState = level.getBlockState(cropPos.below());
        return isCropCellFree(cropState) && soilState.is(Blocks.FARMLAND);
    }

    private static boolean needsHarvestWork(ServerLevel level, FarmlandBoxData data, BlockPos cropPos) {
        FarmCrop crop = data.crop();
        if (crop == null || FarmlandWorkGeometry.isWaterTrough(data.plot(), cropPos)) {
            return false;
        }
        BlockState cropState = level.getBlockState(cropPos);
        if (crop.isStem()) {
            return crop.isOwnPlant(cropState) && hasProduceAround(level, crop, cropPos);
        }
        return crop.isOwnPlant(cropState) && crop.isMatureFull(cropState);
    }

    private static FarmlandWorkResult applyTarget(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, FarmlandWorkTarget target) {
        return switch (target.phase()) {
            case DIG_WATER -> applyWaterWork(level, data, chestPos, target.cropPos());
            case TILL -> applyTillWork(level, data, target.cropPos());
            case PLANT -> applyPlantWork(level, data, chestPos, target.cropPos());
            case BONEMEAL -> applyBonemealWork(level, data, chestPos, target.cropPos());
            case HARVEST -> applyHarvestWork(level, data, chestPos, target.cropPos());
        };
    }

    private static FarmlandWorkResult applyWaterWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BlockPos cropPos) {
        if (!needsWaterWork(level, data, chestPos, cropPos)) {
            return FarmlandWorkResult.SKIPPED;
        }
        BlockPos soilPos = cropPos.below();
        BlockState cropState = level.getBlockState(cropPos);
        if (!cropState.isAir()) {
            harvestBlock(level, chestPos, cropPos, cropState);
        }
        BlockState soilState = level.getBlockState(soilPos);
        if (!soilState.is(Blocks.WATER)) {
            if (!soilState.isAir()) {
                harvestBlock(level, chestPos, soilPos, soilState);
            }
            level.setBlock(soilPos, Blocks.WATER.defaultBlockState(), 3);
        }
        return FarmlandWorkResult.PROCESSED;
    }

    private static FarmlandWorkResult applyTillWork(ServerLevel level, FarmlandBoxData data, BlockPos cropPos) {
        if (!needsTillWork(level, data, cropPos)) {
            return FarmlandWorkResult.SKIPPED;
        }
        level.setBlock(cropPos.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 3);
        return FarmlandWorkResult.PROCESSED;
    }

    private static FarmlandWorkResult applyPlantWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BlockPos cropPos) {
        if (!needsPlantWork(level, data, cropPos)) {
            return FarmlandWorkResult.SKIPPED;
        }
        FarmCrop crop = data.crop();
        if (!consumeSeed(level, chestPos, crop)) {
            return FarmlandWorkResult.WAITING_SEED;
        }
        level.setBlock(cropPos, crop.plantState(), 3);
        return FarmlandWorkResult.PROCESSED;
    }

    private static FarmlandWorkResult applyHarvestWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BlockPos cropPos) {
        if (!needsHarvestWork(level, data, cropPos)) {
            return FarmlandWorkResult.SKIPPED;
        }
        FarmCrop crop = data.crop();
        BlockState cropState = level.getBlockState(cropPos);
        if (crop.isStem()) {
            return harvestProduceAround(level, chestPos, crop, cropPos) ? FarmlandWorkResult.PROCESSED : FarmlandWorkResult.SKIPPED;
        }
        harvestBlock(level, chestPos, cropPos, cropState);
        replantAfterHarvest(level, chestPos, crop, cropPos);
        return FarmlandWorkResult.PROCESSED;
    }

    private static void replantAfterHarvest(ServerLevel level, BlockPos chestPos, FarmCrop crop, BlockPos cropPos) {
        if (crop == null || crop.isStem() || !crop.shouldPlantAt(cropPos.getX(), cropPos.getZ())) {
            return;
        }
        BlockState cropState = level.getBlockState(cropPos);
        BlockState soilState = level.getBlockState(cropPos.below());
        if (isCropCellFree(cropState) && soilState.is(Blocks.FARMLAND) && consumeSeed(level, chestPos, crop)) {
            level.setBlock(cropPos, crop.plantState(), 3);
        }
    }

    private static boolean isCloseEnoughToWork(ServerLevel level, BlockPos boxPos, CitizenData farmer, CitizenEntity farmerEntity, FarmlandWorkTarget target) {
        Vec3 targetCenter = Vec3.atCenterOf(target.cropPos());
        if (farmerEntity.position().distanceToSqr(targetCenter) <= FarmlandWorkGeometry.ACTION_REACH * FarmlandWorkGeometry.ACTION_REACH) {
            return true;
        }
        Vec3 anchor = FarmlandWorkGeometry.workAnchorFor(level, boxPos, target.cropPos());
        CitizenNavigationService.requestMove(level, farmer.uuid(), anchor, MovementIntent.WORK);
        return false;
    }

    private static boolean hasSeedlessPlantingWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos) {
        if (hasSeed(level, chestPos, data.crop())) {
            return false;
        }
        FarmlandPlot plot = data.plot();
        int cells = Math.max(1, plot.cellCount());
        for (int index = 0; index < cells; index++) {
            BlockPos cropPos = plot.cellAt(index);
            if (!isSkippedBoxCell(data.boxPos(), cropPos) && level.isLoaded(cropPos) && needsPlantWork(level, data, cropPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsBonemealWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BlockPos cropPos) {
        FarmCrop crop = data.crop();
        if (crop == null || FarmlandWorkGeometry.isWaterTrough(data.plot(), cropPos)) return false;
        BlockState state = level.getBlockState(cropPos);
        if (!crop.isOwnPlant(state) || crop.isMatureFull(state)) return false;
        return state.getBlock() instanceof BonemealableBlock b
                && b.isValidBonemealTarget(level, cropPos, state)
                && hasBoneMeal(level, chestPos);
    }

    private static FarmlandWorkResult applyBonemealWork(ServerLevel level, FarmlandBoxData data, BlockPos chestPos, BlockPos cropPos) {
        if (!needsBonemealWork(level, data, chestPos, cropPos)) return FarmlandWorkResult.SKIPPED;
        if (!consumeBoneMeal(level, chestPos)) return FarmlandWorkResult.SKIPPED;
        BlockState state = level.getBlockState(cropPos);
        ((BonemealableBlock) state.getBlock()).performBonemeal(level, level.getRandom(), cropPos, state);
        return FarmlandWorkResult.PROCESSED;
    }

    private static boolean hasBoneMeal(ServerLevel level, BlockPos chestPos) {
        if (chestPos == null) return false;
        for (GenericContainerAccess.SlotSnapshot slot : GenericContainerAccess.snapshotSlots(level, chestPos)) {
            if (slot.stack().getItem() == Items.BONE_MEAL) return true;
        }
        return false;
    }

    private static boolean consumeBoneMeal(ServerLevel level, BlockPos chestPos) {
        if (chestPos == null) return false;
        for (GenericContainerAccess.SlotSnapshot slot : GenericContainerAccess.snapshotSlots(level, chestPos)) {
            if (slot.stack().getItem() == Items.BONE_MEAL) {
                return GenericContainerAccess.consumeSingleItemAtSlot(level, chestPos, slot.slot(), slot.access(), slot.side(), Items.BONE_MEAL);
            }
        }
        return false;
    }

    private static boolean hasSeed(ServerLevel level, BlockPos chestPos, FarmCrop crop) {
        if (chestPos == null || crop == null) {
            return false;
        }
        List<GenericContainerAccess.SlotSnapshot> slots = GenericContainerAccess.snapshotSlots(level, chestPos);
        for (GenericContainerAccess.SlotSnapshot slot : slots) {
            if (slot.stack().getItem() == crop.seed()) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumeSeed(ServerLevel level, BlockPos chestPos, FarmCrop crop) {
        if (chestPos == null || crop == null) {
            return false;
        }
        List<GenericContainerAccess.SlotSnapshot> slots = GenericContainerAccess.snapshotSlots(level, chestPos);
        for (GenericContainerAccess.SlotSnapshot slot : slots) {
            if (slot.stack().getItem() == crop.seed()) {
                return GenericContainerAccess.consumeSingleItemAtSlot(level, chestPos, slot.slot(), slot.access(), slot.side(), crop.seed());
            }
        }
        return false;
    }

    private static void harvestBlock(ServerLevel level, BlockPos chestPos, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        depositDrops(level, chestPos, drops, pos);
    }

    private static boolean harvestProduceAround(ServerLevel level, BlockPos chestPos, FarmCrop crop, BlockPos stemPos) {
        boolean harvested = false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos producePos = stemPos.relative(direction);
            if (!level.isLoaded(producePos)) {
                continue;
            }
            BlockState produceState = level.getBlockState(producePos);
            if (crop.isProduce(produceState)) {
                harvestBlock(level, chestPos, producePos, produceState);
                harvested = true;
            }
        }
        return harvested;
    }

    private static boolean hasProduceAround(ServerLevel level, FarmCrop crop, BlockPos stemPos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos producePos = stemPos.relative(direction);
            if (level.isLoaded(producePos) && crop.isProduce(level.getBlockState(producePos))) {
                return true;
            }
        }
        return false;
    }

    private static void depositDrops(ServerLevel level, BlockPos chestPos, List<ItemStack> drops, BlockPos fallbackPos) {
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack leftover = chestPos != null ? GenericContainerAccess.insert(level, chestPos, drop) : drop;
            if (!leftover.isEmpty()) {
                Block.popResource(level, fallbackPos, leftover);
            }
        }
    }

    private static void setFarmerStatus(ServerLevel level, CitizenData farmer, BoxRuntime boxRuntime, String label, CitizenWorkStatus status, String phaseKey) {
        String safeLabel = label != null ? label : "";
        String detail = "farm:" + phaseKey;
        if (safeLabel.equals(boxRuntime.lastStatusLabel)
                && detail.equals(farmer.workNeedDetail())
                && farmer.workStatusType() == status) {
            return;
        }
        boxRuntime.lastStatusLabel = safeLabel;
        farmer.setWorkStatus(status);
        farmer.setStatusLabel(safeLabel);
        farmer.setWorkNeedDetail(detail);
        CitizenService.save(level, farmer.uuid());
    }

    private static boolean isCropCellFree(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    private static boolean isTillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.ROOTED_DIRT);
    }

    private static boolean isSkippedBoxCell(BlockPos boxPos, BlockPos cropPos) {
        return cropPos.getX() == boxPos.getX() && cropPos.getZ() == boxPos.getZ();
    }

    private static boolean isProtected(ServerLevel level, BlockPos pos, BlockState state, BlockPos boxPos, BlockPos chestPos) {
        if (pos.equals(boxPos) || (chestPos != null && pos.equals(chestPos))) {
            return true;
        }
        if (state.is(Blocks.BEDROCK)) {
            return true;
        }
        if (state.is(ModBlocks.NSUK_FARMLAND_BOX.get()) || state.is(ModBlocks.BUILD_BOX.get()) || state.is(ModBlocks.CITY_CORE.get())) {
            return true;
        }
        return GenericContainerAccess.isContainer(level, pos);
    }

    private static String cropLabel(FarmCrop crop) {
        return crop != null ? crop.id() : "unknown";
    }

    private static void idle(BoxRuntime boxRuntime, long gameTime) {
        boxRuntime.nextActionTick = gameTime + IDLE_INTERVAL_TICKS;
    }

    private static void clearActiveTarget(BoxRuntime boxRuntime) {
        boxRuntime.activeTarget = null;
    }

    private static LevelRuntime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(runtimeKey(level), ignored -> new LevelRuntime());
    }

    private static String runtimeKey(ServerLevel level) {
        return SaveScopedCacheKey.levelKey(level).toLowerCase(Locale.ROOT);
    }

    private static final class LevelRuntime {
        private final ConcurrentMap<BlockPos, BoxRuntime> boxes = new ConcurrentHashMap<>();
    }

    private static final class BoxRuntime {
        private final int[] cursors = new int[FarmlandWorkPhase.values().length];
        private volatile FarmlandWorkTarget activeTarget;
        private long nextActionTick;
        private String lastStatusLabel = "";
        private ItemStack visualTool = ItemStack.EMPTY;
        private boolean visualActive;

        private int cursor(FarmlandWorkPhase phase) {
            return cursors[phase.ordinal()];
        }
        private void setCursor(FarmlandWorkPhase phase, int cursor) {
            cursors[phase.ordinal()] = cursor;
        }
        private void setVisual(ItemStack tool, boolean active) {
            visualTool = FarmlandFarmerVisualService.normalize(tool);
            visualActive = active;
        }
    }
}
