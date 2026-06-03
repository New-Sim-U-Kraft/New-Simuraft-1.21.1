package common.cn.kafei.simukraft.citizen;

import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.job.CityJobType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("null")
public final class CitizenJobVisualService {
    private static final int SWING_INTERVAL_TICKS = 10;
    private static final CitizenJobVisualRule EMPTY_RULE = new CitizenJobVisualRule(ItemStack.EMPTY, ItemStack.EMPTY, CitizenJobVisualAction.NONE);
    private static final ConcurrentMap<CityJobType, CitizenJobVisualRule> RULES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, ItemStack> MAIN_HAND_OVERRIDES = new ConcurrentHashMap<>();

    static {
        define(CityJobType.BUILDER, new CitizenJobVisualRule(new ItemStack(Items.COBBLESTONE), ItemStack.EMPTY, CitizenJobVisualAction.SWING_RIGHT_HAND_WHEN_BUILDING));
    }

    private CitizenJobVisualService() {
    }

    // define：注册职业手持物和动作，供后续职业扩展复用。
    public static void define(CityJobType jobType, CitizenJobVisualRule rule) {
        if (jobType == null || rule == null || jobType == CityJobType.UNEMPLOYED) {
            return;
        }
        RULES.put(jobType, rule);
    }

    public static void sync(ServerLevel level, CitizenEntity entity, CitizenData data) {
        if (level == null || entity == null || data == null || data.dead()) {
            return;
        }
        CitizenJobVisualRule rule = RULES.getOrDefault(data.jobType(), EMPTY_RULE);
        ItemStack rightHand = MAIN_HAND_OVERRIDES.getOrDefault(data.uuid(), rule.rightHand());
        applySlot(entity, EquipmentSlot.MAINHAND, rightHand);
        applySlot(entity, EquipmentSlot.OFFHAND, rule.leftHand());
        applyAction(level, entity, data, rule.action());
    }

    public static void setMainHandOverride(UUID citizenId, ItemStack stack) {
        if (citizenId == null) {
            return;
        }
        ItemStack normalized = normalizeVisualStack(stack);
        if (normalized.isEmpty()) {
            MAIN_HAND_OVERRIDES.remove(citizenId);
            return;
        }
        MAIN_HAND_OVERRIDES.put(citizenId, normalized);
    }

    public static void clearMainHandOverride(UUID citizenId) {
        if (citizenId != null) {
            MAIN_HAND_OVERRIDES.remove(citizenId);
        }
    }

    private static void applySlot(CitizenEntity entity, EquipmentSlot slot, ItemStack desired) {
        ItemStack normalized = desired != null ? desired : ItemStack.EMPTY;
        ItemStack current = entity.getItemBySlot(slot);
        if (ItemStack.isSameItemSameComponents(current, normalized) && current.getCount() == normalized.getCount()) {
            return;
        }
        entity.setItemSlot(slot, normalized.copy());
        entity.setDropChance(slot, 0.0F);
    }

    private static void applyAction(ServerLevel level, CitizenEntity entity, CitizenData data, CitizenJobVisualAction action) {
        boolean active = action == CitizenJobVisualAction.SWING_RIGHT_HAND_WHEN_BUILDING && BuilderConstructionService.hasActiveBuildTask(level, data.uuid());
        entity.setHasActiveVisualTask(active);
        if (active) {
            swingRightHand(level, entity);
        }
    }

    private static void swingRightHand(ServerLevel level, CitizenEntity entity) {
        UUID uuid = entity.getUUID();
        int phase = Math.floorMod(uuid.hashCode(), SWING_INTERVAL_TICKS);
        if (level.getGameTime() % SWING_INTERVAL_TICKS == phase) {
            entity.triggerWorkSwing(InteractionHand.MAIN_HAND);
        }
    }

    private static ItemStack normalizeVisualStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return stack.copyWithCount(Math.max(1, stack.getCount()));
    }

    public record CitizenJobVisualRule(ItemStack rightHand, ItemStack leftHand, CitizenJobVisualAction action) {
        public CitizenJobVisualRule {
            rightHand = normalizeVisualStack(rightHand);
            leftHand = normalizeVisualStack(leftHand);
            action = action != null ? action : CitizenJobVisualAction.NONE;
        }
    }

    public enum CitizenJobVisualAction {
        NONE,
        SWING_RIGHT_HAND_WHEN_BUILDING
    }
}
