/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.mixininterface.IEntityRenderState;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.ESP;
import meteordevelopment.meteorclient.systems.modules.render.Fullbright;
import meteordevelopment.meteorclient.systems.modules.render.Nametags;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {

    @Unique
    private ESP esp;
    @Unique
    private NoRender noRender;

    @Unique
    private ESP getEsp() {
        if (esp == null) esp = Modules.get().get(ESP.class);
        return esp;
    }

    @Unique
    private NoRender getNoRender() {
        if (noRender == null) noRender = Modules.get().get(NoRender.class);
        return noRender;
    }

    @Inject(method = "getNameTag", at = @At("HEAD"), cancellable = true)
    private void onRenderLabel(T entity, CallbackInfoReturnable<Component> cir) {
        if (getNoRender().noNametags()) cir.setReturnValue(null);
        if (!(entity instanceof Player player)) return;
        if (Modules.get().get(Nametags.class).playerNametags() && !(EntityUtils.getGameMode(player) == null && Modules.get().get(Nametags.class).excludeBots()))
            cir.setReturnValue(null);
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void shouldRender(T entity, Frustum culler, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (getNoRender().noEntity(entity)) cir.setReturnValue(false);
        if (getNoRender().noFallingBlocks() && entity instanceof FallingBlockEntity) cir.setReturnValue(false);
    }

    @Inject(method = "affectedByCulling", at = @At("HEAD"), cancellable = true)
    void canBeCulled(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (getEsp().forceRender()) cir.setReturnValue(false);
    }

    @ModifyReturnValue(method = "getSkyLightLevel", at = @At("RETURN"))
    private int onGetSkyLight(int original) {
        return Math.max(Modules.get().get(Fullbright.class).getLuminance(LightLayer.SKY), original);
    }

    @ModifyReturnValue(method = "getBlockLightLevel", at = @At("RETURN"))
    private int onGetBlockLight(int original) {
        return Math.max(Modules.get().get(Fullbright.class).getLuminance(LightLayer.BLOCK), original);
    }

    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I"))
    private int onGetLightLevel(int original) {
        return Math.max(Modules.get().get(Fullbright.class).getLuminance(LightLayer.BLOCK), original);
    }

    @ModifyReturnValue(method = "getShadowRadius", at = @At("RETURN"))
    private float updateShadow(float original, S state) {
        if (getNoRender().noDeadEntities() && state instanceof LivingEntityRenderState livingEntityRenderState && livingEntityRenderState.deathTime > 0) {
            return 0;
        }

        return original;
    }

    // IEntityRenderState

    @ModifyReturnValue(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
    private S createRenderState$setEntity(S state, T entity, float partialTicks) {
        ((IEntityRenderState) state).meteor$setEntity(entity);
        return state;
    }
}
