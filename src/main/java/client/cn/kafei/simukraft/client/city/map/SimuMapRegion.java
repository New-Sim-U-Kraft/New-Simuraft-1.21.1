package client.cn.kafei.simukraft.client.city.map;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 表示一个 512x512 方块的地图 region。
 * 同时管理 CPU 侧数据和 GPU 纹理资源。
 */
public class SimuMapRegion {
    private static final Logger LOGGER = LogUtils.getLogger();

    public final int regionX;
    public final int regionZ;

    private SimuMapRegionData data;
    private NativeImage renderedImage;
    private int textureId = -1;
    private volatile boolean textureNeedsUpload = false;
    private volatile boolean imageLoaded = false;
    private long lastAccessTime;

    public SimuMapRegion(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.lastAccessTime = System.currentTimeMillis();
    }

    /** 获取或创建 region 数据。 */
    public SimuMapRegionData getOrCreateData() {
        if (data == null) {
            data = new SimuMapRegionData(regionX, regionZ);
        }
        lastAccessTime = System.currentTimeMillis();
        return data;
    }

    /**
     * 直接设置 region 数据，通常用于磁盘加载后的反序列化注入。
     * 
     * @param data 已填充的 region 数据，不能为 null
     */
    public void setData(SimuMapRegionData data) {
        this.data = data;
        this.lastAccessTime = System.currentTimeMillis();
    }

    /** 获取 region 数据，可能返回 null。 */
    @Nullable
    public SimuMapRegionData getData() {
        if (data != null) {
            lastAccessTime = System.currentTimeMillis();
        }
        return data;
    }

    /** 判断 region 是否已经持有数据。 */
    public boolean hasData() {
        return data != null;
    }

    /** 获取或创建渲染图像。 */
    public NativeImage getOrCreateImage() {
        if (renderedImage == null) {
            renderedImage = new NativeImage(NativeImage.Format.RGBA, 512, 512, true);
            renderedImage.fillRect(0, 0, 512, 512, 0);
        }
        return renderedImage;
    }

    /** 标记纹理需要上传到 GPU。 */
    public void markTextureNeedsUpload() {
        textureNeedsUpload = true;
        imageLoaded = false;
    }

    /** 获取 OpenGL 纹理 ID，并在需要时上传图像数据。 */
    public int getTextureId() {
        if (textureId == -1) {
            textureId = com.mojang.blaze3d.platform.TextureUtil.generateTextureId();
            com.mojang.blaze3d.platform.TextureUtil.prepareImage(textureId, 512, 512);
        }

        if (textureNeedsUpload && renderedImage != null) {
            textureNeedsUpload = false;
            if (RenderSystem.isOnRenderThreadOrInit()) {
                uploadNow();
            } else {
                Minecraft.getInstance().submit(this::uploadNow);
            }
        }

        return textureId;
    }

    private void uploadNow() {
        try {
            RenderSystem.bindTexture(textureId);
            synchronized (this) {
                if (renderedImage != null) {
                    renderedImage.upload(0, 0, 0, false);
                    imageLoaded = true;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Simukraft: Failed to upload map region texture ({}, {})", regionX, regionZ, e);
        }
    }

    /** 判断纹理是否已经成功上传。 */
    public boolean isImageLoaded() {
        return imageLoaded;
    }

    /** 获取最近访问时间。 */
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    /** 释放 region 占用的全部 CPU/GPU 资源。 */
    public void release() {
        synchronized (this) {
            if (renderedImage != null) {
                renderedImage.close();
                renderedImage = null;
            }
        }
        if (textureId != -1) {
            GlStateManager._deleteTexture(textureId);
            textureId = -1;
        }
        imageLoaded = false;
        data = null;
    }

    /** 释放纹理资源但保留地图数据。 */
    public void releaseTexture() {
        synchronized (this) {
            if (renderedImage != null) {
                renderedImage.close();
                renderedImage = null;
            }
        }
        if (textureId != -1) {
            GlStateManager._deleteTexture(textureId);
            textureId = -1;
        }
        imageLoaded = false;
    }

    /** 丢弃 CPU 侧地图数据。 */
    public void discardData() {
        data = null;
    }

    /** 计算 region 到玩家的距离平方。 */
    public double distToPlayer() {
        var player = Minecraft.getInstance().player;
        if (player == null) return Double.MAX_VALUE;
        double cx = regionX * 512.0 + 256.0;
        double cz = regionZ * 512.0 + 256.0;
        double dx = cx - player.getX();
        double dz = cz - player.getZ();
        return dx * dx + dz * dz;
    }

    /** 获取 region 的字符串标识。 */
    public String regionKey() {
        return regionX + "," + regionZ;
    }

    @Override
    public String toString() {
        return "SimuMapRegion[" + regionX + "," + regionZ + "]";
    }
}
