package client.cn.kafei.simukraft.client.city.map;

/**
 * 地图渲染样式枚举。
 * 决定城市地图使用哪种底层地图纹理数据源。
 */
public enum MapRenderStyle {

    /**
     * 自有渲染系统，默认使用。
     * 通过 {@link SimuMapManager} 独立扫描与渲染，不依赖外部地图模组。
     */
    SIMUKRAFT,

    /**
     * Xaero's World Map 渲染样式。
     * 预留给 Xaero 集成；不可用时降级为 {@link #SIMUKRAFT}。
     */
    XAERO,

    /**
     * FTB Chunks 渲染样式。
     * 预留给 FTB Chunks 集成；不可用时降级为 {@link #SIMUKRAFT}。
     */
    FTB;

    /** 根据名称解析样式，无效值返回 {@link #SIMUKRAFT}。 */
    public static MapRenderStyle fromString(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SIMUKRAFT;
        }
    }
}
