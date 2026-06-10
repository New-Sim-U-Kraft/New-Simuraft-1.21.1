package client.cn.kafei.simukraft.client.logistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

@SuppressWarnings("null")
final class LogisticsItemDisplayName {
    private LogisticsItemDisplayName() {
    }

    /** itemName: 按客户端语言把物品 ID 转成可读名称。 */
    static String itemName(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return safeItemId(itemId);
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? safeItemId(itemId) : new ItemStack(item).getHoverName().getString();
    }

    /** filterText: 把路线过滤物品列表格式化为客户端翻译名。 */
    static String filterText(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Component.translatable("gui.simukraft.logistics.channel.all_items").getString();
        }
        if (filters.size() <= 3) {
            return String.join(", ", filters.stream().map(LogisticsItemDisplayName::itemName).toList());
        }
        return itemName(filters.get(0)) + ", " + itemName(filters.get(1)) + "...";
    }

    /** channelName: 默认线路名显示为过滤物品的客户端翻译名。 */
    static String channelName(String storedName, List<String> filters) {
        String defaultName = Component.translatable("gui.simukraft.logistics.channel.default_name").getString();
        if (storedName == null || storedName.isBlank() || storedName.equals(defaultName)
                || storedName.equals("物流线路") || storedName.equals("Logistics Route")) {
            return filterText(filters);
        }
        return storedName;
    }

    /** stackFor: 根据物品 ID 创建只读展示用 ItemStack。 */
    static ItemStack stackFor(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return new ItemStack(Items.BARRIER);
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
    }

    /** safeItemId: 物品 ID 无效时提供稳定回退文本。 */
    private static String safeItemId(String itemId) {
        return itemId == null || itemId.isBlank() ? "-" : itemId;
    }
}
