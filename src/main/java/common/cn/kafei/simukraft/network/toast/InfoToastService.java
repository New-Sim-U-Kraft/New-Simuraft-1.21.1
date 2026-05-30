package common.cn.kafei.simukraft.network.toast;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class InfoToastService {
    private InfoToastService() {
    }

    public static void send(ServerPlayer player, Component message) {
        send(player, Component.translatable("toast.simukraft.title"), message, "info");
    }

    public static void success(ServerPlayer player, Component message) {
        send(player, Component.translatable("toast.simukraft.title"), message, "success");
    }

    public static void warning(ServerPlayer player, Component message) {
        send(player, Component.translatable("toast.simukraft.title"), message, "warning");
    }

    public static void error(ServerPlayer player, Component message) {
        send(player, Component.translatable("toast.simukraft.title"), message, "error");
    }

    public static void money(ServerPlayer player, Component message) {
        send(player, Component.translatable("toast.simukraft.money_title"), message, "money");
    }

    public static void material(ServerPlayer player, Component message, ItemStack iconStack) {
        send(player, Component.translatable("toast.simukraft.material_title"), message, "warning", iconStack);
    }

    public static void send(ServerPlayer player, Component title, Component message, String style) {
        send(player, title, message, style, ItemStack.EMPTY);
    }

    public static void send(ServerPlayer player, Component title, Component message, String style, ItemStack iconStack) {
        if (player == null || message == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new InfoToastPacket(title, message, style, iconStack));
    }
}
