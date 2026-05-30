package client.cn.kafei.simukraft.mixin;

import client.cn.kafei.simukraft.client.buildbox.BuildingPreviewScreen;
import client.cn.kafei.simukraft.client.freecamera.FreeCameraManager;
import client.cn.kafei.simukraft.client.path.NpcPathDebugRenderer;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public final class MixinKeyboardHandler {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void simukraft$keyPress(long window, int key, int scanCode, int action, int modifiers, CallbackInfo callbackInfo) {
        if (NpcPathDebugRenderer.handleToggleShortcut(window, key, action, modifiers)) {
            callbackInfo.cancel();
            return;
        }
        if (!FreeCameraManager.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof BuildingPreviewScreen) && minecraft.screen != null) {
            return;
        }
        boolean pressed = action == GLFW.GLFW_PRESS;
        boolean released = action == GLFW.GLFW_RELEASE;
        if (!pressed && !released) {
            return;
        }
        boolean state = pressed;
        switch (key) {
            case GLFW.GLFW_KEY_W -> FreeCameraManager.setMovingForward(state);
            case GLFW.GLFW_KEY_S -> FreeCameraManager.setMovingBackward(state);
            case GLFW.GLFW_KEY_A -> FreeCameraManager.setMovingLeft(state);
            case GLFW.GLFW_KEY_D -> FreeCameraManager.setMovingRight(state);
            case GLFW.GLFW_KEY_SPACE -> FreeCameraManager.setMovingUp(state);
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> FreeCameraManager.setMovingDown(state);
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> FreeCameraManager.setSprinting(state);
            default -> {
            }
        }
    }
}
