package client.cn.kafei.simukraft.client;

import client.cn.kafei.simukraft.client.city.ClientCityChunkCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class CityEntryHud {
    private static final long DURATION_MS = 3000L;
    private static final int FADE_IN_TICKS = 12;
    private static final int HOLD_TICKS = 36;
    private static final int FADE_OUT_TICKS = 14;
    private static final int TITLE_RGB = 0xF4E8C9;
    private static final int BAR_RGB = 0xF0DFC1;
    private static final int BAR_WIDTH = 2;
    private static final int BAR_HEIGHT = 14;
    private static final int BAR_GAP = 12;
    private static final int BAR_TEXT_PADDING = 8;
    private static final int BAR_TRAVEL = 18;
    private static final float TITLE_SCALE = 2.2F;

    private static String cityName = null;
    private static long startTime = 0L;
    private static UUID lastCityId = null;
    private static long lastChunkLong = Long.MIN_VALUE;

    private CityEntryHud() {}

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            lastCityId = null;
            lastChunkLong = Long.MIN_VALUE;
            return;
        }
        long chunkLong = mc.player.chunkPosition().toLong();
        if (chunkLong == lastChunkLong) return;
        lastChunkLong = chunkLong;

        ClientCityChunkCache cache = ClientCityChunkCache.getInstance();
        UUID cityId = cache.getChunkOwner(chunkLong);
        if (cityId == null || cityId.equals(lastCityId)) {
            lastCityId = cityId;
            return;
        }
        ClientCityChunkCache.CityCoreEntry entry = cache.getAllCityCores().get(cityId);
        if (entry != null && !entry.cityName().isBlank()) {
            show(entry.cityName());
        }
        lastCityId = cityId;
    }

    public static void show(String name) {
        if (name == null || name.isBlank()) return;
        cityName = name.trim();
        startTime = System.currentTimeMillis();
    }

    public static void reset() {
        cityName = null;
        startTime = 0L;
        lastCityId = null;
        lastChunkLong = Long.MIN_VALUE;
    }

    public static void render(GuiGraphics guiGraphics, float partialTick) {
        if (cityName == null) return;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > DURATION_MS) { cityName = null; return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.font == null) return;

        float elapsedTicks = (elapsed / 50.0f) + clamp01(partialTick);
        float alpha = getAlpha(elapsedTicks);
        if (alpha <= 0.01f) return;

        Font font = mc.font;
        int width = guiGraphics.guiWidth();
        int height = guiGraphics.guiHeight();
        int anchorX = width / 2;
        int anchorY = Math.max(56, height / 5);

        List<FormattedCharSequence> titleLines = font.split(Component.literal(cityName), Math.max(120, width / 4));
        int titleLineHeight = Math.max(1, Math.round(font.lineHeight * TITLE_SCALE));
        int startY = anchorY - (titleLines.size() * titleLineHeight) / 2;

        drawCenteredScaledLines(guiGraphics, font, titleLines, anchorX, startY,
                TITLE_SCALE, ((int) (alpha * 255.0f) << 24) | TITLE_RGB, true);

        float inPhase = clamp01(elapsedTicks / FADE_IN_TICKS);
        float outPhase = clamp01((elapsedTicks - FADE_IN_TICKS - HOLD_TICKS) / FADE_OUT_TICKS);
        float leftOffset, rightOffset;
        if (elapsedTicks < FADE_IN_TICKS) {
            float e = easeOutCubic(inPhase);
            leftOffset = -BAR_TRAVEL * (1.0f - e);
            rightOffset = BAR_TRAVEL * (1.0f - e);
        } else if (elapsedTicks < FADE_IN_TICKS + HOLD_TICKS) {
            leftOffset = rightOffset = 0.0f;
        } else {
            float e = easeInCubic(outPhase);
            leftOffset = BAR_TRAVEL * e;
            rightOffset = -BAR_TRAVEL * e;
        }

        int barColor = ((int) (alpha * 215.0f) << 24) | BAR_RGB;
        int barShadow = ((int) (alpha * 96.0f) << 24) | 0x4F3928;
        int titleWidth = Math.max(1, Math.round(getMaxLineWidth(font, titleLines) * TITLE_SCALE));
        int baseBarY = startY + Math.max(0, titleLineHeight / 2 - BAR_HEIGHT / 2);
        int leftX = anchorX - titleWidth / 2 - BAR_TEXT_PADDING - BAR_GAP - BAR_WIDTH;
        int rightX = anchorX + titleWidth / 2 + BAR_TEXT_PADDING + BAR_GAP;
        int leftY = Math.round(baseBarY + leftOffset);
        int rightY = Math.round(baseBarY + rightOffset);
        guiGraphics.fill(leftX + 1, leftY + 1, leftX + BAR_WIDTH + 1, leftY + BAR_HEIGHT + 1, barShadow);
        guiGraphics.fill(rightX + 1, rightY + 1, rightX + BAR_WIDTH + 1, rightY + BAR_HEIGHT + 1, barShadow);
        guiGraphics.fill(leftX, leftY, leftX + BAR_WIDTH, leftY + BAR_HEIGHT, barColor);
        guiGraphics.fill(rightX, rightY, rightX + BAR_WIDTH, rightY + BAR_HEIGHT, barColor);
    }

    private static float getAlpha(float t) {
        if (t < FADE_IN_TICKS) return easeOutCubic(clamp01(t / FADE_IN_TICKS));
        if (t < FADE_IN_TICKS + HOLD_TICKS) return 1.0f;
        return 1.0f - easeInCubic(clamp01((t - FADE_IN_TICKS - HOLD_TICKS) / FADE_OUT_TICKS));
    }

    private static int getMaxLineWidth(Font font, List<FormattedCharSequence> lines) {
        int w = 0;
        for (FormattedCharSequence line : lines) w = Math.max(w, font.width(line));
        return w;
    }

    private static void drawCenteredScaledLines(GuiGraphics g, Font font, List<FormattedCharSequence> lines,
                                                int centerX, int startY, float scale, int color, boolean shadow) {
        int lineHeight = Math.max(1, Math.round(font.lineHeight * scale));
        int y = startY;
        for (FormattedCharSequence line : lines) {
            g.pose().pushPose();
            g.pose().translate(centerX, y, 0);
            g.pose().scale(scale, scale, 1.0F);
            g.drawString(font, line, -font.width(line) / 2, 0, color, shadow);
            g.pose().popPose();
            y += lineHeight;
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }
    private static float easeOutCubic(float t) { float m = 1.0f - clamp01(t); return 1.0f - m * m * m; }
    private static float easeInCubic(float t) { float x = clamp01(t); return x * x * x; }
}
