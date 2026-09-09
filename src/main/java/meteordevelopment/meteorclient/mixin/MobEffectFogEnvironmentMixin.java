/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

// PORT(1.21.4): the standalone MobEffectFogEnvironment abstraction doesn't exist yet - the equivalent in 1.21.4 is
// the (package-private) FogRenderer.MobEffectFogFunction interface (implemented by FogRenderer.BlindnessFogFunction/
// DarknessFogFunction), whose "isApplicable" equivalent is the default method "isEnabled(LivingEntity, float)".
// Targeted by string since the interface isn't public and can't be referenced by a .class literal here.
@Mixin(targets = "net.minecraft.client.renderer.FogRenderer$MobEffectFogFunction")
public interface MobEffectFogEnvironmentMixin {
    @Shadow
    Holder<MobEffect> getMobEffect();

    @ModifyReturnValue(method = "isEnabled", at = @At("RETURN"))
    private boolean modifyShouldApply(boolean original, LivingEntity entity, float partialTick) {
        NoRender noRender = Modules.get().get(NoRender.class);
        if (getMobEffect() == MobEffects.BLINDNESS) return original && !noRender.noBlindness();
        if (getMobEffect() == MobEffects.DARKNESS) return original && !noRender.noDarkness();
        return original;
    }
}
