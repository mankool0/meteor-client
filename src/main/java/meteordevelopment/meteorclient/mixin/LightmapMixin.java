/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Fullbright;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.ARGB;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightTexture.class)
public abstract class LightmapMixin {
    @Shadow
    @Final
    private GpuTexture texture;

    // PORT(1.21.11): 1.21.11 has no separate lightmap render state, hook LightTexture#updateLightTexture after its profiler push instead
    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", shift = At.Shift.AFTER), cancellable = true)
    private void render$fullbright(float tickProgress, CallbackInfo ci, @Local ProfilerFiller profile) {
        if (Modules.get().get(Fullbright.class).getGamma() || Modules.get().isActive(Xray.class)) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, ARGB.color(255, 255, 255, 255));

            profile.pop();
            ci.cancel();
        }
    }

    // PORT(1.21.11): restored from the 1.21.11 implementation, the darkness pulse is applied to the lightmap here
    @Inject(method = "calculateDarknessScale", at = @At("HEAD"), cancellable = true)
    private void getDarknessFactor(LivingEntity entity, float factor, float tickProgress, CallbackInfoReturnable<Float> cir) {
        if (Modules.get().get(NoRender.class).noDarkness()) cir.setReturnValue(0.0f);
    }
}
