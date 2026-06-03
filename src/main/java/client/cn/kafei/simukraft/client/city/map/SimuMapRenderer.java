package client.cn.kafei.simukraft.client.city.map;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 地图渲染器。
 * 将 {@link SimuMapRegionData} 的颜色和高度数据渲染到 {@link NativeImage}。
 */
public class SimuMapRenderer {

    private static float shadowStrength = 0.4f;
    private static float noiseStrength = 0.02f;
    private static boolean drawChunkGrid = true;
    private static final int GRID_COLOR = 0x32464646;

    private SimuMapRenderer() {
    }

    public static void setShadowStrength(float v) { shadowStrength = v; }
    public static void setNoiseStrength(float v) { noiseStrength = v; }
    public static void setDrawChunkGrid(boolean v) { drawChunkGrid = v; }

    /**
     * 渲染一个 region 到 NativeImage。
     * 该方法在后台渲染线程调用，结果写入 region 的图像缓存。
     */
    public static void renderRegion(SimuMapRegion region) {
        SimuMapRegionData data = region.getData();
        if (data == null || data.isEmpty()) return;

        NativeImage image;
        synchronized (region) {
            image = region.getOrCreateImage();
        }

        short[] heights = data.height;
        int[] colors = data.color;
        short[] flags = data.flags;
        int regWX = region.regionX * 512;
        int regWZ = region.regionZ * 512;

        for (int z = 0; z < 512; z++) {
            for (int x = 0; x < 512; x++) {
                int idx = x + z * 512;
                short height = heights[idx];

                if (height == SimuMapRegionData.HEIGHT_UNKNOWN) {
                    image.setPixelRGBA(x, z, 0);
                    continue;
                }

                int argb = colors[idx];
                boolean isWater = (flags[idx] & 1) != 0;

                if (shadowStrength > 0) {
                    float brightness = 0;

                    short heightN = z > 0 ? heights[x + (z - 1) * 512] : height;
                    short heightW = x > 0 ? heights[(x - 1) + z * 512] : height;

                    if (heightN != SimuMapRegionData.HEIGHT_UNKNOWN && heightW != SimuMapRegionData.HEIGHT_UNKNOWN) {
                        float shadowScale = isWater ? shadowStrength * 0.5f : shadowStrength;
                        if (height > heightN || height > heightW) {
                            brightness += shadowScale;
                        }
                        if (height < heightN || height < heightW) {
                            brightness -= shadowScale;
                        }
                    }

                    if (noiseStrength > 0) {
                        long seed = (long) (regWX + x) * 31 + (regWZ + z);
                        float noise = ((seed * 6364136223846793005L + 1442695040888963407L) >> 33 & 0xFF) / 255f;
                        brightness += (noise - 0.5f) * noiseStrength;
                    }

                    if (brightness != 0) {
                        argb = SimuBlockColors.adjustBrightness(argb, brightness);
                    }
                }

                if (drawChunkGrid && (x % 16 == 0 || z % 16 == 0)) {
                    argb = SimuBlockColors.blendColors(argb, GRID_COLOR);
                }

                image.setPixelRGBA(x, z, SimuBlockColors.toNativeColor(argb));
            }
        }

        data.clearDirty();
        region.markTextureNeedsUpload();
    }

    /**
     * 在 region 图像上绘制城市 chunk 边框叠加层。
     * 只绘制外框，不填充领地颜色。
     * 
     * @param region 目标 region
     * @param chunkX chunk X 坐标
     * @param chunkZ chunk Z 坐标
     * @param borderColor ARGB 边框颜色
     * @param borderThickness 边框厚度，单位为像素
     * @param drawTop 是否绘制上边框
     * @param drawBottom 是否绘制下边框
     * @param drawLeft 是否绘制左边框
     * @param drawRight 是否绘制右边框
     */
    public static void drawChunkBorder(SimuMapRegion region, int chunkX, int chunkZ,
                                        int borderColor, int borderThickness,
                                        boolean drawTop, boolean drawBottom,
                                        boolean drawLeft, boolean drawRight) {
        NativeImage image;
        synchronized (region) {
            image = region.getOrCreateImage();
        }

        int localX = (chunkX - region.regionX * 32) * 16;
        int localZ = (chunkZ - region.regionZ * 32) * 16;

        if (localX < 0 || localX + 16 > 512 || localZ < 0 || localZ + 16 > 512) return;

        int nativeColor = SimuBlockColors.toNativeColor(borderColor);

        for (int t = 0; t < borderThickness; t++) {
            if (drawTop) {
                for (int bx = localX; bx < localX + 16; bx++) {
                    image.setPixelRGBA(bx, localZ + t,
                            blendNativeColors(image.getPixelRGBA(bx, localZ + t), nativeColor));
                }
            }
            if (drawBottom) {
                for (int bx = localX; bx < localX + 16; bx++) {
                    image.setPixelRGBA(bx, localZ + 15 - t,
                            blendNativeColors(image.getPixelRGBA(bx, localZ + 15 - t), nativeColor));
                }
            }
            if (drawLeft) {
                for (int bz = localZ; bz < localZ + 16; bz++) {
                    image.setPixelRGBA(localX + t, bz,
                            blendNativeColors(image.getPixelRGBA(localX + t, bz), nativeColor));
                }
            }
            if (drawRight) {
                for (int bz = localZ; bz < localZ + 16; bz++) {
                    image.setPixelRGBA(localX + 15 - t, bz,
                            blendNativeColors(image.getPixelRGBA(localX + 15 - t, bz), nativeColor));
                }
            }
        }
    }

    /** 混合两个 NativeImage ABGR 格式颜色。 */
    private static int blendNativeColors(int base, int overlay) {
        int oa = (overlay >> 24) & 0xFF;
        if (oa == 0) return base;
        if (oa == 255) return overlay;

        int ba = (base >> 24) & 0xFF;
        int bb = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int br = base & 0xFF;

        int ob = (overlay >> 16) & 0xFF;
        int og = (overlay >> 8) & 0xFF;
        int or = overlay & 0xFF;

        float alpha = oa / 255f;
        int r = (int) (br * (1 - alpha) + or * alpha);
        int g = (int) (bg * (1 - alpha) + og * alpha);
        int b = (int) (bb * (1 - alpha) + ob * alpha);
        int a = Math.max(ba, oa);

        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
