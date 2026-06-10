package client.cn.kafei.simukraft.client.renderer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.mojang.blaze3d.vertex.PoseStack;
import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public class CitizenRenderer extends MobRenderer<CitizenEntity, CitizenModel> {
    private static final ResourceLocation DEFAULT_TEXTURE = ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "textures/entity/male/custom_male_entity_0.png");
    private final CitizenModel slimModel;
    private final CitizenModel defaultModel;

    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new CitizenModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
        this.slimModel = this.model;
        this.defaultModel = new CitizenModel(context.bakeLayer(ModelLayers.PLAYER), false);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        return textureFromPath(entity.getSkinPath());
    }

    @Override
    public void render(CitizenEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = useDefaultModel(entity) ? defaultModel : slimModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    protected void scale(CitizenEntity livingEntity, PoseStack poseStack, float partialTickTime) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected void renderNameTag(CitizenEntity entity, Component component, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        renderCitizenNameTag(entity, poseStack, bufferSource, packedLight);
    }

    private void renderCitizenNameTag(CitizenEntity entity, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!shouldShowName(entity)) {
            return;
        }
        float yOffset = entity.getBbHeight() + 0.82F;
        for (CitizenOverheadStatusRegistry.StatusLine line : CitizenOverheadStatusRegistry.resolve(entity)) {
            renderExtraLine(entity, line.text(), poseStack, bufferSource, packedLight, yOffset, line.color(), line.scale());
            yOffset -= 0.23F;
        }
    }

    @Override
    protected boolean shouldShowName(CitizenEntity entity) {
        if (entity.isInvisible()) {
            return false;
        }
        Camera camera = this.entityRenderDispatcher.camera;
        if (camera == null) {
            return false;
        }
        double distance = camera.getPosition().distanceTo(entity.position());
        return distance < 45.0D || entity.hasCustomName() && entity == camera.getEntity();
    }

    private static ResourceLocation textureFromPath(String skinPath) {
        if (skinPath == null || skinPath.isBlank()) {
            return DEFAULT_TEXTURE;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(skinPath);
        return parsed != null ? parsed : DEFAULT_TEXTURE;
    }

    private static boolean useDefaultModel(CitizenEntity entity) {
        String skinPath = entity.getSkinPath();
        if (skinPath == null) {
            return false;
        }
        String fileName = skinPath;
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        if (fileName.endsWith(".png")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        return fileName.endsWith("_f");
    }

    private void renderExtraLine(CitizenEntity entity, Component component, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float yOffset, int color, float scale) {
        if (this.entityRenderDispatcher.distanceToSqr(entity) > 4096.0D) {
            return;
        }
        boolean visible = !entity.isDiscrete();
        int backgroundColor = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        poseStack.pushPose();
        poseStack.translate(0.0F, yOffset, 0.0F);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(scale, -scale, scale);
        Matrix4f matrix = poseStack.last().pose();
        float x = (float) (-font.width(component) / 2);
        int argbColor = color | 0xFF000000;
        font.drawInBatch(component, x, 0.0F, argbColor, false, matrix, bufferSource, visible ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, backgroundColor, packedLight);
        if (visible) {
            font.drawInBatch(component, x, 0.0F, argbColor, false, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
        }
        poseStack.popPose();
    }
}
