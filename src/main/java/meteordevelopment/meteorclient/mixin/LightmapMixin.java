/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.mojang.blaze3d.pipeline.TextureTarget;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Fullbright;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT(1.21.4): RenderSystem.getDevice()/GpuTexture do not exist on 1.21.4. On this version LightTexture
// owns a plain TextureTarget (com.mojang.blaze3d.pipeline.TextureTarget) whose clear color is set to
// (1,1,1,1) in the constructor, so calling target.clear() (raw GL clear via GlStateManager under the hood)
// produces the same fullbright result as the 26.1.2 clearColorTexture(...) call did.
@Mixin(LightTexture.class)
public abstract class LightmapMixin {
    @Shadow
    @Final
    private TextureTarget target;

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void render$fullbright(float partialTick, CallbackInfo ci) {
        if (Modules.get().get(Fullbright.class).getGamma() || Modules.get().isActive(Xray.class)) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("lightmap");

            target.clear();

            profiler.pop();
            ci.cancel();
        }
    }
}
