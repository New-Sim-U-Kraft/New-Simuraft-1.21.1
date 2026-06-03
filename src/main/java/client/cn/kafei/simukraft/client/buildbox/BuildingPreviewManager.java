package client.cn.kafei.simukraft.client.buildbox;

import client.cn.kafei.simukraft.client.city.ClientCityChunkCache;
import client.cn.kafei.simukraft.client.freecamera.FreeCameraManager;
import client.cn.kafei.simukraft.client.toast.ClientInfoToast;
import common.cn.kafei.simukraft.building.BuildingBlockData;
import common.cn.kafei.simukraft.building.BuildingStructure;
import common.cn.kafei.simukraft.building.BuildingStructureService;
import common.cn.kafei.simukraft.building.BuildingTerritoryValidator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class BuildingPreviewManager {
    private static final List<PreviewBlockData> PREVIEW_BLOCKS = new ArrayList<>();
    private static BlockPos previewOrigin = BlockPos.ZERO;
    private static int rotationDegrees;
    private static int blockCount;
    private static boolean active;
    private static String buildingName = "";
    private static PreviewMesh cachedMesh = PreviewMesh.EMPTY;
    private static long previewRevision;

    private BuildingPreviewManager() {
    }

    public static void startPreview(BuildingStructure structure, BlockPos origin) {
        clearPreview();
        if (structure == null || origin == null || !isPlacementAllowed(structure, origin, 0, false)) {
            return;
        }
        previewOrigin = origin;
        rotationDegrees = 0;
        buildingName = structure.displayName();
        structure.category();
        structure.fileName();
        active = true;
        blockCount = structure.blockCount();
        rebuildBlocks(structure);
    }

    public static void movePreviewRelative(int dx, int dy, int dz) {
        if (!active) {
            return;
        }
        if (!isMovedPlacementAllowed(dx, dy, dz, true)) {
            return;
        }
        previewOrigin = previewOrigin.offset(dx, dy, dz);
        offsetBlocks(dx, dy, dz);
    }

    public static void movePreviewRelativeToCamera(int right, int forward) {
        if (!active) {
            return;
        }
        float yaw = FreeCameraManager.getYaw();
        double yawRad = Math.toRadians(yaw);
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        int dx = (int) Math.round(-sinYaw * forward - cosYaw * right);
        int dz = (int) Math.round(cosYaw * forward - sinYaw * right);
        movePreviewRelative(dx, 0, dz);
    }

    public static void movePreviewVertical(int dy) {
        movePreviewRelative(0, dy, 0);
    }

    public static void rotatePreview(BuildingStructure structure) {
        if (!active || structure == null) {
            return;
        }
        int nextRotation = Math.floorMod(rotationDegrees + 90, 360);
        if (!isPlacementAllowed(structure, previewOrigin, nextRotation, true)) {
            return;
        }
        rotationDegrees = nextRotation;
        rebuildBlocks(structure);
    }

    public static void clearPreview() {
        PREVIEW_BLOCKS.clear();
        previewOrigin = BlockPos.ZERO;
        rotationDegrees = 0;
        blockCount = 0;
        active = false;
        buildingName = "";
        cachedMesh.close();
        cachedMesh = PreviewMesh.EMPTY;
        previewRevision++;
    }

    public static List<PreviewBlockData> getPreviewBlocks() {
        return List.copyOf(PREVIEW_BLOCKS);
    }

    public static BlockPos getPreviewOrigin() {
        return previewOrigin;
    }

    public static int getRotationDegrees() {
        return rotationDegrees;
    }

    public static int getBlockCount() {
        return blockCount;
    }

    public static boolean isPreviewActive() {
        return active;
    }

    public static String getBuildingName() {
        return buildingName;
    }

    public static PreviewMesh getCachedMesh() {
        return cachedMesh;
    }

    public static long getPreviewRevision() {
        return previewRevision;
    }

    private static void rebuildBlocks(BuildingStructure structure) {
        PREVIEW_BLOCKS.clear();
        List<BuildingBlockData> blocks = BuildingStructureService.resolvePlacedBlocks(structure, previewOrigin, rotationDegrees);
        for (BuildingBlockData block : blocks) {
            PREVIEW_BLOCKS.add(new PreviewBlockData(block.relativePos(), block.state(), 15728880));
        }
        previewRevision++;
        rebuildMesh();
    }

    private static void offsetBlocks(int dx, int dy, int dz) {
        List<PreviewBlockData> snapshot = new ArrayList<>(PREVIEW_BLOCKS);
        PREVIEW_BLOCKS.clear();
        for (PreviewBlockData block : snapshot) {
            PREVIEW_BLOCKS.add(new PreviewBlockData(block.pos().offset(dx, dy, dz), block.state(), block.packedLight()));
        }
        cachedMesh.offsetOrigin(dx, dy, dz);
        previewRevision++;
    }

    private static void rebuildMesh() {
        cachedMesh.close();
        cachedMesh = PreviewMeshBuilder.build(PREVIEW_BLOCKS);
    }

    private static boolean isPlacementAllowed(BuildingStructure structure, BlockPos origin, int rotation, boolean notifyOnFailure) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }
        ClientCityChunkCache chunkCache = ClientCityChunkCache.getInstance();
        if (chunkCache.getCurrentCityId() == null) {
            return true;
        }
        List<BlockPos> positions = BuildingStructureService.resolvePlacedBlocks(structure, origin, rotation).stream()
                .map(BuildingBlockData::relativePos)
                .toList();
        if (BuildingTerritoryValidator.positionBoundsInChunks(positions, chunkCache.getCurrentCityChunks())) {
            return true;
        }
        notifyOutsideCity(notifyOnFailure);
        return false;
    }

    private static boolean isMovedPlacementAllowed(int dx, int dy, int dz, boolean notifyOnFailure) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }
        ClientCityChunkCache chunkCache = ClientCityChunkCache.getInstance();
        if (chunkCache.getCurrentCityId() == null) {
            return true;
        }
        List<BlockPos> positions = PREVIEW_BLOCKS.stream()
                .map(block -> block.pos().offset(dx, dy, dz))
                .toList();
        if (BuildingTerritoryValidator.positionBoundsInChunks(positions, chunkCache.getCurrentCityChunks())) {
            return true;
        }
        notifyOutsideCity(notifyOnFailure);
        return false;
    }

    private static void notifyOutsideCity(boolean notifyOnFailure) {
        if (notifyOnFailure) {
            ClientInfoToast.show(
                    Component.translatable("toast.simukraft.title"),
                    Component.translatable("message.simukraft.construction.outside_city"),
                    "warning"
            );
        }
    }
}
