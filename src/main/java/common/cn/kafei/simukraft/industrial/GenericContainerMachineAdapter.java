package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.material.GenericContainerAccess;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("null")
final class GenericContainerMachineAdapter implements IndustrialMachineAdapter {
    @Override
    public String id() {
        return "simukraft:item_handler";
    }

    @Override
    public boolean matches(IndustrialMachineOperationContext context) {
        return GenericContainerAccess.isContainer(context.level(), context.machinePos());
    }

    @Override
    public boolean canAcceptInputs(IndustrialMachineOperationContext context, List<ItemStack> inputs) {
        return GenericContainerAccess.simulateInsert(context.level(), context.machinePos(), inputs).isEmpty();
    }

    @Override
    public boolean insertInputs(IndustrialMachineOperationContext context, List<ItemStack> inputs) {
        for (ItemStack input : inputs) {
            ItemStack remaining = GenericContainerAccess.insert(context.level(), context.machinePos(), input.copy());
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, Integer> countOutputs(IndustrialMachineOperationContext context, Map<String, IndustrialItemStackSpec> expectedOutputs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GenericContainerAccess.SlotSnapshot snapshot : GenericContainerAccess.snapshotSlots(context.level(), context.machinePos())) {
            String key = IndustrialMachineOutputMatcher.matchingKey(snapshot.stack(), expectedOutputs);
            if (!key.isBlank()) {
                counts.merge(key, snapshot.stack().getCount(), Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    @Override
    public List<ItemStack> collectOutputs(IndustrialMachineOperationContext context,
                                          Map<String, IndustrialItemStackSpec> expectedOutputs,
                                          Map<String, Integer> baseline,
                                          boolean simulate) {
        Map<String, Integer> remainingByKey = new LinkedHashMap<>();
        Map<String, Integer> current = countOutputs(context, expectedOutputs);
        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            int amount = entry.getValue() - baseline.getOrDefault(entry.getKey(), 0);
            if (amount > 0) {
                remainingByKey.put(entry.getKey(), amount);
            }
        }
        List<ItemStack> collected = new ArrayList<>();
        if (remainingByKey.isEmpty()) {
            return List.of();
        }
        for (GenericContainerAccess.SlotSnapshot snapshot : GenericContainerAccess.snapshotSlots(context.level(), context.machinePos())) {
            String key = IndustrialMachineOutputMatcher.matchingKey(snapshot.stack(), expectedOutputs);
            int remaining = remainingByKey.getOrDefault(key, 0);
            if (key.isBlank() || remaining <= 0) {
                continue;
            }
            int amount = Math.min(snapshot.stack().getCount(), remaining);
            ItemStack stack = snapshot.stack().copyWithCount(amount);
            if (!simulate) {
                int extracted = 0;
                for (int i = 0; i < amount; i++) {
                    if (!GenericContainerAccess.consumeSingleItemAtSlot(context.level(), context.machinePos(), snapshot.slot(), snapshot.access(), snapshot.side(), expectedOutputs.get(key)::matches)) {
                        break;
                    }
                    extracted++;
                }
                stack.setCount(extracted);
            }
            if (!stack.isEmpty()) {
                IndustrialMachineOutputMatcher.addCollected(collected, stack);
                remainingByKey.put(key, remaining - stack.getCount());
            }
        }
        return List.copyOf(collected);
    }

    @Override
    public boolean isWaitingForMissingInput(IndustrialMachineOperationContext context, Map<String, IndustrialItemStackSpec> expectedOutputs) {
        return IndustrialStepRewindService.hasRefillSlotBelowThreshold(context);
    }
}
