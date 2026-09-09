/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Chams;
import net.minecraft.client.model.EndCrystalModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalRenderer.class)
public abstract class EndCrystalRendererMixin {
    // Chams

    @Unique
    private Chams chams;

    @Unique
    private Chams getChams() {
        if (chams == null) chams = Modules.get().get(Chams.class);
        return chams;
    }

    // Chams - Texture

    @Shadow
    @Final
    private static ResourceLocation END_CRYSTAL_LOCATION;

    // Chams - Scale

    @Inject(
        method = "render(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V")
    )
    private void render$scale(EndCrystalRenderState state, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        if (!getChams().isActive() || !getChams().crystals.get()) return;

        float v = getChams().crystalsScale.get().floatValue();
        poseStack.scale(v, v, v);
    }

    // Chams - Texture

    @WrapOperation(
        method = "render(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    )
    private VertexConsumer render$getBuffer(MultiBufferSource bufferSource, RenderType renderType, Operation<VertexConsumer> original) {
        if (getChams().isActive() && getChams().crystals.get()) {
            RenderType type = RenderType.entityTranslucent(getChams().crystalsTexture.get() ? END_CRYSTAL_LOCATION : Chams.BLANK);
            return original.call(bufferSource, type);
        }

        return original.call(bufferSource, renderType);
    }

    // Chams - Color

    @WrapOperation(
        method = "render(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EndCrystalModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V")
    )
    private void render$color(EndCrystalModel model, PoseStack poseStack, VertexConsumer vertexConsumer, int light, int overlay, Operation<Void> original) {
        if (getChams().isActive() && getChams().crystals.get()) {
            model.renderToBuffer(poseStack, vertexConsumer, light, overlay, getChams().crystalsColor.get().getPacked());
        } else {
            original.call(model, poseStack, vertexConsumer, light, overlay);
        }
    }
}
