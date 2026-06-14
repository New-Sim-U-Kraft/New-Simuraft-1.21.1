package client.cn.kafei.simukraft.client.renderer;

import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.material.NpcWorkMaterialService;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class CitizenWorkStatusDisplayRegistry {
    public static final int PRIORITY_LIFE_STATE = 1100;
    public static final int PRIORITY_SELF_FEEDING = 1000;
    public static final int PRIORITY_PROBLEM = 900;
    public static final int PRIORITY_RESTING = 1000;
    public static final int PRIORITY_ACTIVE_WORK = 800;
    public static final int PRIORITY_FINISHED = 500;
    public static final int PRIORITY_CUSTOM_LABEL = 400;
    public static final int PRIORITY_BASE_WORK_STATUS = 100;

    private static final String WORK_STATUS_IDLE = "work_status.idle";
    private static final String WORK_STATUS_RESTING = "work_status.resting";
    private static final String WORK_STATUS_DEAD = "work_status.dead";
    private static final String SELF_FEEDING_PREFIX = "gui.npc.status.";
    private static final String INDUSTRIAL_STATUS_PREFIX = "gui.simukraft.industrial.status.";
    private static final String COMMERCIAL_STATUS_PREFIX = "gui.simukraft.commercial.status.";

    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static volatile List<Entry> SORTED_SNAPSHOT = List.of();

    static {
        register("dead_status", PRIORITY_LIFE_STATE, workStatus(WORK_STATUS_DEAD));
        registerLabelPrefix("self_feeding", PRIORITY_SELF_FEEDING, SELF_FEEDING_PREFIX);
        register("missing_material_status", PRIORITY_PROBLEM, CitizenWorkStatusDisplayRegistry::missingMaterialStatus);
        register("problem_status_key", PRIORITY_PROBLEM, statusLabel(CitizenWorkStatusDisplayRegistry::isProblemStatusKey));
        register("problem_status_text", PRIORITY_PROBLEM, statusLabel(CitizenWorkStatusDisplayRegistry::isProblemStatusText));
        register("resting_status_key", PRIORITY_RESTING, statusLabel(CitizenWorkStatusDisplayRegistry::isRestingStatusKey));
        register("resting_status_text", PRIORITY_RESTING, statusLabel(CitizenWorkStatusDisplayRegistry::isRestingStatusText));
        register("resting_work_status", PRIORITY_RESTING, workStatusWhenLabelBlank(WORK_STATUS_RESTING));
        register("active_status_key", PRIORITY_ACTIVE_WORK, statusLabel(CitizenWorkStatusDisplayRegistry::isKnownStatusKey));
        register("active_status_text", PRIORITY_ACTIVE_WORK, statusLabel(CitizenWorkStatusDisplayRegistry::isActiveWorkText));
        register("finished_status_text", PRIORITY_FINISHED, statusLabel(CitizenWorkStatusDisplayRegistry::isFinishedStatusText));
        register("custom_status_label", PRIORITY_CUSTOM_LABEL, statusLabel(label -> true));
        register("base_work_status", PRIORITY_BASE_WORK_STATUS, context -> {
            String workStatus = context.workStatus();
            return Optional.of(localizedOrLiteral(isBlank(workStatus) ? WORK_STATUS_IDLE : workStatus));
        });
        rebuildSnapshot();
    }

    private static void rebuildSnapshot() {
        List<Entry> s = new ArrayList<>(ENTRIES);
        s.sort(Comparator.comparingInt(Entry::priority).reversed());
        SORTED_SNAPSHOT = List.copyOf(s);
    }

    private CitizenWorkStatusDisplayRegistry() {
    }

    /** register: 注册一类可显示在 NPC 工作状态行的文字来源。 */
    public static void register(String id, int priority, WorkStatusProvider provider) {
        if (id == null || id.isBlank() || provider == null) {
            return;
        }
        ENTRIES.removeIf(entry -> entry.id().equals(id));
        ENTRIES.add(new Entry(id, priority, provider));
        rebuildSnapshot();
    }

    /** registerLabelPrefix: 按 statusLabel 前缀注册一组工作状态文本。 */
    public static void registerLabelPrefix(String id, int priority, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        register(id, priority, statusLabel(label -> label.startsWith(prefix)));
    }

    /** unregister: 移除指定工作状态文字来源。 */
    public static void unregister(String id) {
        if (id != null && !id.isBlank()) {
            ENTRIES.removeIf(entry -> entry.id().equals(id));
            rebuildSnapshot();
        }
    }

    /** resolve: 按优先级返回当前 NPC 工作状态行应该显示的文字。 */
    public static Component resolve(CitizenEntity entity) {
        if (entity == null) {
            return Component.translatable(WORK_STATUS_IDLE);
        }
        return resolve(new WorkStatusContext(entity, entity.getWorkStatus(), entity.getStatusLabel()));
    }

    /** resolve: 供居民信息界面等非实体渲染入口复用同一套工作状态文字规则。 */
    public static Component resolve(String workStatus, String statusLabel) {
        return resolve(new WorkStatusContext(null, workStatus, statusLabel));
    }

    private static Component resolve(WorkStatusContext context) {
        for (Entry entry : SORTED_SNAPSHOT) {
            Optional<Component> component = entry.provider().resolve(context)
                    .filter(text -> text != null && !text.getString().isBlank());
            if (component.isPresent()) {
                return component.get();
            }
        }
        return Component.translatable(WORK_STATUS_IDLE);
    }

    /** statusLabel: 把实体 statusLabel 按条件解析为工作状态文字。 */
    private static WorkStatusProvider statusLabel(Predicate<String> matcher) {
        return context -> {
            String statusLabel = context.statusLabel();
            if (isBlank(statusLabel) || !matcher.test(statusLabel)) {
                return Optional.empty();
            }
            return Optional.of(localizedOrLiteral(statusLabel));
        };
    }

    /** workStatus: 匹配基础 workStatus 文本。 */
    private static WorkStatusProvider workStatus(String expectedStatus) {
        return context -> expectedStatus.equals(context.workStatus()) ? Optional.of(localizedOrLiteral(expectedStatus)) : Optional.empty();
    }

    /** workStatusWhenLabelBlank: 没有更具体状态时才显示基础 workStatus。 */
    private static WorkStatusProvider workStatusWhenLabelBlank(String expectedStatus) {
        return context -> isBlank(context.statusLabel()) && expectedStatus.equals(context.workStatus())
                ? Optional.of(localizedOrLiteral(expectedStatus))
                : Optional.empty();
    }

    /** localizedOrLiteral: 翻译键走本地化，普通文本保持原样。 */
    private static Component localizedOrLiteral(String value) {
        if (isBlank(value)) {
            return Component.empty();
        }
        return I18n.exists(value) ? Component.translatable(value) : Component.literal(value);
    }

    /** missingMaterialStatus: 客户端按本地语言显示缺少的材料名。 */
    private static Optional<Component> missingMaterialStatus(WorkStatusContext context) {
        String label = context.statusLabel();
        if (isBlank(label) || !label.startsWith(NpcWorkMaterialService.MISSING_MATERIAL_STATUS_PREFIX)) {
            return Optional.empty();
        }
        String itemId = label.substring(NpcWorkMaterialService.MISSING_MATERIAL_STATUS_PREFIX.length());
        return Optional.of(Component.translatable("work_status.missing_material", itemName(itemId)));
    }

    private static Component itemName(String itemId) {
        if (itemId == null || itemId.isBlank() || "unknown".equals(itemId)) {
            return Component.translatable("message.simukraft.material.unknown");
        }
        try {
            ResourceLocation id = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
            return item == Items.AIR ? Component.literal(itemId) : new ItemStack(item).getHoverName();
        } catch (Exception exception) {
            return Component.literal(itemId);
        }
    }

    private static boolean isProblemStatusKey(String label) {
        String industrial = tail(label, INDUSTRIAL_STATUS_PREFIX);
        if (!industrial.isEmpty()) {
            return industrial.startsWith("missing")
                    || industrial.startsWith("invalid")
                    || equalsAny(industrial, "no_building", "invalid_definition", "no_worker", "no_recipe",
                    "paused", "worker_fired", "interrupted", "output_full", "craft_blocked", "waiting_block",
                    "block_action_blocked", "machine_missing", "machine_no_adapter", "machine_input_blocked",
                    "machine_timeout", "machine_needs_input");
        }
        String commercial = tail(label, COMMERCIAL_STATUS_PREFIX);
        return !commercial.isEmpty()
                && equalsAny(commercial, "no_building", "invalid_definition", "no_worker", "worker_fired", "interrupted");
    }

    private static boolean isRestingStatusKey(String label) {
        return label.equals(INDUSTRIAL_STATUS_PREFIX + "resting")
                || label.equals(COMMERCIAL_STATUS_PREFIX + "resting");
    }

    private static boolean isKnownStatusKey(String label) {
        return label.startsWith(INDUSTRIAL_STATUS_PREFIX)
                || label.startsWith(COMMERCIAL_STATUS_PREFIX);
    }

    private static boolean isProblemStatusText(String label) {
        return startsWithAny(label, "缺少", "等待仓储箱", "等待种子", "等待方块", "等待目标", "建造暂停中", "生产被阻塞");
    }

    private static boolean isRestingStatusText(String label) {
        return label.contains("休息");
    }

    private static boolean isActiveWorkText(String label) {
        return startsWithAny(label, "建造中", "建造准备中", "建造收尾中", "规划中", "挖水槽", "耕地中",
                "播种中", "收割中", "等待作物成熟", "正在", "前往");
    }

    private static boolean isFinishedStatusText(String label) {
        return startsWithAny(label, "规划完成", "建造完成");
    }

    private static String tail(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : "";
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    public interface WorkStatusProvider {
        /** resolve: 根据 NPC 状态上下文解析一条可选工作状态文字。 */
        Optional<Component> resolve(WorkStatusContext context);
    }

    public record WorkStatusContext(CitizenEntity entity, String workStatus, String statusLabel) {
        public WorkStatusContext {
            workStatus = workStatus != null ? workStatus : "";
            statusLabel = statusLabel != null ? statusLabel : "";
        }
    }

    private record Entry(String id, int priority, WorkStatusProvider provider) {
    }
}
