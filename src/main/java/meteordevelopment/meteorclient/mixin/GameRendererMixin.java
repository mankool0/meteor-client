/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.MixinPlugin;
import meteordevelopment.meteorclient.events.render.GetFovEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.render.RenderAfterWorldEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.systems.modules.render.Zoom;
import meteordevelopment.meteorclient.systems.modules.world.HighwayBuilder;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.CustomBannerGuiElementRenderer;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private Camera mainCamera;

    @Unique
    private Renderer3D renderer;

    @Unique
    private Renderer3D depthRenderer;

    @Unique
    private final PoseStack matrices = new PoseStack();

    @Shadow
    protected abstract void bobView(final PoseStack poseStack, final float partialTicks);

    @Shadow
    protected abstract void bobHurt(final PoseStack poseStack, final float partialTicks);

    @Shadow
    public abstract void pick(float partialTicks);

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;<init>(Lnet/minecraft/client/gui/render/state/GuiRenderState;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;Ljava/util/List;)V"))
    private List<PictureInPictureRenderer<?>> meteor$addSpecialRenderers(List<PictureInPictureRenderer<?>> list) {
        List<PictureInPictureRenderer<?>> result = new ArrayList<>(list.size() + 1);
        result.addAll(list);
        result.add(new CustomBannerGuiElementRenderer(renderBuffers.bufferSource(), minecraft.getAtlasManager()));
        return result;
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand"))
    private void onRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix, @Local(ordinal = 1) Matrix4f modelViewMatrix, @Local(ordinal = 0) float worldPartialTicks, @Local PoseStack bobStack) {
        if (!Utils.canUpdate()) return;

        Profiler.get().push(MeteorClient.MOD_ID + "_render");

        // Create renderer and event

        if (renderer == null)
            renderer = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES, MeteorRenderPipelines.WORLD_COLORED);
        if (depthRenderer == null)
            depthRenderer = new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH, MeteorRenderPipelines.WORLD_COLORED_DEPTH);
        Render3DEvent event = Render3DEvent.get(bobStack, renderer, depthRenderer, worldPartialTicks, mainCamera.position().x, mainCamera.position().y, mainCamera.position().z);

        // Update model view matrix

        RenderSystem.getModelViewStack().pushMatrix().mul(modelViewMatrix);

        matrices.pushPose();
        bobHurt(matrices, mainCamera.getPartialTickTime());
        if (minecraft.options.bobView().get()) {
            bobView(matrices, mainCamera.getPartialTickTime());
        }

        Matrix4f inverseBob = new Matrix4f(matrices.last().pose()).invert();
        RenderSystem.getModelViewStack().mul(inverseBob);
        matrices.popPose();

        // Call utility classes (apply bob correction when Iris shaders are active)

        Matrix4fc correctedPosition = MixinPlugin.isIrisPresent && RenderUtils.isShaderPackInUse() ? new Matrix4f(modelViewMatrix).mul(inverseBob) : modelViewMatrix;
        RenderUtils.updateScreenCenter(projectionMatrix, correctedPosition);
        NametagUtils.onRender(modelViewMatrix);

        // Render

        renderer.begin();
        depthRenderer.begin();
        MeteorClient.EVENT_BUS.post(event);
        renderer.render(bobStack);
        depthRenderer.render(bobStack);

        // Revert model view matrix

        RenderSystem.getModelViewStack().popMatrix();

        Profiler.get().pop();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void onRenderLevelTail(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(RenderAfterWorldEvent.get());
    }

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void onDisplayItemActivation(ItemStack itemStack, CallbackInfo ci) {
        if (itemStack.getItem() == Items.TOTEM_OF_UNDYING && Modules.get().get(NoRender.class).noTotemAnimation()) {
            ci.cancel();
        }
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
    private float applyCameraTransformationsMathHelperLerpProxy(float original) {
        return Modules.get().get(NoRender.class).noNausea() ? 0 : original;
    }

    // PORT(1.21.11): FOV is computed by GameRenderer#getFov on 1.21.11 (moved to Camera#calculateFov in 26.1)
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float modifyFov(float original) {
        return MeteorClient.EVENT_BUS.post(GetFovEvent.get(original)).fov;
    }

    // Freecam
    // PORT(1.21.11): pick lives on GameRenderer on 1.21.11 (moved to Minecraft in 26.1)

    @Unique
    private boolean freecamSet = false;

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void updateTargetedEntityInvoke(float partialTicks, CallbackInfo ci) {
        Freecam freecam = Modules.get().get(Freecam.class);
        boolean highwayBuilder = Modules.get().isActive(HighwayBuilder.class);

        if ((freecam.isActive() || highwayBuilder) && minecraft.getCameraEntity() != null && !freecamSet) {
            ci.cancel();
            Entity cameraE = minecraft.getCameraEntity();

            double x = cameraE.getX();
            double y = cameraE.getY();
            double z = cameraE.getZ();
            double lastX = cameraE.xo;
            double lastY = cameraE.yo;
            double lastZ = cameraE.zo;
            float yaw = cameraE.getYRot();
            float pitch = cameraE.getXRot();
            float lastYaw = cameraE.yRotO;
            float lastPitch = cameraE.xRotO;

            if (highwayBuilder) {
                cameraE.setYRot(mainCamera.yRot());
                cameraE.setXRot(mainCamera.xRot());
            } else {
                ((IVec3) cameraE.position()).meteor$set(freecam.pos.x, freecam.pos.y - cameraE.getEyeHeight(cameraE.getPose()), freecam.pos.z);
                cameraE.xo = freecam.prevPos.x;
                cameraE.yo = freecam.prevPos.y - cameraE.getEyeHeight(cameraE.getPose());
                cameraE.zo = freecam.prevPos.z;
                cameraE.setYRot(freecam.yaw);
                cameraE.setXRot(freecam.pitch);
                cameraE.yRotO = freecam.lastYaw;
                cameraE.xRotO = freecam.lastPitch;
            }

            freecamSet = true;
            pick(partialTicks);
            freecamSet = false;

            ((IVec3) cameraE.position()).meteor$set(x, y, z);
            cameraE.xo = lastX;
            cameraE.yo = lastY;
            cameraE.zo = lastZ;
            cameraE.setYRot(yaw);
            cameraE.setXRot(pitch);
            cameraE.yRotO = lastYaw;
            cameraE.xRotO = lastPitch;
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void renderItemInHand(float deltaPartialTick, boolean sleeping, Matrix4f modelViewMatrix, CallbackInfo ci) {
        if (!Modules.get().get(Freecam.class).renderHands() || !Modules.get().get(Zoom.class).renderHands()) {
            ci.cancel();
        }
    }
}
