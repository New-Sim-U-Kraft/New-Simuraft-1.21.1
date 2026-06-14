package client.cn.kafei.simukraft.client.buildbox;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import client.cn.kafei.simukraft.client.city.ClientCityChunkCache;
import common.cn.kafei.simukraft.building.BuildingTerritoryValidator;
import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.building.PlacedBuildingService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class BuildingBoundsRenderer {
    private static final int COLOR_CITY_BORDER = 0x553C66FF;
    private static final int COLOR_INTRUSION_AIR = 0x60FFFF00;
    private static final int COLOR_INTRUSION_BLOCK = 0x60FF0000;
    private static final int COLOR_SELECTED_BUILDING = 0xAAFFFFFF;
    private static final int COLOR_RESIDENTIAL_POI = 0xAA00FF66;
    private static final int COLOR_INDUSTRIAL_WORK_POINT = 0xAA33CCFF;
    private static final int COLOR_INDUSTRIAL_MACHINE_POINT = 0xAAFFFF33;
    private static final int COLOR_INDUSTRIAL_INPUT_CONTAINER = 0xAA00FF66;
    private static final int COLOR_INDUSTRIAL_OUTPUT_CONTAINER = 0xAAFF9900;
    private static final double BUILDING_CONTACT_EPSILON = 0.001D;
    private static final double SELECTED_BOUNDS_INFLATE = 0.03D;
    private static final double POINT_MARKER_RADIUS = 0.18D;
    private static final double POINT_MARKER_Y_OFFSET = 0.56D;
    // 住宅控制盒手动打开的建筑边界，按控制盒位置索引以便再次点击时关闭。
    private static final Map<BlockPos, DisplayedBuildingBounds> DISPLAYED_BUILDING_BOUNDS = new ConcurrentHashMap<>();
    private static UUID previewPlayerId;
    private static long intrusionCacheRevision = Long.MIN_VALUE;
    private static BlockPos intrusionCacheOrigin = BlockPos.ZERO;
    private static int intrusionCacheRotation = Integer.MIN_VALUE;
    private static List<PreviewIntrusion> cachedIntrusions = List.of();
    private static List<AABB> cachedTouchedBuildingBounds = List.of();

    private BuildingBoundsRenderer() {
    }

    public static void setPreviewPlayerId(UUID playerId) {
        previewPlayerId = playerId;
    }

    // 清理客户端建筑边界显示状态，避免切换存档后仍渲染旧控制盒边界。
    public static void clearAll() {
        DISPLAYED_BUILDING_BOUNDS.clear();
        previewPlayerId = null;
        clearPreviewDetectionCache();
    }

    public static boolean isBuildingBoundsVisible(BlockPos controlBoxPos) {
        return controlBoxPos != null && DISPLAYED_BUILDING_BOUNDS.containsKey(controlBoxPos.immutable());
    }

    public static void setBuildingBoundsVisible(BlockPos controlBoxPos, AABB bounds, boolean visible) {
        setBuildingBoundsVisible(controlBoxPos, bounds, List.of(), visible);
    }

    public static void setBuildingBoundsVisible(BlockPos controlBoxPos, AABB bounds, List<BlockPos> residentialPoiPositions, boolean visible) {
        List<DisplayMarker> markers = residentialPoiPositions == null
                ? List.of()
                : residentialPoiPositions.stream()
                .map(pos -> new DisplayMarker(pos.immutable(), COLOR_RESIDENTIAL_POI))
                .toList();
        setBuildingBoundsVisibleWithMarkers(controlBoxPos, bounds, markers, visible);
    }

    public static void setBuildingBoundsVisibleWithMarkers(BlockPos controlBoxPos, AABB bounds, List<DisplayMarker> markers, boolean visible) {
        if (controlBoxPos == null) {
            return;
        }
        BlockPos key = controlBoxPos.immutable();
        if (visible && bounds != null) {
            List<DisplayMarker> markerList = markers == null
                    ? List.of()
                    : markers.stream()
                    .filter(marker -> marker != null && marker.pos() != null)
                    .map(marker -> new DisplayMarker(marker.pos().immutable(), marker.color()))
                    .distinct()
                    .toList();
            DISPLAYED_BUILDING_BOUNDS.put(key, new DisplayedBuildingBounds(bounds, markerList));
        } else {
            DISPLAYED_BUILDING_BOUNDS.remove(key);
        }
    }

    public static void updateDisplayedBuildingBounds(BlockPos controlBoxPos, boolean hasBuildingBounds, BlockPos boundsMin, BlockPos boundsMax, List<BlockPos> residentialPoiPositions) {
        if (controlBoxPos == null || !isBuildingBoundsVisible(controlBoxPos)) {
            return;
        }
        if (!hasBuildingBounds) {
            setBuildingBoundsVisible(controlBoxPos, null, false);
            return;
        }
        AABB bounds = new AABB(boundsMin.getX(), boundsMin.getY(), boundsMin.getZ(), boundsMax.getX() + 1, boundsMax.getY() + 1, boundsMax.getZ() + 1);
        setBuildingBoundsVisible(controlBoxPos, bounds, residentialPoiPositions, true);
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        // 预览模式只渲染当前玩家自己的城市边界和侵入提示，避免多人客户端互相干扰。
        if (BuildingPreviewManager.isPreviewActive() && (previewPlayerId == null || previewPlayerId.equals(minecraft.player.getUUID()))) {
            renderCityBoundary(poseStack, cameraPos, minecraft);
            renderIntrusions(poseStack, cameraPos, minecraft);
        }
        renderSelectedBuildingBounds(poseStack, cameraPos);
    }

    public static boolean isEntireBuildingInCityTerritory() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return true;
        }
        ClientCityChunkCache cityData = ClientCityChunkCache.getInstance();
        var cityChunks = cityData.getCurrentCityChunks();
        if (cityChunks.isEmpty()) {
            return false;
        }
        return BuildingTerritoryValidator.positionBoundsInChunks(
                BuildingPreviewManager.getPreviewBlocks().stream().map(PreviewBlockData::pos).toList(),
                cityChunks
        );
    }

    private static void renderCityBoundary(PoseStack poseStack, Vec3 cameraPos, Minecraft minecraft) {
        ClientCityChunkCache cityData = ClientCityChunkCache.getInstance();
        var cityChunks = cityData.getCurrentCityChunks();
        if (cityChunks.isEmpty()) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = poseStack.last().pose();
        float red = ((COLOR_CITY_BORDER >> 16) & 0xFF) / 255.0f;
        float green = ((COLOR_CITY_BORDER >> 8) & 0xFF) / 255.0f;
        float blue = (COLOR_CITY_BORDER & 0xFF) / 255.0f;
        float alpha = ((COLOR_CITY_BORDER >> 24) & 0xFF) / 255.0f;
        final double minY = -64 - cameraPos.y;
        final double maxY = 320 - cameraPos.y;
        for (long chunkLong : cityChunks) {
            int cx = net.minecraft.world.level.ChunkPos.getX(chunkLong);
            int cz = net.minecraft.world.level.ChunkPos.getZ(chunkLong);
            double minX = (cx << 4) - cameraPos.x;
            double maxX = (cx << 4) + 16 - cameraPos.x;
            double minZ = (cz << 4) - cameraPos.z;
            double maxZ = (cz << 4) + 16 - cameraPos.z;
            if (isBoundaryFace(cityChunks, cx, cz - 1)) {
                drawQuad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, red, green, blue, alpha);
            }
            if (isBoundaryFace(cityChunks, cx, cz + 1)) {
                drawQuad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
            }
            if (isBoundaryFace(cityChunks, cx - 1, cz)) {
                drawQuad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, red, green, blue, alpha);
            }
            if (isBoundaryFace(cityChunks, cx + 1, cz)) {
                drawQuad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, red, green, blue, alpha);
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static boolean isBoundaryFace(Set<Long> cityChunks, int neighborChunkX, int neighborChunkZ) {
        // 只画领地最外圈面，相邻同城 chunk 的公共面直接跳过。
        return !cityChunks.contains(net.minecraft.world.level.ChunkPos.asLong(neighborChunkX, neighborChunkZ));
    }

    private static void renderIntrusions(PoseStack poseStack, Vec3 cameraPos, Minecraft minecraft) {
        renderCachedIntrusions(poseStack, cameraPos, minecraft);
    }
    private static void renderSelectedBuildingBounds(PoseStack poseStack, Vec3 cameraPos) {
        if (DISPLAYED_BUILDING_BOUNDS.isEmpty()) {
            return;
        }
        DISPLAYED_BUILDING_BOUNDS.values().forEach(bounds -> {
            renderWireBox(poseStack, cameraPos, bounds.bounds().inflate(SELECTED_BOUNDS_INFLATE), COLOR_SELECTED_BUILDING, true);
            bounds.markers().forEach(marker -> {
                AABB markerBox = pointMarker(marker);
                renderWireBox(poseStack, cameraPos, markerBox, marker.color(), true);
            });
        });
    }

    private static AABB pointMarker(DisplayMarker marker) {
        BlockPos pos = marker.pos();
        double radius = markerRadius(marker.color());
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + POINT_MARKER_Y_OFFSET;
        double centerZ = pos.getZ() + 0.5D;
        return new AABB(
                centerX - radius,
                centerY - radius,
                centerZ - radius,
                centerX + radius,
                centerY + radius,
                centerZ + radius
        );
    }

    private static double markerRadius(int color) {
        return switch (color) {
            case COLOR_INDUSTRIAL_WORK_POINT -> 0.14D;
            case COLOR_INDUSTRIAL_MACHINE_POINT -> 0.22D;
            case COLOR_INDUSTRIAL_OUTPUT_CONTAINER -> 0.26D;
            case COLOR_INDUSTRIAL_INPUT_CONTAINER -> POINT_MARKER_RADIUS;
            default -> POINT_MARKER_RADIUS;
        };
    }

    private static AABB previewBounds(List<PreviewBlockData> blocks) {
        if (blocks.isEmpty()) {
            return null;
        }
        AABB bounds = new AABB(blocks.getFirst().pos());
        for (int index = 1; index < blocks.size(); index++) {
            bounds = bounds.minmax(new AABB(blocks.get(index).pos()));
        }
        return bounds;
    }

    private static boolean previewTouchesOrIntersects(AABB previewBounds, AABB buildingBounds) {
        return previewBounds.inflate(BUILDING_CONTACT_EPSILON).intersects(buildingBounds);
    }

    private static void renderCachedIntrusions(PoseStack poseStack, Vec3 cameraPos, Minecraft minecraft) {
        ensurePreviewDetectionCache(minecraft);
        for (PreviewIntrusion intrusion : cachedIntrusions) {
            renderBox(poseStack, cameraPos, new AABB(intrusion.pos()), intrusion.color());
        }
        for (AABB buildingBounds : cachedTouchedBuildingBounds) {
            renderWireBox(poseStack, cameraPos, buildingBounds, COLOR_SELECTED_BUILDING);
        }
    }

    private static void ensurePreviewDetectionCache(Minecraft minecraft) {
        long revision = BuildingPreviewManager.getPreviewRevision();
        BlockPos origin = BuildingPreviewManager.getPreviewOrigin();
        int rotation = BuildingPreviewManager.getRotationDegrees();
        if (revision == intrusionCacheRevision && origin.equals(intrusionCacheOrigin) && rotation == intrusionCacheRotation) {
            return;
        }

        intrusionCacheRevision = revision;
        intrusionCacheOrigin = origin.immutable();
        intrusionCacheRotation = rotation;
        cachedIntrusions = List.of();
        cachedTouchedBuildingBounds = List.of();
        if (minecraft.level == null) {
            return;
        }

        List<PreviewBlockData> blocks = BuildingPreviewManager.getPreviewBlocks();
        AABB previewBounds = previewBounds(blocks);
        List<PlacedBuildingRecord> buildings = previewPlacedBuildings(minecraft);
        List<PreviewIntrusion> intrusions = new java.util.ArrayList<>();
        for (PreviewBlockData block : blocks) {
            BlockState worldState = minecraft.level.getBlockState(block.pos());
            boolean inPlacedBuilding = intersectsPlacedBuilding(buildings, block.pos());
            if (worldState.isAir() && !inPlacedBuilding) {
                continue;
            }
            int color = worldState.getBlock() == block.state().getBlock() ? COLOR_INTRUSION_AIR : COLOR_INTRUSION_BLOCK;
            intrusions.add(new PreviewIntrusion(block.pos().immutable(), color));
        }

        List<AABB> touchedBuildingBounds = new java.util.ArrayList<>();
        if (previewBounds != null) {
            for (PlacedBuildingRecord building : buildings) {
                AABB bounds = buildingBounds(building);
                if (previewTouchesOrIntersects(previewBounds, bounds)) {
                    touchedBuildingBounds.add(bounds);
                }
            }
        }
        cachedIntrusions = List.copyOf(intrusions);
        cachedTouchedBuildingBounds = List.copyOf(touchedBuildingBounds);
    }

    private static List<PlacedBuildingRecord> previewPlacedBuildings(Minecraft minecraft) {
        if (!(minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) || minecraft.getSingleplayerServer() == null) {
            return List.of();
        }
        var serverLevel = minecraft.getSingleplayerServer().getLevel(clientLevel.dimension());
        return serverLevel == null ? List.of() : PlacedBuildingService.getBuildings(serverLevel);
    }

    private static boolean intersectsPlacedBuilding(List<PlacedBuildingRecord> buildings, BlockPos pos) {
        for (PlacedBuildingRecord building : buildings) {
            if (contains(building, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(PlacedBuildingRecord building, BlockPos pos) {
        BlockPos min = building.minPos();
        BlockPos max = building.maxPos();
        return pos.getX() >= Math.min(min.getX(), max.getX()) && pos.getX() <= Math.max(min.getX(), max.getX())
                && pos.getY() >= Math.min(min.getY(), max.getY()) && pos.getY() <= Math.max(min.getY(), max.getY())
                && pos.getZ() >= Math.min(min.getZ(), max.getZ()) && pos.getZ() <= Math.max(min.getZ(), max.getZ());
    }

    private static AABB buildingBounds(PlacedBuildingRecord building) {
        return new AABB(building.minPos().getX(), building.minPos().getY(), building.minPos().getZ(), building.maxPos().getX() + 1, building.maxPos().getY() + 1, building.maxPos().getZ() + 1);
    }

    private static void clearPreviewDetectionCache() {
        intrusionCacheRevision = Long.MIN_VALUE;
        intrusionCacheOrigin = BlockPos.ZERO;
        intrusionCacheRotation = Integer.MIN_VALUE;
        cachedIntrusions = List.of();
        cachedTouchedBuildingBounds = List.of();
    }

    private static void renderWireBox(PoseStack poseStack, Vec3 cameraPos, AABB bounds, int color) {
        renderWireBox(poseStack, cameraPos, bounds, color, false);
    }

    private static void renderWireBox(PoseStack poseStack, Vec3 cameraPos, AABB bounds, int color, boolean throughWalls) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        if (throughWalls) {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = poseStack.last().pose();
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = ((color >> 24) & 0xFF) / 255.0f;
        double minX = bounds.minX - cameraPos.x;
        double minY = bounds.minY - cameraPos.y;
        double minZ = bounds.minZ - cameraPos.z;
        double maxX = bounds.maxX - cameraPos.x;
        double maxY = bounds.maxY - cameraPos.y;
        double maxZ = bounds.maxZ - cameraPos.z;
        drawLine(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        if (throughWalls) {
            RenderSystem.enableDepthTest();
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderBox(PoseStack poseStack, Vec3 cameraPos, AABB bounds, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = poseStack.last().pose();
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = ((color >> 24) & 0xFF) / 255.0f;
        double minX = bounds.minX - cameraPos.x;
        double minY = bounds.minY - cameraPos.y;
        double minZ = bounds.minZ - cameraPos.z;
        double maxX = bounds.maxX - cameraPos.x;
        double maxY = bounds.maxY - cameraPos.y;
        double maxZ = bounds.maxZ - cameraPos.z;
        drawQuad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        drawQuad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawQuad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, red, green, blue, alpha);
        drawQuad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, red, green, blue, alpha);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void drawQuad(BufferBuilder buffer, Matrix4f matrix, double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4, float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, (float) x1, (float) y1, (float) z1).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, (float) x2, (float) y2, (float) z2).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, (float) x3, (float) y3, (float) z3).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, (float) x4, (float) y4, (float) z4).setColor(red, green, blue, alpha);
    }

    private static void drawLine(BufferBuilder buffer, Matrix4f matrix, double x1, double y1, double z1, double x2, double y2, double z2, float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, (float) x1, (float) y1, (float) z1).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, (float) x2, (float) y2, (float) z2).setColor(red, green, blue, alpha);
    }

    public record DisplayMarker(BlockPos pos, int color) {
    }

    private record DisplayedBuildingBounds(AABB bounds, List<DisplayMarker> markers) {
    }

    private record PreviewIntrusion(BlockPos pos, int color) {
    }
}
