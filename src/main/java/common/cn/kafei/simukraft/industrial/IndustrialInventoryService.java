package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.material.GenericContainerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("null")
public final class IndustrialInventoryService {
    private IndustrialInventoryService() {
    }

    public static boolean hasInputs(ServerLevel level, List<BlockPos> containers, List<IndustrialDefinition.ItemRequirement> inputs) {
        for (IndustrialDefinition.ItemRequirement input : inputs) {
            if (countItem(level, containers, input) < input.count()) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasOutputSpace(ServerLevel level, List<BlockPos> containers, List<ItemStack> outputs) {
        List<ItemStack> remaining = outputs.stream().map(ItemStack::copy).toList();
        for (BlockPos container : containers) {
            if (!GenericContainerAccess.isContainer(level, container)) {
                continue;
            }
            remaining = GenericContainerAccess.simulateInsert(level, container, remaining);
            if (remaining.isEmpty()) {
                return true;
            }
        }
        return remaining.isEmpty();
    }

    public static boolean craftRecipe(ServerLevel level,
                                      List<BlockPos> inputContainers,
                                      List<BlockPos> outputContainers,
                                      IndustrialDefinition.RecipeDefinition recipe,
                                      double outputMultiplier,
                                      RandomSource random) {
        return craftRecipe(level, inputContainers, outputContainers, recipe, outputMultiplier, random, 1);
    }

    /**
     * craftAvailableRecipe: 按当前可消耗输入数量批量执行同一个配方。
     */
    public static boolean craftAvailableRecipe(ServerLevel level,
                                               List<BlockPos> inputContainers,
                                               List<BlockPos> outputContainers,
                                               IndustrialDefinition.RecipeDefinition recipe,
                                               double outputMultiplier,
                                               RandomSource random) {
        int craftCount = availableCraftCount(level, inputContainers, recipe);
        return craftCount > 0 && craftRecipe(level, inputContainers, outputContainers, recipe, outputMultiplier, random, craftCount);
    }

    private static boolean craftRecipe(ServerLevel level,
                                       List<BlockPos> inputContainers,
                                       List<BlockPos> outputContainers,
                                       IndustrialDefinition.RecipeDefinition recipe,
                                       double outputMultiplier,
                                       RandomSource random,
                                       int craftCount) {
        if (level == null || recipe == null || craftCount <= 0 || !hasInputsForCrafts(level, inputContainers, recipe.inputs(), craftCount)) {
            return false;
        }
        List<ItemStack> outputs = buildOutputs(recipe, outputMultiplier, random, craftCount);
        List<ItemStack> consumed = new ArrayList<>();
        for (IndustrialDefinition.ItemRequirement input : recipe.inputs()) {
            if (!input.consume()) {
                continue;
            }
            int consumeCount = safeRequiredCount(input, craftCount);
            if (!consumeItem(level, input, inputContainers, consumeCount)) {
                insertItems(level, inputContainers, consumed);
                return false;
            }
            ItemStack stack = stackForItem(input.itemId(), input.potionId(), consumeCount);
            if (!stack.isEmpty()) {
                consumed.add(stack);
            }
        }
        if (!hasOutputSpace(level, outputContainers, outputs)) {
            insertItems(level, inputContainers, consumed);
            return false;
        }
        if (!insertOutputs(level, outputContainers, outputs)) {
            insertItems(level, inputContainers, consumed);
            return false;
        }
        return true;
    }

    public static List<ItemStack> buildOutputs(IndustrialDefinition.RecipeDefinition recipe, double outputMultiplier, RandomSource random) {
        return buildOutputs(recipe, outputMultiplier, random, 1);
    }

    private static List<ItemStack> buildOutputs(IndustrialDefinition.RecipeDefinition recipe, double outputMultiplier, RandomSource random, int craftCount) {
        List<ItemStack> outputs = new ArrayList<>();
        RandomSource safeRandom = random != null ? random : RandomSource.create();
        double safeMultiplier = Math.max(0.0D, outputMultiplier);
        for (int craft = 0; craft < craftCount; craft++) {
            for (IndustrialDefinition.ProductOutput output : recipe.outputs()) {
                if (safeRandom.nextDouble() > output.probability()) {
                    continue;
                }
                int randomBonus = output.randomRange() > 0 ? safeRandom.nextInt(output.randomRange()) : 0;
                int amount = output.baseAmount() + randomBonus;
                if (!output.ignoreMultiplier()) {
                    amount = Math.max(1, (int) Math.floor(amount * safeMultiplier));
                }
                ItemStack stack = IndustrialItemStackSpec.of(output.itemId(), output.potionId()).stack(amount);
                if (!stack.isEmpty()) {
                    addOutputStack(outputs, stack);
                }
            }
        }
        return List.copyOf(outputs);
    }

    public static ItemStack stackForItem(String itemId, int count) {
        return IndustrialItemStackSpec.of(itemId, "").stack(count);
    }

    public static ItemStack stackForItem(String itemId, String potionId, int count) {
        return IndustrialItemStackSpec.of(itemId, potionId).stack(count);
    }

    public static boolean consumeInput(ServerLevel level, List<BlockPos> containers, String itemId, String potionId, int count) {
        if (count <= 0) {
            return true;
        }
        IndustrialDefinition.ItemRequirement request = new IndustrialDefinition.ItemRequirement(itemId, count, true, potionId != null ? potionId : "");
        return hasInputs(level, containers, List.of(request)) && consumeItem(level, request, containers, count);
    }

    public static int countInput(ServerLevel level, List<BlockPos> containers, String itemId, String potionId) {
        IndustrialDefinition.ItemRequirement request = new IndustrialDefinition.ItemRequirement(itemId, 1, true, potionId != null ? potionId : "");
        return countItem(level, containers, request);
    }

    public static boolean insertItem(ServerLevel level, List<BlockPos> containers, ItemStack stack) {
        if (level == null || containers == null || containers.isEmpty() || stack == null || stack.isEmpty()) {
            return false;
        }
        return insertOutputs(level, containers, List.of(stack.copy()));
    }

    public static boolean insertItems(ServerLevel level, List<BlockPos> containers, List<ItemStack> stacks) {
        if (level == null || containers == null || containers.isEmpty() || stacks == null || stacks.isEmpty()) {
            return false;
        }
        List<ItemStack> outputs = stacks.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
        return !outputs.isEmpty() && hasOutputSpace(level, containers, outputs) && insertOutputs(level, containers, outputs);
    }

    private static int countItem(ServerLevel level, List<BlockPos> containers, IndustrialDefinition.ItemRequirement input) {
        IndustrialItemStackSpec spec = IndustrialItemStackSpec.of(input.itemId(), input.potionId());
        int count = 0;
        for (BlockPos container : containers) {
            if (!GenericContainerAccess.isContainer(level, container)) {
                continue;
            }
            for (GenericContainerAccess.SlotSnapshot snapshot : GenericContainerAccess.snapshotSlots(level, container)) {
                if (spec.matches(snapshot.stack())) {
                    count += snapshot.stack().getCount();
                }
            }
        }
        return count;
    }

    /**
     * availableCraftCount: 计算所有输入共同允许的最大合成次数。
     */
    private static int availableCraftCount(ServerLevel level, List<BlockPos> containers, IndustrialDefinition.RecipeDefinition recipe) {
        if (level == null || recipe == null || recipe.inputs().isEmpty()) {
            return 0;
        }
        int craftCount = Integer.MAX_VALUE;
        boolean hasConsumableInput = false;
        for (IndustrialDefinition.ItemRequirement input : recipe.inputs()) {
            int available = countItem(level, containers, input);
            if (input.consume()) {
                hasConsumableInput = true;
                craftCount = Math.min(craftCount, available / Math.max(1, input.count()));
            } else if (available < input.count()) {
                return 0;
            }
        }
        return hasConsumableInput ? Math.max(0, craftCount) : hasInputs(level, containers, recipe.inputs()) ? 1 : 0;
    }

    private static boolean hasInputsForCrafts(ServerLevel level, List<BlockPos> containers, List<IndustrialDefinition.ItemRequirement> inputs, int craftCount) {
        for (IndustrialDefinition.ItemRequirement input : inputs) {
            if (countItem(level, containers, input) < safeRequiredCount(input, craftCount)) {
                return false;
            }
        }
        return true;
    }

    private static int safeRequiredCount(IndustrialDefinition.ItemRequirement input, int craftCount) {
        int baseCount = Math.max(1, input.count());
        if (!input.consume()) {
            return baseCount;
        }
        long required = (long) baseCount * Math.max(1, craftCount);
        return required > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) required;
    }

    private static boolean consumeItem(ServerLevel level, IndustrialDefinition.ItemRequirement input, List<BlockPos> containers, int count) {
        if (count <= 0) {
            return false;
        }
        IndustrialItemStackSpec spec = IndustrialItemStackSpec.of(input.itemId(), input.potionId());
        int remaining = count;
        for (BlockPos container : containers) {
            if (!GenericContainerAccess.isContainer(level, container)) {
                continue;
            }
            for (GenericContainerAccess.SlotSnapshot snapshot : GenericContainerAccess.snapshotSlots(level, container)) {
                if (!spec.matches(snapshot.stack())) {
                    continue;
                }
                int slotCount = Math.min(snapshot.stack().getCount(), remaining);
                for (int i = 0; i < slotCount; i++) {
                    if (!GenericContainerAccess.consumeSingleItemAtSlot(level, container, snapshot.slot(), snapshot.access(), snapshot.side(), spec::matches)) {
                        return false;
                    }
                    remaining--;
                    if (remaining <= 0) {
                        return true;
                    }
                }
            }
        }
        return remaining <= 0;
    }

    private static void addOutputStack(List<ItemStack> outputs, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (ItemStack existing : outputs) {
            if (remaining.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int movable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            if (movable > 0) {
                existing.grow(movable);
                remaining.shrink(movable);
            }
        }
        while (!remaining.isEmpty()) {
            int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            outputs.add(remaining.copyWithCount(amount));
            remaining.shrink(amount);
        }
    }

    private static boolean insertOutputs(ServerLevel level, List<BlockPos> containers, List<ItemStack> outputs) {
        for (ItemStack output : outputs) {
            ItemStack remaining = output.copy();
            for (BlockPos container : containers) {
                if (remaining.isEmpty()) {
                    break;
                }
                if (GenericContainerAccess.isContainer(level, container)) {
                    remaining = GenericContainerAccess.insert(level, container, remaining);
                }
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

}
