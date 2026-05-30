package common.cn.kafei.simukraft.material;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("null")
public record WorkMaterialResult(boolean available, ItemStack requested, List<Item> acceptedItems) {
    public WorkMaterialResult {
        requested = requested != null ? requested.copy() : ItemStack.EMPTY;
        acceptedItems = acceptedItems == null ? List.of() : acceptedItems.stream()
                .filter(item -> item != null)
                .sorted(Comparator.comparing(item -> {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                    return key != null ? key.toString() : "";
                }))
                .toList();
    }

    public WorkMaterialResult(boolean available, ItemStack requested) {
        this(available, requested, List.of());
    }

    public static WorkMaterialResult available(ItemStack requested) {
        return new WorkMaterialResult(true, requested, List.of());
    }

    public static WorkMaterialResult missing(ItemStack requested) {
        return new WorkMaterialResult(false, requested, List.of());
    }

    public static WorkMaterialResult missing(ItemStack requested, Collection<Item> acceptedItems) {
        return new WorkMaterialResult(false, requested, acceptedItems == null ? List.of() : List.copyOf(acceptedItems));
    }

    public Component displayName() {
        return requested.isEmpty() ? Component.translatable("message.simukraft.material.unknown") : requested.getHoverName();
    }

    public String materialName() {
        if (requested.isEmpty()) {
            return "unknown";
        }
        String translated = requested.getHoverName().getString();
        if (!translated.isBlank()) {
            return translated;
        }
        return BuiltInRegistries.ITEM.getKey(requested.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    public String acceptedMaterialsText() {
        if (acceptedItems.isEmpty()) {
            return Component.translatable("message.simukraft.material.unknown").getString();
        }
        int limit = Math.min(3, acceptedItems.size());
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(displayItemName(acceptedItems.get(index)));
        }
        if (acceptedItems.size() > limit) {
            builder.append(' ').append(Component.translatable("simukraft.material.etc").getString());
        }
        return builder.toString();
    }

    private static String displayItemName(Item item) {
        if (item == null) {
            return Component.translatable("message.simukraft.material.unknown").getString();
        }
        ItemStack stack = new ItemStack(item);
        String translated = stack.getHoverName().getString();
        if (!translated.isBlank()) {
            return translated;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.toString() : Component.translatable("message.simukraft.material.unknown").getString();
    }
}
