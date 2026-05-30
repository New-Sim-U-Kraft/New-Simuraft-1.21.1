package common.cn.kafei.simukraft.network.toast;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record InfoToastPacket(Component title, Component message, String style, ItemStack iconStack) implements CustomPacketPayload {
    public static final Type<InfoToastPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "info_toast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InfoToastPacket> STREAM_CODEC = StreamCodec.of(InfoToastPacket::encode, InfoToastPacket::decode);

    public InfoToastPacket(Component title, Component message, String style) {
        this(title, message, style, ItemStack.EMPTY);
    }

    public InfoToastPacket {
        title = title != null ? title : Component.translatable("toast.simukraft.title");
        message = message != null ? message : Component.empty();
        style = style != null && !style.isBlank() ? style : "info";
        iconStack = iconStack != null ? iconStack.copy() : ItemStack.EMPTY;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buffer, InfoToastPacket packet) {
        ComponentSerialization.STREAM_CODEC.encode(buffer, packet.title());
        ComponentSerialization.STREAM_CODEC.encode(buffer, packet.message());
        buffer.writeUtf(packet.style(), 16);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, packet.iconStack());
    }

    public static InfoToastPacket decode(RegistryFriendlyByteBuf buffer) {
        return new InfoToastPacket(
                ComponentSerialization.STREAM_CODEC.decode(buffer),
                ComponentSerialization.STREAM_CODEC.decode(buffer),
                buffer.readUtf(16),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
        );
    }

    public static void handle(InfoToastPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> client.cn.kafei.simukraft.client.toast.ClientInfoToast.show(packet.title(), packet.message(), packet.style(), packet.iconStack()));
    }
}
