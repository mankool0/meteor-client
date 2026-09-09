/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.Hitboxes;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// PORT(1.21.4): the standalone EntityHitboxDebugRenderer class doesn't exist yet - in 1.21.4 debug hitboxes are
// rendered by the private static EntityRenderDispatcher.renderHitbox(PoseStack, VertexConsumer, Entity, float, float, float, float)
// method, which calls Entity.getBoundingBox() directly (same call site the old mixin modified).
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityHitboxDebugRendererMixin {
    @ModifyExpressionValue(method = "renderHitbox", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private static AABB meteor$createHitbox(AABB original, PoseStack poseStack, VertexConsumer vertexConsumer, Entity entity, float f, float g, float h, float i) {
        double v = Modules.get().get(Hitboxes.class).getEntityValue(entity);
        if (v == 0) return original;

        return original.inflate(v);
    }
}
