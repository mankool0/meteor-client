/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import meteordevelopment.meteorclient.mixininterface.IEntityRenderState;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Chams;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.opengl.GL11C.*;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {
    // Freecam

    @ModifyExpressionValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getCameraEntity()Lnet/minecraft/world/entity/Entity;"))
    private Entity hasLabelGetCameraEntityProxy(Entity cameraEntity) {
        return Modules.get().isActive(Freecam.class) ? null : cameraEntity;
    }

    // Player model rendering in main menu

    @ModifyExpressionValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getTeam()Lnet/minecraft/world/scores/PlayerTeam;"))
    private PlayerTeam hasLabelClientPlayerEntityGetScoreboardTeamProxy(PlayerTeam team) {
        return (mc.player == null) ? null : team;
    }

    // Chams

    @Unique
    private Chams chams;

    @Unique
    private Chams getChams() {
        if (chams == null) chams = Modules.get().get(Chams.class);
        return chams;
    }

    // Chams - player color

    @WrapWithCondition(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private boolean render$render(EntityModel<? super S> instance, PoseStack matrixStack, VertexConsumer vertexConsumer, int light, int overlay, int color, S state, PoseStack matrices, MultiBufferSource bufferSource, int packedLight) {
        if (!getChams().isActive() || !getChams().players.get() || !(((IEntityRenderState) state).meteor$getEntity() instanceof Player player))
            return true;
        if (getChams().ignoreSelf.get() && player == mc.player) return true;

        instance.renderToBuffer(matrixStack, vertexConsumer, light, overlay, PlayerUtils.getPlayerColor(player, getChams().playersColor.get()).getPacked());
        return false;
    }

    // Chams - Player texture

    @ModifyReturnValue(method = "getRenderType", at = @At("RETURN"))
    private RenderType getRenderPlayer(RenderType original, S state, boolean isBodyVisible, boolean forceTransparent, boolean appearGlowing) {
        if (!getChams().isActive() || !(((IEntityRenderState) state).meteor$getEntity() instanceof Player player))
            return original;

        if (!getChams().players.get() || getChams().playersTexture.get())
            return original;
        if (getChams().ignoreSelf.get() && player == mc.player)
            return original;

        return RenderType.itemEntityTranslucentCull(Chams.BLANK);
    }

    // Chams - Through walls

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), cancellable = true)
    private void render$Head(S state, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        Entity entity = ((IEntityRenderState) state).meteor$getEntity();
        if (!(entity instanceof LivingEntity livingEntity)) return;

        if (Modules.get().get(NoRender.class).noDeadEntities() && livingEntity.isDeadOrDying()) ci.cancel();

        if (getChams().shouldRender(entity)) {
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1.0f, -1100000.0f);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("TAIL"))
    private void render$Tail(S state, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        Entity entity = ((IEntityRenderState) state).meteor$getEntity();
        if (!(entity instanceof LivingEntity livingEntity)) return;

        if (getChams().shouldRender(livingEntity)) {
            glPolygonOffset(1.0f, 1100000.0f);
            glDisable(GL_POLYGON_OFFSET_FILL);
        }
    }
}
