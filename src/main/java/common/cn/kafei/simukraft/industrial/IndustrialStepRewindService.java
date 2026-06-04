package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.material.GenericSlotAccess;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Objects;

/**
 * IndustrialStepRewindService: 处理工业步骤因外部干预需要回退重试的场景。
 */
final class IndustrialStepRewindService {
    private IndustrialStepRewindService() {
    }

    static void rewindForMachineInput(IndustrialBoxManager manager,
                                      IndustrialBoxData data,
                                      IndustrialDefinition.RecipeDefinition recipe,
                                      IndustrialDefinition.StepDefinition machineStep) {
        int refillStep = refillStartStep(recipe, data.currentStep(), machineStep);
        if (refillStep >= 0 && refillStep < data.currentStep()) {
            data.setCurrentStep(refillStep);
        }
        data.setMachineState("");
        data.setStatusKey("gui.simukraft.industrial.status.machine_needs_input");
        data.setStatusText(machineStep.point());
        manager.persist(data);
    }

    static boolean hasRefillSlotBelowThreshold(IndustrialMachineOperationContext context) {
        if (context == null || context.box() == null || context.recipe() == null || context.step() == null) {
            return false;
        }
        int firstFill = firstRefillStep(context.recipe(), context.box().currentStep(), context.step());
        if (firstFill < 0) {
            return false;
        }
        for (int i = firstFill; i < context.box().currentStep(); i++) {
            IndustrialDefinition.StepDefinition fillStep = context.recipe().steps().get(i);
            if (!isFillStep(fillStep) || !samePoint(fillStep.point(), context.step().point())) {
                break;
            }
            if (isSlotBelowThreshold(context, fillStep)) {
                return true;
            }
        }
        return false;
    }

    /**
     * refillStartStep: 找到同一机器点位前最近一组补料动作的起点。
     */
    private static int refillStartStep(IndustrialDefinition.RecipeDefinition recipe,
                                       int currentStep,
                                       IndustrialDefinition.StepDefinition machineStep) {
        int firstFill = firstRefillStep(recipe, currentStep, machineStep);
        if (firstFill < 0) {
            return -1;
        }
        int start = firstFill;
        for (int i = firstFill - 1; i >= 0; i--) {
            if (!isRefillPrepStep(recipe.steps().get(i))) {
                break;
            }
            start = i;
        }
        return start;
    }

    private static int firstRefillStep(IndustrialDefinition.RecipeDefinition recipe,
                                       int currentStep,
                                       IndustrialDefinition.StepDefinition machineStep) {
        int firstFill = -1;
        for (int i = currentStep - 1; i >= 0; i--) {
            IndustrialDefinition.StepDefinition candidate = recipe.steps().get(i);
            if (isFillStep(candidate) && samePoint(candidate.point(), machineStep.point())) {
                firstFill = i;
                continue;
            }
            if (firstFill >= 0) {
                break;
            }
        }
        return firstFill;
    }

    private static boolean isSlotBelowThreshold(IndustrialMachineOperationContext context, IndustrialDefinition.StepDefinition fillStep) {
        if (fillStep.slot() < 0 || fillStep.item().isBlank()) {
            return false;
        }
        ItemStack expected = IndustrialInventoryService.stackForItem(fillStep.item(), 1);
        if (expected.isEmpty()) {
            return false;
        }
        ItemStack current = GenericSlotAccess.stackAt(context.level(), context.machinePos(), fillStep.slot());
        if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, expected)) {
            return true;
        }
        return fillStep.thresholdCount() >= 0 && current.getCount() <= fillStep.thresholdCount();
    }

    private static boolean isFillStep(IndustrialDefinition.StepDefinition step) {
        String type = step.type().toLowerCase(Locale.ROOT);
        return type.equals("fill_item") || type.equals("fill_slot") || type.equals("refill_item") || type.equals("refill_slot");
    }

    private static boolean isRefillPrepStep(IndustrialDefinition.StepDefinition step) {
        String type = step.type().toLowerCase(Locale.ROOT);
        return type.equals("move_to_container")
                || type.equals("move_to_chest")
                || type.equals("look_at_container")
                || type.equals("look_at_chest")
                || type.equals("inspect_container")
                || type.equals("open_container")
                || type.equals("set_held_item")
                || type.equals("move_to")
                || type.equals("look_at");
    }

    private static boolean samePoint(String first, String second) {
        return Objects.equals(first == null ? "" : first, second == null ? "" : second);
    }
}
