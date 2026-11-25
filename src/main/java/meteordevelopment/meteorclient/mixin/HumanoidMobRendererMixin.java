/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidMobRendererMixin {
    // ElytraFly standing box: the model's flying pose (body tilt, arms and legs) follows this flag.
    @ModifyExpressionValue(method = "extractHumanoidRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z"))
    private static boolean extractHumanoidRenderState$standing(boolean original, LivingEntity entity, HumanoidRenderState state, float partialTicks, ItemModelResolver itemModelResolver) {
        return original && !Modules.get().get(ElytraFly.class).drawsStanding(entity);
    }
}
