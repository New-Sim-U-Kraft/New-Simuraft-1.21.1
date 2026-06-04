package common.cn.kafei.simukraft.industrial;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public interface IndustrialMachineAdapter {
    String id();

    boolean matches(IndustrialMachineOperationContext context);

    boolean canAcceptInputs(IndustrialMachineOperationContext context, List<ItemStack> inputs);

    boolean insertInputs(IndustrialMachineOperationContext context, List<ItemStack> inputs);

    Map<String, Integer> countOutputs(IndustrialMachineOperationContext context, Map<String, IndustrialItemStackSpec> expectedOutputs);

    List<ItemStack> collectOutputs(IndustrialMachineOperationContext context,
                                   Map<String, IndustrialItemStackSpec> expectedOutputs,
                                   Map<String, Integer> baseline,
                                   boolean simulate);

    /**
     * isWaitingForMissingInput: 判断机器是否因输入/燃料被移走而无法继续产出。
     */
    default boolean isWaitingForMissingInput(IndustrialMachineOperationContext context, Map<String, IndustrialItemStackSpec> expectedOutputs) {
        return false;
    }

    default void abort(IndustrialMachineOperationContext context, String reason) {
    }
}
