package common.cn.kafei.simukraft.material;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;

@SuppressWarnings("null")
public final class GenericSlotAccess {
    private GenericSlotAccess() {
    }

    public static ItemStack stackAt(ServerLevel level, BlockPos pos, int slot) {
        if (level == null || pos == null || slot < 0 || !level.isLoaded(pos)) {
            return ItemStack.EMPTY;
        }
        try {
            SlotTarget target = resolve(level, pos);
            if (target == null || !target.validSlot(slot)) {
                return ItemStack.EMPTY;
            }
            return target.stackAt(slot);
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to read slot {} at {}", slot, pos, exception);
            return ItemStack.EMPTY;
        }
    }

    public static int countInsertable(ServerLevel level, BlockPos pos, int slot, ItemStack stack) {
        if (level == null || pos == null || slot < 0 || stack == null || stack.isEmpty() || !level.isLoaded(pos)) {
            return 0;
        }
        try {
            SlotTarget target = resolve(level, pos);
            if (target == null || !target.validSlot(slot)) {
                return 0;
            }
            return target.countInsertable(slot, stack.copy());
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to simulate slot insertion into {} at {}", slot, pos, exception);
            return 0;
        }
    }

    public static ItemStack insert(ServerLevel level, BlockPos pos, int slot, ItemStack stack) {
        if (level == null || pos == null || slot < 0 || stack == null || stack.isEmpty() || !level.isLoaded(pos)) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        try {
            SlotTarget target = resolve(level, pos);
            if (target == null || !target.validSlot(slot)) {
                return stack;
            }
            return target.insert(slot, stack.copy());
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to insert item into slot {} at {}", slot, pos, exception);
            return stack;
        }
    }

    @Nullable
    private static SlotTarget resolve(ServerLevel level, BlockPos pos) {
        Container container = resolveContainer(level, pos);
        if (container != null) {
            return new ContainerSlotTarget(level, pos, container);
        }
        ItemHandlerAccess handlerAccess = resolveItemHandler(level, pos);
        return handlerAccess != null ? new ItemHandlerSlotTarget(handlerAccess.handler()) : null;
    }

    @Nullable
    private static Container resolveContainer(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container chest = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (chest != null) {
                return chest;
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    @Nullable
    private static ItemHandlerAccess resolveItemHandler(ServerLevel level, BlockPos pos) {
        IItemHandler unsided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (hasSlots(unsided)) {
            return new ItemHandlerAccess(unsided);
        }
        for (Direction side : Direction.values()) {
            IItemHandler sided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (hasSlots(sided)) {
                return new ItemHandlerAccess(sided);
            }
        }
        return null;
    }

    private static boolean hasSlots(@Nullable IItemHandler handler) {
        return handler != null && handler.getSlots() > 0;
    }

    private interface SlotTarget {
        boolean validSlot(int slot);

        ItemStack stackAt(int slot);

        int countInsertable(int slot, ItemStack stack);

        ItemStack insert(int slot, ItemStack stack);
    }

    private record ContainerSlotTarget(ServerLevel level, BlockPos pos, Container container) implements SlotTarget {
        @Override
        public boolean validSlot(int slot) {
            return slot >= 0 && slot < container.getContainerSize();
        }

        @Override
        public ItemStack stackAt(int slot) {
            return container.getItem(slot).copy();
        }

        @Override
        public int countInsertable(int slot, ItemStack stack) {
            return stack.getCount() - insertIntoContainer(slot, stack, true).getCount();
        }

        @Override
        public ItemStack insert(int slot, ItemStack stack) {
            return insertIntoContainer(slot, stack, false);
        }

        private ItemStack insertIntoContainer(int slot, ItemStack stack, boolean simulate) {
            if (!validSlot(slot) || !container.canPlaceItem(slot, stack)) {
                return stack;
            }
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) {
                return stack;
            }
            int maxStack = existing.isEmpty()
                    ? Math.min(container.getMaxStackSize(), stack.getMaxStackSize())
                    : Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
            int free = existing.isEmpty() ? maxStack : maxStack - existing.getCount();
            int movable = Math.max(0, Math.min(stack.getCount(), free));
            if (movable <= 0) {
                return stack;
            }
            ItemStack remaining = stack.copy();
            remaining.shrink(movable);
            if (!simulate) {
                if (existing.isEmpty()) {
                    container.setItem(slot, stack.copyWithCount(movable));
                } else {
                    existing.grow(movable);
                }
                container.setChanged();
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    blockEntity.setChanged();
                }
            }
            return remaining;
        }
    }

    private record ItemHandlerSlotTarget(IItemHandler handler) implements SlotTarget {
        @Override
        public boolean validSlot(int slot) {
            return slot >= 0 && slot < handler.getSlots();
        }

        @Override
        public ItemStack stackAt(int slot) {
            return handler.getStackInSlot(slot).copy();
        }

        @Override
        public int countInsertable(int slot, ItemStack stack) {
            return stack.getCount() - handler.insertItem(slot, stack.copy(), true).getCount();
        }

        @Override
        public ItemStack insert(int slot, ItemStack stack) {
            return handler.insertItem(slot, stack.copy(), false);
        }
    }

    private record ItemHandlerAccess(IItemHandler handler) {
    }
}
