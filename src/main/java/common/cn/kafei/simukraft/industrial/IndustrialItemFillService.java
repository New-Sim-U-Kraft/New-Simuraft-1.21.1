package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.material.GenericSlotAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class IndustrialItemFillService {
    private static final int DEFAULT_TARGET_COUNT = 64;

    private IndustrialItemFillService() {
    }

    public static ActionResult fill(ServerLevel level,
                                    PlacedBuildingRecord building,
                                    IndustrialDefinition definition,
                                    IndustrialDefinition.StepDefinition step,
                                    Vec3 origin) {
        if (step.item().isBlank() || step.slot() < 0) {
            return ActionResult.INVALID_STEP;
        }
        BlockPos targetPos = IndustrialControlBoxService.resolvePoint(building, definition, step.point(), origin);
        if (targetPos == null) {
            return ActionResult.MISSING_TARGET;
        }
        List<BlockPos> sourceContainers = IndustrialControlBoxService.resolveContainerPositions(
                building,
                definition,
                containerName(step.input(), step.container(), "input")
        );
        if (sourceContainers.isEmpty()) {
            return ActionResult.MISSING_INPUTS;
        }
        ItemStack targetStack = IndustrialInventoryService.stackForItem(step.item(), 1);
        if (targetStack.isEmpty()) {
            return ActionResult.INVALID_STEP;
        }
        ItemStack current = GenericSlotAccess.stackAt(level, targetPos, step.slot());
        if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, targetStack)) {
            return ActionResult.TARGET_BLOCKED;
        }
        int targetCount = targetCount(step);
        int currentCount = current.isEmpty() ? 0 : current.getCount();
        if (step.thresholdCount() >= 0 && currentCount > step.thresholdCount()) {
            return ActionResult.SUCCESS;
        }
        int need = Math.max(0, targetCount - currentCount);
        if (need <= 0) {
            return ActionResult.SUCCESS;
        }
        int available = IndustrialInventoryService.countInput(level, sourceContainers, step.item(), "");
        if (available <= 0) {
            return ActionResult.MISSING_INPUTS;
        }
        int insertable = GenericSlotAccess.countInsertable(level, targetPos, step.slot(), targetStack.copyWithCount(need));
        int moveCount = Math.min(need, Math.min(available, insertable));
        if (moveCount <= 0) {
            return ActionResult.TARGET_BLOCKED;
        }
        if (!IndustrialInventoryService.consumeInput(level, sourceContainers, step.item(), "", moveCount)) {
            return ActionResult.MISSING_INPUTS;
        }
        ItemStack remaining = GenericSlotAccess.insert(level, targetPos, step.slot(), targetStack.copyWithCount(moveCount));
        if (!remaining.isEmpty()) {
            IndustrialInventoryService.insertItem(level, sourceContainers, remaining);
            return remaining.getCount() < moveCount ? ActionResult.SUCCESS : ActionResult.TARGET_BLOCKED;
        }
        return ActionResult.SUCCESS;
    }

    private static int targetCount(IndustrialDefinition.StepDefinition step) {
        if (step.targetCount() > 0) {
            return step.targetCount();
        }
        if (step.count() > 0) {
            return step.count();
        }
        return DEFAULT_TARGET_COUNT;
    }

    private static String containerName(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }

    public enum ActionResult {
        SUCCESS,
        MISSING_TARGET,
        INVALID_STEP,
        MISSING_INPUTS,
        TARGET_BLOCKED
    }
}
