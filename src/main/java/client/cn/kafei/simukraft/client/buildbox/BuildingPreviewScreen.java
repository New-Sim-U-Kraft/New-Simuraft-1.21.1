package client.cn.kafei.simukraft.client.buildbox;

import client.cn.kafei.simukraft.client.freecamera.FreeCameraManager;
import client.cn.kafei.simukraft.client.toast.ClientInfoToast;
import client.cn.kafei.simukraft.client.ui.SimuKraftUiTheme;
import common.cn.kafei.simukraft.building.BuildingStructure;
import common.cn.kafei.simukraft.network.building.BuildBoxStartConstructionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@SuppressWarnings("null")
public final class BuildingPreviewScreen extends Screen {
    private final Screen parent;
    private final BuildingCacheService.BuildingMeta building;
    private final BlockPos buildBoxPos;
    private final BuildingStructure structure;

    public BuildingPreviewScreen(Screen parent, BuildingCacheService.BuildingMeta building, BlockPos buildBoxPos, BuildingStructure structure) {
        super(Component.translatable("gui.building_preview.title"));
        this.parent = parent;
        this.building = building;
        this.buildBoxPos = buildBoxPos;
        this.structure = structure;
    }

    @Override
    protected void init() {
        super.init();
        BlockPos previewOrigin = buildBoxPos.offset(1, 0, 1);
        BuildingPreviewManager.startPreview(structure, previewOrigin);
        if (!BuildingPreviewManager.isPreviewActive()) {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
            return;
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            BuildingBoundsRenderer.setPreviewPlayerId(this.minecraft.player.getUUID());
        }
        FreeCameraManager.activate();
    }

    @Override
    public void removed() {
        super.removed();
        releasePreviewMouse();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int y = 14;
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.building_preview.title_with_name", building.name()), centerX, y, SimuKraftUiTheme.TEXT_PRIMARY_COLOR);
        y += 15;
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.building_preview.hint.preview_move"), centerX, y, SimuKraftUiTheme.TEXT_WARNING_COLOR);
        y += 12;
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.building_preview.hint.camera"), centerX, y, SimuKraftUiTheme.TEXT_INFO_COLOR);
        y += 12;
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.building_preview.hint.actions"), centerX, y, SimuKraftUiTheme.TEXT_WARNING_COLOR);
        y += 12;
        BlockPos origin = BuildingPreviewManager.getPreviewOrigin();
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.building_preview.origin_info", origin.getX(), origin.getY(), origin.getZ(), BuildingPreviewManager.getRotationDegrees()), centerX, y, SimuKraftUiTheme.TEXT_SUCCESS_COLOR);
        y += 12;
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.building_preview.block_count", structure.blockCount()), centerX, y, SimuKraftUiTheme.TEXT_INFO_COLOR);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            exitPreview();
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> {
                BuildingPreviewManager.movePreviewRelativeToCamera(0, 1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                BuildingPreviewManager.movePreviewRelativeToCamera(0, -1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                BuildingPreviewManager.movePreviewRelativeToCamera(-1, 0);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                BuildingPreviewManager.movePreviewRelativeToCamera(1, 0);
                return true;
            }
            case GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_PAGE_UP -> {
                BuildingPreviewManager.movePreviewVertical(1);
                return true;
            }
            case GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_PAGE_DOWN -> {
                BuildingPreviewManager.movePreviewVertical(-1);
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                BuildingPreviewManager.rotatePreview(structure);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!BuildingBoundsRenderer.isEntireBuildingInCityTerritory()) {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        ClientInfoToast.show(
                                Component.translatable("toast.simukraft.title"),
                                Component.translatable("message.simukraft.construction.outside_city"),
                                "warning"
                        );
                    }
                    return true;
                }
                startConstruction();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    private void exitPreview() {
        var minecraft = this.minecraft;
        BuildingPreviewManager.clearPreview();
        FreeCameraManager.deactivate();
        BuildingBoundsRenderer.setPreviewPlayerId(null);
        releasePreviewMouse();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private void startConstruction() {
        var minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        PacketDistributor.sendToServer(new BuildBoxStartConstructionPacket(buildBoxPos, building.category(), stripExtension(building.metaFileName()), BuildingPreviewManager.getPreviewOrigin(), BuildingPreviewManager.getRotationDegrees()));
        BuildingPreviewManager.clearPreview();
        FreeCameraManager.deactivate();
        BuildingBoundsRenderer.setPreviewPlayerId(null);
        releasePreviewMouse();
        minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private void releasePreviewMouse() {
        if (this.minecraft != null && this.minecraft.mouseHandler.isMouseGrabbed()) {
            this.minecraft.mouseHandler.releaseMouse();
        }
    }
}
