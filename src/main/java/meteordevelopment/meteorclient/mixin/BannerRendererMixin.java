/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.MaterialSet;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BannerRenderer.class)
public abstract class BannerRendererMixin {
    @Shadow
    @Final
    private MaterialSet materials;

    @Shadow
    @Final
    private BannerModel standingModel;

    @Shadow
    @Final
    private BannerModel wallModel;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BannerRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void injectRender1(BannerRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).getBannerRenderMode() == NoRender.BannerRenderMode.None) ci.cancel();
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BannerRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BannerRenderer;submitBanner(Lnet/minecraft/client/resources/model/MaterialSet;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIFLnet/minecraft/client/model/object/banner/BannerModel;Lnet/minecraft/client/model/object/banner/BannerFlagModel;FLnet/minecraft/world/item/DyeColor;Lnet/minecraft/world/level/block/entity/BannerPatternLayers;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V"),
        cancellable = true
    )
    private void injectRender2(BannerRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).getBannerRenderMode() == NoRender.BannerRenderMode.Pillar) {
            renderPillar(
                poseStack,
                submitNodeCollector,
                state.lightCoords,
                state.angle,
                state.standing ? this.standingModel : this.wallModel,
                this.materials,
                state.breakProgress
            );
            ci.cancel();
        }
    }

    @Unique
    private static void renderPillar(PoseStack matrices, SubmitNodeCollector submitNodeCollector, int light, float rotation, BannerModel model, MaterialSet materials, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        // PORT(1.21.11): submitBanner applies the banner transform itself on 1.21.11, so the pillar has to as well
        matrices.pushPose();
        matrices.translate(0.5F, 0.0F, 0.5F);
        matrices.mulPose(Axis.YP.rotationDegrees(rotation));
        matrices.scale(0.6666667F, -0.6666667F, -0.6666667F);
        Material sprite = ModelBakery.BANNER_BASE;
        submitNodeCollector.submitModel(
            model,
            Unit.INSTANCE,
            matrices,
            sprite.renderType(RenderTypes::entitySolid),
            light,
            OverlayTexture.NO_OVERLAY,
            -1,
            materials.get(sprite),
            0,
            breakProgress
        );
        matrices.popPose();
    }
}
