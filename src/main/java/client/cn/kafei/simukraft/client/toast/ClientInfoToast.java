package client.cn.kafei.simukraft.client.toast;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

@SuppressWarnings("null")
public final class ClientInfoToast implements Toast {
    private static final ResourceLocation LOGO_TEXTURE = ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "textures/gui/logo.png");
    private static final int WIDTH = 184;
    private static final int MIN_HEIGHT = 48;
    private static final int TEXT_X = 36;
    private static final int TEXT_WIDTH = WIDTH - TEXT_X - 8;
    private static final int BADGE_RIGHT = WIDTH - 8;
    private static final int ICON_X = 9;
    private static final int ICON_Y = 15;
    private static final int ICON_SIZE = 18;
    private static final int ICON_BACKING_PADDING = 2;
    private static final int LOGO_TEXTURE_SIZE = 128;
    private static final int MESSAGE_Y = 22;
    private static final int LINE_HEIGHT = 10;
    private static final int BOTTOM_PADDING = 6;
    private static final int ITEM_GAP = 3;
    private static final int INLINE_ITEM_SIZE = 16;
    private static final long DISPLAY_TIME_MS = 4200L;

    private final ToastKey token;
    private final Component title;
    private final Component message;
    private final String style;
    private final ItemStack iconStack;
    private final List<FormattedCharSequence> messageLines;
    private final List<FormattedCharSequence> itemNameLines;
    private final Component itemPrefix;
    private final int itemPrefixWidth;
    private final int height;
    private int count = 1;
    private boolean changed;
    private long lastChangedVisibleTime;

    private ClientInfoToast(Component title, Component message, String style, ItemStack iconStack, ToastKey token, Font font) {
        this.title = title != null ? title : Component.translatable("toast.simukraft.title");
        this.message = message != null ? message : Component.empty();
        this.style = style != null && !style.isBlank() ? style : "info";
        this.iconStack = iconStack != null ? iconStack.copyWithCount(1) : ItemStack.EMPTY;
        this.token = token;
        this.messageLines = List.copyOf(font.split(this.message, TEXT_WIDTH));
        if (this.iconStack.isEmpty()) {
            this.itemNameLines = List.of();
            this.itemPrefix = Component.empty();
            this.itemPrefixWidth = 0;
            this.height = textHeight(this.messageLines.size());
        } else {
            this.itemPrefix = Component.translatable("message.simukraft.material.required_prefix");
            this.itemPrefixWidth = font.width(this.itemPrefix);
            int itemTextWidth = Math.max(1, TEXT_WIDTH - itemPrefixWidth - INLINE_ITEM_SIZE - 8);
            this.itemNameLines = List.copyOf(font.split(this.iconStack.getHoverName(), itemTextWidth));
            this.height = itemHeight(this.messageLines.size(), this.itemNameLines.size());
        }
    }

    public static void show(Component title, Component message, String style) {
        show(title, message, style, ItemStack.EMPTY);
    }

    public static void show(Component title, Component message, String style, ItemStack iconStack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        ToastKey key = ToastKey.from(title, message, style, iconStack);
        ClientInfoToast existing = minecraft.getToasts().getToast(ClientInfoToast.class, key);
        if (existing != null) {
            existing.mergeDuplicate();
            return;
        }
        minecraft.getToasts().addToast(new ClientInfoToast(title, message, key.style(), iconStack, key, minecraft.font));
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent toastComponent, long timeSinceLastVisible) {
        if (changed) {
            lastChangedVisibleTime = timeSinceLastVisible;
            changed = false;
        }
        Minecraft minecraft = toastComponent.getMinecraft();
        Font font = minecraft.font;
        int accentColor = accentColor(style);
        int toastHeight = height();
        graphics.fill(0, 0, WIDTH, toastHeight, 0xE6101010);
        graphics.fill(0, 0, 4, toastHeight, accentColor);
        graphics.fill(4, 0, WIDTH, 1, 0x66FFFFFF);
        graphics.fill(4, toastHeight - 1, WIDTH, toastHeight, 0x66000000);
        int logoY = Math.max(ICON_Y, (toastHeight - ICON_SIZE) / 2);
        graphics.fill(
                ICON_X - ICON_BACKING_PADDING,
                logoY - ICON_BACKING_PADDING,
                ICON_X + ICON_SIZE + ICON_BACKING_PADDING,
                logoY + ICON_SIZE + ICON_BACKING_PADDING,
                accentColor
        );
        graphics.blit(LOGO_TEXTURE, ICON_X, logoY, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE, LOGO_TEXTURE_SIZE);
        graphics.drawString(font, title, TEXT_X, 8, 0xFFFFFFFF, false);
        if (count > 1) {
            String countText = "x" + count;
            graphics.drawString(font, countText, BADGE_RIGHT - font.width(countText), 8, accentColor, false);
        }

        if (iconStack.isEmpty()) {
            renderTextMessage(graphics, font);
        } else {
            renderItemMessage(graphics, font);
        }
        long scaledDisplayTime = (long) (DISPLAY_TIME_MS * toastComponent.getNotificationDisplayTimeMultiplier());
        return timeSinceLastVisible - lastChangedVisibleTime >= scaledDisplayTime ? Visibility.HIDE : Visibility.SHOW;
    }

    private void renderTextMessage(GuiGraphics graphics, Font font) {
        for (int index = 0; index < messageLines.size(); index++) {
            graphics.drawString(font, messageLines.get(index), TEXT_X, MESSAGE_Y + index * LINE_HEIGHT, 0xFFE6E6E6, false);
        }
    }

    private void renderItemMessage(GuiGraphics graphics, Font font) {
        for (int index = 0; index < messageLines.size(); index++) {
            graphics.drawString(font, messageLines.get(index), TEXT_X, MESSAGE_Y + index * LINE_HEIGHT, 0xFFE6E6E6, false);
        }
        int itemY = MESSAGE_Y + Math.max(1, messageLines.size()) * LINE_HEIGHT + ITEM_GAP;
        graphics.drawString(font, itemPrefix, TEXT_X, itemY + 4, 0xFFFFFFFF, false);
        int itemX = TEXT_X + itemPrefixWidth + 4;
        graphics.renderItem(iconStack, itemX, itemY);
        int itemTextX = itemX + INLINE_ITEM_SIZE + 4;
        for (int index = 0; index < itemNameLines.size(); index++) {
            graphics.drawString(font, itemNameLines.get(index), itemTextX, itemY + 4 + index * LINE_HEIGHT, 0xFFFFFFFF, false);
        }
    }

    @Override
    public Object getToken() {
        return token;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int slotCount() {
        return Math.max(1, Math.min(5, (height() + Toast.SLOT_HEIGHT - 1) / Toast.SLOT_HEIGHT));
    }

    private static int accentColor(String style) {
        return switch ((style != null ? style : "info").toLowerCase(Locale.ROOT)) {
            case "success" -> 0xFF42D17A;
            case "warning" -> 0xFFFFC857;
            case "error" -> 0xFFFF5C5C;
            case "money" -> 0xFFFFD166;
            default -> 0xFF58A6FF;
        };
    }

    private void mergeDuplicate() {
        if (count < Integer.MAX_VALUE) {
            count++;
        }
        changed = true;
    }

    private static int textHeight(int lineCount) {
        return Math.max(MIN_HEIGHT, MESSAGE_Y + Math.max(1, lineCount) * LINE_HEIGHT + BOTTOM_PADDING);
    }

    private static int itemHeight(int messageLineCount, int itemLineCount) {
        int messageHeight = Math.max(1, messageLineCount) * LINE_HEIGHT;
        int itemHeight = Math.max(INLINE_ITEM_SIZE, Math.max(1, itemLineCount) * LINE_HEIGHT);
        return Math.max(MIN_HEIGHT, MESSAGE_Y + messageHeight + ITEM_GAP + itemHeight + BOTTOM_PADDING);
    }

    private record ToastKey(String title, String message, String style, String iconId) {
        private static ToastKey from(Component title, Component message, String style, ItemStack iconStack) {
            Component normalizedTitle = title != null ? title : Component.translatable("toast.simukraft.title");
            Component normalizedMessage = message != null ? message : Component.empty();
            String normalizedStyle = style != null && !style.isBlank() ? style.toLowerCase(Locale.ROOT) : "info";
            String normalizedIconId = iconStack == null || iconStack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(iconStack.getItem()).toString();
            return new ToastKey(normalizedTitle.getString(), normalizedMessage.getString(), normalizedStyle, normalizedIconId);
        }
    }
}
