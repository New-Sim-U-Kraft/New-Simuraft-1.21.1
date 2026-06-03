package client.cn.kafei.simukraft.client.city.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simukraft 自有方块颜色映射系统。
 * 为方块状态计算 ARGB 地图颜色，完全独立于 FTB/Xaero。
 */
public class SimuBlockColors {
    private static final SimuBlockColors INSTANCE = new SimuBlockColors();
    private final Map<Block, Integer> colorOverrides = new HashMap<>();
    private boolean initialized = false;

    private SimuBlockColors() {
    }

    public static SimuBlockColors getInstance() {
        return INSTANCE;
    }

    /** 初始化颜色覆盖表。 */
    public void init() {
        if (initialized) return;
        initialized = true;

        colorOverrides.put(Blocks.WATER, 0xFF3F76E4);
        colorOverrides.put(Blocks.LAVA, 0xFFD4610A);
        colorOverrides.put(Blocks.ICE, 0xFF91B4FC);
        colorOverrides.put(Blocks.PACKED_ICE, 0xFF8DB4FA);
        colorOverrides.put(Blocks.BLUE_ICE, 0xFF74AEF9);
        colorOverrides.put(Blocks.SNOW, 0xFFFAFAFA);
        colorOverrides.put(Blocks.SNOW_BLOCK, 0xFFF0F0F0);
        colorOverrides.put(Blocks.SAND, 0xFFDBD3A0);
        colorOverrides.put(Blocks.RED_SAND, 0xFFA85320);
        colorOverrides.put(Blocks.GRAVEL, 0xFF837E7A);
        colorOverrides.put(Blocks.CLAY, 0xFF9EA4B0);
        colorOverrides.put(Blocks.BEDROCK, 0xFF545454);
        colorOverrides.put(Blocks.NETHERRACK, 0xFF6B3535);
        colorOverrides.put(Blocks.END_STONE, 0xFFDBDE8E);
        colorOverrides.put(Blocks.OBSIDIAN, 0xFF14121D);
        colorOverrides.put(Blocks.DIAMOND_BLOCK, 0xFF6EECD2);
        colorOverrides.put(Blocks.GOLD_BLOCK, 0xFFF9EC4E);
        colorOverrides.put(Blocks.IRON_BLOCK, 0xFFD8D8D8);
        colorOverrides.put(Blocks.EMERALD_BLOCK, 0xFF41C950);
        colorOverrides.put(Blocks.COAL_BLOCK, 0xFF161616);
        colorOverrides.put(Blocks.REDSTONE_BLOCK, 0xFFA81303);
        colorOverrides.put(Blocks.LAPIS_BLOCK, 0xFF1D47A5);
        colorOverrides.put(Blocks.MYCELIUM, 0xFF6F6265);
        colorOverrides.put(Blocks.SOUL_SAND, 0xFF544033);
        colorOverrides.put(Blocks.GLOWSTONE, 0xFFAB8654);
        colorOverrides.put(Blocks.MELON, 0xFF669E1F);
        colorOverrides.put(Blocks.PUMPKIN, 0xFFC07615);
        colorOverrides.put(Blocks.TNT, 0xFFDB4A2B);
        colorOverrides.put(Blocks.BOOKSHELF, 0xFF6B5339);
        colorOverrides.put(Blocks.COBBLESTONE, 0xFF7F7F7F);
        colorOverrides.put(Blocks.STONE, 0xFF7D7D7D);
        colorOverrides.put(Blocks.DEEPSLATE, 0xFF505050);
        colorOverrides.put(Blocks.MOSS_BLOCK, 0xFF596D28);
        colorOverrides.put(Blocks.CHERRY_LEAVES, 0xFFEEB3C7);
        colorOverrides.put(Blocks.CHERRY_LOG, 0xFF3A1F23);
    }

    /**
     * 获取方块在给定位置的地图颜色。
     * 
     * @param state 方块状态
     * @param level 世界实例
     * @param pos 方块位置
     * @return ARGB 颜色值
     */
    public int getBlockColor(BlockState state, Level level, BlockPos pos) {
        Block block = state.getBlock();

        Integer override = colorOverrides.get(block);
        if (override != null) {
            return override;
        }

        if (state.isAir()) {
            return 0x00000000;
        }

        if (block instanceof GrassBlock) {
            return getBiomeGrassColor(level, pos);
        }
        if (state.is(Objects.requireNonNull(BlockTags.LEAVES))) {
            return getBiomeFoliageColor(level, pos);
        }

        if (block instanceof LiquidBlock) {
            return getBiomeWaterColor(level, pos);
        }

        try {
            BlockColors blockColors = Minecraft.getInstance().getBlockColors();
            int tintColor = blockColors.getColor(state, level, pos, 0);
            if (tintColor != -1 && tintColor != 0) {
                return 0xFF000000 | tintColor;
            }
        } catch (Exception ignored) {
        }

        try {
            MapColor mapColor = state.getMapColor(Objects.requireNonNull(level), Objects.requireNonNull(pos));
            if (mapColor != MapColor.NONE) {
                return 0xFF000000 | mapColor.col;
            }
        } catch (Exception ignored) {
        }

        return 0xFF7F7F7F;
    }

    /** 获取生物群系草地颜色。 */
    private int getBiomeGrassColor(Level level, BlockPos pos) {
        try {
            Biome biome = level.getBiome(Objects.requireNonNull(pos)).value();
            int color = biome.getGrassColor(pos.getX(), pos.getZ());
            return 0xFF000000 | color;
        } catch (Exception e) {
            return 0xFF7CBB4A; // 默认草地颜色
        }
    }

    /** 获取生物群系树叶颜色。 */
    private int getBiomeFoliageColor(Level level, BlockPos pos) {
        try {
            Biome biome = level.getBiome(Objects.requireNonNull(pos)).value();
            int color = biome.getFoliageColor();
            return 0xFF000000 | color;
        } catch (Exception e) {
            return 0xFF59AE30; // 默认树叶颜色
        }
    }

    /** 获取生物群系水体颜色。 */
    private int getBiomeWaterColor(Level level, BlockPos pos) {
        try {
            Biome biome = level.getBiome(Objects.requireNonNull(pos)).value();
            int color = biome.getWaterColor();
            return 0xFF000000 | color;
        } catch (Exception e) {
            return 0xFF3F76E4; // 默认水体颜色
        }
    }

    /**
     * 混合两个 ARGB 颜色。
     * 
     * @param base 基色
     * @param overlay 叠加色，alpha 控制混合强度
     * @return 混合后的颜色
     */
    public static int blendColors(int base, int overlay) {
        int oa = (overlay >> 24) & 0xFF;
        if (oa == 0) return base;
        if (oa == 255) return overlay;

        int ba = (base >> 24) & 0xFF;
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;

        int or = (overlay >> 16) & 0xFF;
        int og = (overlay >> 8) & 0xFF;
        int ob = overlay & 0xFF;

        float alpha = oa / 255f;
        int r = (int) (br * (1 - alpha) + or * alpha);
        int g = (int) (bg * (1 - alpha) + og * alpha);
        int b = (int) (bb * (1 - alpha) + ob * alpha);
        int a = Math.max(ba, oa);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 调整 ARGB 颜色亮度。
     * 
     * @param color ARGB 颜色
     * @param brightness 亮度调节值，范围 [-1.0, 1.0]
     * @return 调整后的颜色
     */
    public static int adjustBrightness(int color, float brightness) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        if (brightness > 0) {
            r = (int) (r + (255 - r) * brightness);
            g = (int) (g + (255 - g) * brightness);
            b = (int) (b + (255 - b) * brightness);
        } else {
            float factor = 1.0f + brightness;
            r = (int) (r * factor);
            g = (int) (g * factor);
            b = (int) (b * factor);
        }

        r = Math.min(Math.max(r, 0), 255);
        g = Math.min(Math.max(g, 0), 255);
        b = Math.min(Math.max(b, 0), 255);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 将 ARGB 转换为 NativeImage 使用的 ABGR 格式。 */
    public static int toNativeColor(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
