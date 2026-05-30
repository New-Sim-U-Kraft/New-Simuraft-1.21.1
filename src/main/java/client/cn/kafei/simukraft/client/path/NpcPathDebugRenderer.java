package client.cn.kafei.simukraft.client.path;

import client.cn.kafei.simukraft.client.toast.ClientInfoToast;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import common.cn.kafei.simukraft.network.path.NpcPathDebugRequestPacket;
import common.cn.kafei.simukraft.network.path.NpcPathDebugSyncPacket;
import common.cn.kafei.simukraft.path.MovementMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public final class NpcPathDebugRenderer {
    private static final Map<UUID, DebugPath> PATHS = new ConcurrentHashMap<>();
    private static final double MARKER_RADIUS = 0.12D;
    private static final int COLOR_WALK = 0xFF16D9D9;
    private static final int COLOR_RUN = 0xFF35E06B;
    private static final int COLOR_JUMP = 0xFFFFDD44;
    private static final int COLOR_SWIM = 0xFF3E8CFF;
    private static final int COLOR_CLIMB = 0xFFD064FF;
    private static final int COLOR_FALL = 0xFFFF8A2A;
    private static final int COLOR_WAIT = 0xFFFFE066;
    private static final int COLOR_STALLED = 0xFFFF3B3B;
    private static final int COLOR_PENDING = 0xFFB7B7B7;
    private static volatile boolean visible;

    private NpcPathDebugRenderer() {
    }

    public static void update(NpcPathDebugSyncPacket packet) {
        if (packet == null) {
            return;
        }
        if (packet.points().isEmpty()) {
            if (packet.success()) {
                PATHS.clear();
                showActionBar(Component.translatable("message.simukraft.path_debug.empty"));
            } else {
                PATHS.remove(packet.citizenId());
                showActionBar(Component.translatable("message.simukraft.path_debug.failed", packet.reason()));
            }
            return;
        }
        List<DebugPoint> points = packet.points().stream()
                .map(point -> new DebugPoint(point.x(), point.y(), point.z(), parseMode(point.mode())))
                .toList();
        PATHS.put(packet.citizenId(), new DebugPath(packet.citizenId(), packet.status(), points));
        if ("debug".equalsIgnoreCase(packet.status())) {
            showActionBar(Component.translatable("message.simukraft.path_debug.received", points.size()));
        }
    }

    public static void clear() {
        PATHS.clear();
    }

    public static boolean handleToggleShortcut(long window, int key, int action, int modifiers) {
        if (key != GLFW.GLFW_KEY_P || action != GLFW.GLFW_PRESS) {
            return false;
        }
        boolean altDown = (modifiers & GLFW.GLFW_MOD_ALT) != 0
                || isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        if (!altDown) {
            return false;
        }
        toggleVisible();
        return true;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (!visible || PATHS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.lineWidth(3.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = poseStack.last().pose();
        for (DebugPath path : PATHS.values()) {
            renderPath(buffer, matrix, cameraPos, path);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderPath(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, DebugPath path) {
        List<DebugPoint> points = path.points();
        if (points.isEmpty()) {
            return;
        }
        for (int index = 0; index < points.size() - 1; index++) {
            DebugPoint from = points.get(index);
            DebugPoint to = points.get(index + 1);
            drawLine(buffer, matrix, cameraPos, from.x(), from.y() + 0.12D, from.z(), to.x(), to.y() + 0.12D, to.z(), colorFor(path.status(), to.mode()));
        }
        for (int index = 0; index < points.size(); index++) {
            DebugPoint point = points.get(index);
            int color = index == 0 ? 0xFFFFFFFF : colorFor(path.status(), point.mode());
            renderMarker(buffer, matrix, cameraPos, point, color);
        }
    }

    private static void renderMarker(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, DebugPoint point, int color) {
        double x = point.x();
        double y = point.y() + 0.12D;
        double z = point.z();
        drawLine(buffer, matrix, cameraPos, x - MARKER_RADIUS, y, z, x + MARKER_RADIUS, y, z, color);
        drawLine(buffer, matrix, cameraPos, x, y - MARKER_RADIUS, z, x, y + MARKER_RADIUS, z, color);
        drawLine(buffer, matrix, cameraPos, x, y, z - MARKER_RADIUS, x, y, z + MARKER_RADIUS, color);
    }

    private static void drawLine(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        float alpha = ((color >> 24) & 0xFF) / 255.0F;
        buffer.addVertex(matrix, (float) (x1 - cameraPos.x), (float) (y1 - cameraPos.y), (float) (z1 - cameraPos.z)).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, (float) (x2 - cameraPos.x), (float) (y2 - cameraPos.y), (float) (z2 - cameraPos.z)).setColor(red, green, blue, alpha);
    }

    private static MovementMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MovementMode.WALK;
        }
        try {
            return MovementMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return MovementMode.WALK;
        }
    }

    private static int colorFor(MovementMode mode) {
        return switch (mode) {
            case RUN -> COLOR_RUN;
            case JUMP -> COLOR_JUMP;
            case SWIM -> COLOR_SWIM;
            case CLIMB -> COLOR_CLIMB;
            case FALL -> COLOR_FALL;
            default -> COLOR_WALK;
        };
    }

    private static int colorFor(String status, MovementMode mode) {
        if (status != null) {
            return switch (status.toLowerCase(Locale.ROOT)) {
                case "crowd_yield" -> COLOR_WAIT;
                case "stalled" -> COLOR_STALLED;
                case "pending", "queued" -> COLOR_PENDING;
                default -> colorFor(mode);
            };
        }
        return colorFor(mode);
    }

    private static void toggleVisible() {
        visible = !visible;
        if (visible) {
            PacketDistributor.sendToServer(new NpcPathDebugRequestPacket(true));
        }
        showActionBar(Component.translatable(visible ? "message.simukraft.path_debug.enabled" : "message.simukraft.path_debug.disabled"));
    }

    private static boolean isKeyDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    private static void showActionBar(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            ClientInfoToast.show(Component.translatable("toast.simukraft.title"), message, "info");
        }
    }

    private record DebugPath(UUID citizenId, String status, List<DebugPoint> points) {
        private DebugPath {
            status = status != null ? status : "";
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    private record DebugPoint(double x, double y, double z, MovementMode mode) {
    }
}
