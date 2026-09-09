/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import net.minecraft.client.renderer.FogRenderer;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @ModifyReturnValue(method = "computeFogColor", at = @At("RETURN"))
    private static Vector4f modifyFogDistance(Vector4f fogColor) {
        if (Modules.get() == null) return fogColor;

        Ambience ambience = Modules.get().get(Ambience.class);
        if (ambience.isActive() && ambience.customFogColor.get()) {
            return ambience.fogColor.get().getVec4f();
        }

        return fogColor;
    }

    @ModifyExpressionValue(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/FogRenderer;fogEnabled:Z", opcode = Opcodes.GETSTATIC))
    private static boolean modifyFogEnabled(boolean original) {
        if (Modules.get() == null) return original;

        return original && !Modules.get().get(NoRender.class).noFog();
    }
}
