package common.cn.kafei.simukraft.farmland;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@SuppressWarnings("null")
final class FarmlandFarmerVisualService {
    private FarmlandFarmerVisualService() {
    }

    static void refresh(ServerLevel level, FarmlandBoxData data, ItemStack tool, boolean active) {
        CitizenData farmer = FarmlandBoxService.findAssignedFarmer(level, data.boxPos());
        if (farmer == null) {
            return;
        }
        CitizenEntity entity = CitizenTeleportService.findCitizenEntity(level, farmer.uuid());
        if (entity != null) {
            apply(entity, tool, active);
        }
    }

    static void apply(CitizenEntity entity, ItemStack tool, boolean active) {
        ItemStack normalized = normalize(tool);
        ItemStack current = entity.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!ItemStack.isSameItemSameComponents(current, normalized) || current.getCount() != normalized.getCount()) {
            entity.setItemSlot(EquipmentSlot.MAINHAND, normalized);
            entity.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
        entity.setHasActiveVisualTask(active);
    }

    static ItemStack toolFor(FarmCrop crop, FarmlandWorkPhase phase) {
        return switch (phase) {
            case DIG_WATER -> new ItemStack(Items.WATER_BUCKET);
            case TILL, HARVEST -> new ItemStack(Items.IRON_HOE);
            case PLANT -> crop != null ? new ItemStack(crop.seed()) : ItemStack.EMPTY;
            case BONEMEAL -> new ItemStack(Items.BONE_MEAL);
        };
    }

    static ItemStack normalize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return stack.copyWithCount(Math.max(1, stack.getCount()));
    }
}
