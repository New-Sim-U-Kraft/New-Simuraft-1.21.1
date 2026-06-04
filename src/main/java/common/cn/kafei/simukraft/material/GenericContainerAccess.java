package common.cn.kafei.simukraft.material;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@SuppressWarnings("null")
/**
 * 通用容器访问：优先支持 NeoForge IItemHandler，回退原版 Container。
 */
public final class GenericContainerAccess {
    private GenericContainerAccess() {
    }

    public static boolean isContainer(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return false;
        }
        return resolveContainer(level, pos) != null || resolveItemHandler(level, pos) != null;
    }

    public static List<SlotSnapshot> snapshotSlots(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return List.of();
        }
        Container chestContainer = resolveChestContainer(level, pos);
        if (chestContainer != null) {
            return snapshotContainer(chestContainer);
        }
        ItemHandlerAccess handlerAccess = resolveItemHandler(level, pos);
        if (handlerAccess != null) {
            return snapshotItemHandler(handlerAccess);
        }
        Container container = resolveContainer(level, pos);
        if (container == null) {
            return List.of();
        }
        return snapshotContainer(container);
    }

    public static boolean consumeSingleItemAtSlot(ServerLevel level, BlockPos pos, int slot, SlotAccess access, @Nullable Direction side, Item item) {
        return consumeSingleItemAtSlot(level, pos, slot, access, side, stack -> !stack.isEmpty() && stack.getItem() == item);
    }

    public static boolean consumeSingleItemAtSlot(ServerLevel level, BlockPos pos, int slot, SlotAccess access, @Nullable Direction side, Predicate<ItemStack> matcher) {
        if (level == null || pos == null || matcher == null || !level.isLoaded(pos)) {
            return false;
        }
        try {
            return switch (access) {
                case ITEM_HANDLER -> consumeFromItemHandler(level, pos, side, slot, matcher);
                case CONTAINER -> consumeFromContainer(level, pos, slot, matcher);
            };
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to consume material from container at {}", pos, exception);
            return false;
        }
    }

    public static ItemStack stackAtSlot(ServerLevel level, BlockPos pos, int slot, SlotAccess access, @Nullable Direction side) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return ItemStack.EMPTY;
        }
        try {
            return switch (access) {
                case ITEM_HANDLER -> stackFromItemHandler(level, pos, side, slot);
                case CONTAINER -> stackFromContainer(level, pos, slot);
            };
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to read material slot from container at {}", pos, exception);
            return ItemStack.EMPTY;
        }
    }

    /**
     * 向容器插入物品，优先 IItemHandler，回退原版 Container。返回未能放入的剩余物（调用方负责掉落兜底）。
     */
    public static ItemStack insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (level == null || pos == null || stack == null || stack.isEmpty() || !level.isLoaded(pos)) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        try {
            ItemHandlerAccess handlerAccess = resolveItemHandler(level, pos);
            if (handlerAccess != null) {
                return net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(handlerAccess.handler(), stack.copy(), false);
            }
            Container container = resolveContainer(level, pos);
            if (container != null) {
                return insertIntoContainer(level, pos, container, stack.copy());
            }
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to insert item into container at {}", pos, exception);
        }
        return stack;
    }

    public static List<ItemStack> simulateInsert(ServerLevel level, BlockPos pos, List<ItemStack> stacks) {
        List<ItemStack> remaining = normalizeStacks(stacks);
        if (level == null || pos == null || remaining.isEmpty() || !level.isLoaded(pos)) {
            return remaining;
        }
        try {
            ItemHandlerAccess handlerAccess = resolveItemHandler(level, pos);
            if (handlerAccess != null) {
                return simulateInsertIntoItemHandler(handlerAccess.handler(), remaining);
            }
            Container container = resolveContainer(level, pos);
            if (container != null) {
                return simulateInsertIntoContainer(container, remaining);
            }
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to simulate batch insertion into container at {}", pos, exception);
        }
        return remaining;
    }

    public static int countInsertable(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (level == null || pos == null || stack == null || stack.isEmpty() || !level.isLoaded(pos)) {
            return 0;
        }
        try {
            ItemHandlerAccess handlerAccess = resolveItemHandler(level, pos);
            if (handlerAccess != null) {
                ItemStack remaining = net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(handlerAccess.handler(), stack.copy(), true);
                return stack.getCount() - remaining.getCount();
            }
            Container container = resolveContainer(level, pos);
            if (container != null) {
                return countInsertableInContainer(container, stack.copy());
            }
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to simulate item insertion into container at {}", pos, exception);
        }
        return 0;
    }

    private static List<ItemStack> normalizeStacks(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                result.add(stack.copy());
            }
        }
        return List.copyOf(result);
    }

    private static List<ItemStack> simulateInsertIntoItemHandler(IItemHandler handler, List<ItemStack> stacks) {
        if (handler == null || handler.getSlots() <= 0) {
            return stacks;
        }
        List<ItemStack> virtualSlots = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            virtualSlots.add(handler.getStackInSlot(slot).copy());
        }
        List<ItemStack> remainingStacks = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            mergeIntoVirtualItemHandler(handler, virtualSlots, remaining, false);
            mergeIntoVirtualItemHandler(handler, virtualSlots, remaining, true);
            if (!remaining.isEmpty()) {
                remainingStacks.add(remaining);
            }
        }
        return List.copyOf(remainingStacks);
    }

    private static void mergeIntoVirtualItemHandler(IItemHandler handler, List<ItemStack> virtualSlots, ItemStack remaining, boolean emptySlots) {
        for (int slot = 0; slot < virtualSlots.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = virtualSlots.get(slot);
            if (emptySlots != existing.isEmpty() || !handler.isItemValid(slot, remaining)) {
                continue;
            }
            if (existing.isEmpty()) {
                int movable = Math.min(remaining.getCount(), Math.min(handler.getSlotLimit(slot), remaining.getMaxStackSize()));
                if (movable > 0) {
                    virtualSlots.set(slot, remaining.copyWithCount(movable));
                    remaining.shrink(movable);
                }
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int maxStack = Math.min(handler.getSlotLimit(slot), existing.getMaxStackSize());
            int movable = Math.min(remaining.getCount(), maxStack - existing.getCount());
            if (movable > 0) {
                existing.grow(movable);
                remaining.shrink(movable);
            }
        }
    }

    private static List<ItemStack> simulateInsertIntoContainer(Container container, List<ItemStack> stacks) {
        if (container == null || container.getContainerSize() <= 0) {
            return stacks;
        }
        List<ItemStack> virtualSlots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            virtualSlots.add(container.getItem(slot).copy());
        }
        List<ItemStack> remainingStacks = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            mergeIntoVirtualContainer(container, virtualSlots, remaining, false);
            mergeIntoVirtualContainer(container, virtualSlots, remaining, true);
            if (!remaining.isEmpty()) {
                remainingStacks.add(remaining);
            }
        }
        return List.copyOf(remainingStacks);
    }

    private static void mergeIntoVirtualContainer(Container container, List<ItemStack> virtualSlots, ItemStack remaining, boolean emptySlots) {
        for (int slot = 0; slot < virtualSlots.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = virtualSlots.get(slot);
            if (emptySlots != existing.isEmpty() || !container.canPlaceItem(slot, remaining)) {
                continue;
            }
            if (existing.isEmpty()) {
                int maxStack = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                int movable = Math.min(remaining.getCount(), maxStack);
                if (movable > 0) {
                    virtualSlots.set(slot, remaining.copyWithCount(movable));
                    remaining.shrink(movable);
                }
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int maxStack = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
            int movable = Math.min(remaining.getCount(), maxStack - existing.getCount());
            if (movable > 0) {
                existing.grow(movable);
                remaining.shrink(movable);
            }
        }
    }

    private static int countInsertableInContainer(Container container, ItemStack stack) {
        int remaining = stack.getCount();
        int size = container.getContainerSize();
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int maxStack = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
            remaining -= Math.max(0, Math.min(remaining, maxStack - existing.getCount()));
        }
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty()) {
                continue;
            }
            int maxStack = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
            remaining -= Math.max(0, Math.min(remaining, maxStack));
        }
        return stack.getCount() - remaining;
    }

    private static ItemStack insertIntoContainer(ServerLevel level, BlockPos pos, Container container, ItemStack stack) {
        ItemStack remaining = stack;
        int size = container.getContainerSize();
        // 先并入同物品的已有槽位，再放入空槽，最大限度复用堆叠空间。
        for (int slot = 0; slot < size && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int maxStack = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
            int movable = Math.min(remaining.getCount(), maxStack - existing.getCount());
            if (movable > 0) {
                existing.grow(movable);
                remaining.shrink(movable);
            }
        }
        for (int slot = 0; slot < size && !remaining.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            int maxStack = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
            ItemStack placed = remaining.copy();
            int amount = Math.min(remaining.getCount(), maxStack);
            placed.setCount(amount);
            container.setItem(slot, placed);
            remaining.shrink(amount);
        }
        container.setChanged();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.setChanged();
        }
        return remaining;
    }

    public static BlockPos canonicalContainerPos(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return pos;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE)) {
            return pos.immutable();
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return pos.immutable();
        }
        BlockPos otherHalf = pos.relative(ChestBlock.getConnectedDirection(state));
        if (!level.isLoaded(otherHalf)) {
            return pos.immutable();
        }
        BlockState otherState = level.getBlockState(otherHalf);
        if (otherState.getBlock() != state.getBlock() || !otherState.hasProperty(ChestBlock.TYPE)) {
            return pos.immutable();
        }
        return comparePositions(pos, otherHalf) <= 0 ? pos.immutable() : otherHalf.immutable();
    }

    private static List<SlotSnapshot> snapshotItemHandler(ItemHandlerAccess handlerAccess) {
        List<SlotSnapshot> snapshots = new ArrayList<>();
        IItemHandler handler = handlerAccess.handler();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                snapshots.add(new SlotSnapshot(slot, SlotAccess.ITEM_HANDLER, handlerAccess.side(), stack.copy()));
            }
        }
        return List.copyOf(snapshots);
    }

    private static List<SlotSnapshot> snapshotContainer(Container container) {
        List<SlotSnapshot> snapshots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                snapshots.add(new SlotSnapshot(slot, SlotAccess.CONTAINER, null, stack.copy()));
            }
        }
        return List.copyOf(snapshots);
    }

    @Nullable
    private static ItemHandlerAccess resolveItemHandler(ServerLevel level, BlockPos pos) {
        IItemHandler unsided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (hasSlots(unsided)) {
            return new ItemHandlerAccess(unsided, null);
        }
        for (Direction side : Direction.values()) {
            IItemHandler sided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (hasSlots(sided)) {
                return new ItemHandlerAccess(sided, side);
            }
        }
        return null;
    }

    @Nullable
    private static Container resolveContainer(ServerLevel level, BlockPos pos) {
        Container chestContainer = resolveChestContainer(level, pos);
        if (chestContainer != null) {
            return chestContainer;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    @Nullable
    private static Container resolveChestContainer(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, true);
        }
        return null;
    }

    private static boolean hasSlots(@Nullable IItemHandler handler) {
        return handler != null && handler.getSlots() > 0;
    }

    private static boolean consumeFromItemHandler(ServerLevel level, BlockPos pos, @Nullable Direction side, int slot, Predicate<ItemStack> matcher) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return false;
        }
        ItemStack current = handler.getStackInSlot(slot);
        if (current.isEmpty() || !matcher.test(current)) {
            return false;
        }
        ItemStack extracted = handler.extractItem(slot, 1, false);
        return !extracted.isEmpty() && matcher.test(extracted);
    }

    private static ItemStack stackFromItemHandler(ServerLevel level, BlockPos pos, @Nullable Direction side, int slot) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return ItemStack.EMPTY;
        }
        return handler.getStackInSlot(slot).copy();
    }

    private static boolean consumeFromContainer(ServerLevel level, BlockPos pos, int slot, Predicate<ItemStack> matcher) {
        Container container = resolveContainer(level, pos);
        if (container == null || slot < 0 || slot >= container.getContainerSize()) {
            return false;
        }
        ItemStack current = container.getItem(slot);
        if (current.isEmpty() || !matcher.test(current)) {
            return false;
        }
        ItemStack removed = container.removeItem(slot, 1);
        if (removed.isEmpty() || !matcher.test(removed)) {
            return false;
        }
        container.setChanged();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.setChanged();
        }
        return true;
    }

    private static ItemStack stackFromContainer(ServerLevel level, BlockPos pos, int slot) {
        Container container = resolveContainer(level, pos);
        if (container == null || slot < 0 || slot >= container.getContainerSize()) {
            return ItemStack.EMPTY;
        }
        return container.getItem(slot).copy();
    }

    private static int comparePositions(BlockPos first, BlockPos second) {
        int y = Integer.compare(first.getY(), second.getY());
        if (y != 0) {
            return y;
        }
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) {
            return x;
        }
        return Integer.compare(first.getZ(), second.getZ());
    }

    public enum SlotAccess {
        ITEM_HANDLER,
        CONTAINER
    }

    public record SlotSnapshot(int slot, SlotAccess access, @Nullable Direction side, ItemStack stack) {
    }

    private record ItemHandlerAccess(IItemHandler handler, @Nullable Direction side) {
    }
}
