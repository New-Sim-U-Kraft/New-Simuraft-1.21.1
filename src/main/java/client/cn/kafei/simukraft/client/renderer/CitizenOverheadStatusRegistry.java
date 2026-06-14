package client.cn.kafei.simukraft.client.renderer;

import common.cn.kafei.simukraft.entity.CitizenEntity;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class CitizenOverheadStatusRegistry {
    public static final int PRIORITY_NAME = 300;
    public static final int PRIORITY_WORK_STATUS = 200;
    public static final int PRIORITY_HUNGER = 100;

    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static volatile List<Entry> SORTED_SNAPSHOT = List.of();

    static {
        register("name", PRIORITY_NAME, entity -> Optional.of(new StatusLine(entity.getDisplayName(), 0xFFFFFF, 0.025F)));
        register("work_status", PRIORITY_WORK_STATUS, entity -> Optional.of(new StatusLine(CitizenWorkStatusDisplayRegistry.resolve(entity), 0xFFFF00, 0.02F)));
        register("hunger", PRIORITY_HUNGER, entity -> Optional.of(new StatusLine(Component.translatable(entity.getHungerLevelKey()), 0xFFFF00, 0.02F)));
        rebuildSnapshot();
    }

    private static void rebuildSnapshot() {
        List<Entry> s = new ArrayList<>(ENTRIES);
        s.sort(Comparator.comparingInt(Entry::priority).reversed());
        SORTED_SNAPSHOT = List.copyOf(s);
    }

    private CitizenOverheadStatusRegistry() {
    }

    /** register: 注册 NPC 头顶状态提供器，同 ID 会被新注册覆盖。 */
    public static void register(String id, int priority, StatusProvider provider) {
        if (id == null || id.isBlank() || provider == null) {
            return;
        }
        ENTRIES.removeIf(entry -> entry.id().equals(id));
        ENTRIES.add(new Entry(id, priority, provider));
        rebuildSnapshot();
    }

    /** unregister: 移除指定 ID 的 NPC 头顶状态提供器。 */
    public static void unregister(String id) {
        if (id != null && !id.isBlank()) {
            ENTRIES.removeIf(entry -> entry.id().equals(id));
            rebuildSnapshot();
        }
    }

    /** resolve: 按优先级解析当前 NPC 应显示的所有头顶状态行。 */
    public static List<StatusLine> resolve(CitizenEntity entity) {
        if (entity == null) {
            return List.of();
        }
        List<StatusLine> lines = new ArrayList<>(SORTED_SNAPSHOT.size());
        for (Entry entry : SORTED_SNAPSHOT) {
            entry.provider().resolve(entity)
                    .filter(StatusLine::isVisible)
                    .ifPresent(lines::add);
        }
        return List.copyOf(lines);
    }

    /** StatusProvider: 提供一行 NPC 头顶显示内容。 */
    @FunctionalInterface
    public interface StatusProvider {
        /** resolve: 根据 NPC 实体解析一行可选头顶状态。 */
        Optional<StatusLine> resolve(CitizenEntity entity);
    }

    public record StatusLine(Component text, int color, float scale) {
        /** isVisible: 空文本不参与头顶渲染。 */
        private boolean isVisible() {
            return text != null && !text.getString().isBlank();
        }
    }

    private record Entry(String id, int priority, StatusProvider provider) {
    }
}
