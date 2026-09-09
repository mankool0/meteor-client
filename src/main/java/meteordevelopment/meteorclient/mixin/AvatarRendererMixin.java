/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Chams;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(PlayerRenderer.class)
public abstract class AvatarRendererMixin {
    // Chams

    @Unique
    private Chams chams;

    @Unique
    private Chams getChams() {
        if (chams == null) chams = Modules.get().get(Chams.class);
        return chams;
    }

    // Chams - Player scale

    @Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V", at = @At("RETURN"))
    private void updateRenderState$scale(AbstractClientPlayer entity, PlayerRenderState state, float partialTicks, CallbackInfo ci) {
        if (!getChams().isActive() || !getChams().players.get()) return;
        if (getChams().ignoreSelf.get() && entity == mc.player) return;

        float v = getChams().playersScale.get().floatValue();
        state.scale *= v;

        if (state.nameTagAttachment != null)
            ((IVec3) state.nameTagAttachment).meteor$setY(state.nameTagAttachment.y + (entity.getBbHeight() * v - entity.getBbHeight()));
    }

    // Chams - Hand Texture

    @ModifyExpressionValue(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;entityTranslucent(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"))
    private RenderType renderArm$texture(RenderType original, PoseStack poseStack, MultiBufferSource bufferSource, int lightCoords, ResourceLocation skinTexture, ModelPart arm, boolean hasSleeve) {
        if (getChams().isActive() && getChams().hand.get()) {
            ResourceLocation texture = getChams().handTexture.get() ? skinTexture : Chams.BLANK;
            return RenderType.entityTranslucent(texture);
        }

        return original;
    }

    // Chams - Hand Color

    @WrapWithCondition(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private boolean renderArm$color(ModelPart instance, PoseStack matrixStack, VertexConsumer vertices, int light, int overlay) {
        if (getChams().isActive() && getChams().hand.get()) {
            instance.render(matrixStack, vertices, light, overlay, getChams().handColor.get().getPacked());
            return false;
        }

        return true;
    }

    // Rotations

    @Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V", at = @At("RETURN"))
    private void extractRenderState$rotations(AbstractClientPlayer entity, PlayerRenderState state, float partialTicks, CallbackInfo ci) {
        if (Rotations.rotating && entity == mc.player) {
            state.yRot = 0;
            state.bodyRot = Rotations.serverYaw;
            state.xRot = Rotations.serverPitch;
        }
    }
}
