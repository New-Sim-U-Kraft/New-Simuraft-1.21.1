package client.cn.kafei.simukraft.client.city.map;

import java.util.Arrays;

/**
 * 单个地图 region 的地形数据。
 * 一个 region 覆盖 512x512 方块，即 32x32 个 chunk。
 */
public class SimuMapRegionData {

    public static final int SIZE = 512;
    public static final int AREA = SIZE * SIZE;
    public static final short HEIGHT_UNKNOWN = Short.MIN_VALUE;

    /** 最高非空气方块高度。 */
    public final short[] height = new short[AREA];

    /** 每个方块位置对应的 ARGB 渲染颜色。 */
    public final int[] color = new int[AREA];

    /** 标志位：bit0=水面，bit1-4=光照等级。 */
    public final short[] flags = new short[AREA];

    /** 数据是否被修改，需要重新渲染。 */
    private boolean dirty = true;

    /** 该数据所属 region 坐标。 */
    public final int regionX;
    public final int regionZ;

    public SimuMapRegionData(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        Arrays.fill(height, HEIGHT_UNKNOWN);
    }

    /** 获取指定 region 内方块位置的数组索引。 */
    public static int index(int localX, int localZ) {
        return (localX & 0x1FF) + (localZ & 0x1FF) * SIZE;
    }

    /** 设置指定位置的地图采样数据。 */
    public void setData(int localX, int localZ, short h, int argbColor, boolean water, int light) {
        int idx = index(localX, localZ);
        short f = (short) ((water ? 1 : 0) | ((light & 0xF) << 1));
        if (height[idx] == h && color[idx] == argbColor && flags[idx] == f) {
            return;
        }
        height[idx] = h;
        color[idx] = argbColor;
        flags[idx] = f;
        dirty = true;
    }

    /** 获取指定位置的高度。 */
    public short getHeight(int localX, int localZ) {
        return height[index(localX, localZ)];
    }

    /** 获取指定位置的颜色。 */
    public int getColor(int localX, int localZ) {
        return color[index(localX, localZ)];
    }

    /** 判断指定位置是否为水面。 */
    public boolean isWater(int localX, int localZ) {
        return (flags[index(localX, localZ)] & 1) != 0;
    }

    /** 获取指定位置的光照等级。 */
    public int getLight(int localX, int localZ) {
        return (flags[index(localX, localZ)] >> 1) & 0xF;
    }

    /** 判断是否有未渲染的修改。 */
    public boolean isDirty() {
        return dirty;
    }

    /** 标记为已渲染。 */
    public void clearDirty() {
        dirty = false;
    }

    /** 标记为需要重新渲染。 */
    public void markDirty() {
        dirty = true;
    }

    /** 检查该 region 是否完全空白。 */
    public boolean isEmpty() {
        for (short h : height) {
            if (h != HEIGHT_UNKNOWN) return false;
        }
        return true;
    }

    /** 获取该 region 起点的世界 X 坐标。 */
    public int worldBlockX() {
        return regionX * SIZE;
    }

    /** 获取该 region 起点的世界 Z 坐标。 */
    public int worldBlockZ() {
        return regionZ * SIZE;
    }
}
