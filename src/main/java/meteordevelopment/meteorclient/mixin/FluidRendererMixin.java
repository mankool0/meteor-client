/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LiquidBlockRenderer.class)
public abstract class FluidRendererMixin {
    @Unique
    private static final ThreadLocal<Integer> ALPHAS = ThreadLocal.withInitial(() -> -1);
    @Unique
    private static final ThreadLocal<Boolean> AMBIENT = ThreadLocal.withInitial(() -> false);
    @Unique
    private static final ThreadLocal<Boolean> FORCE_XRAY_FLUID_SIDES = ThreadLocal.withInitial(() -> false);
    @Unique
    private Xray xray;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        xray = Modules.get().get(Xray.class);
    }

    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void onTesselate(BlockAndTintGetter level, BlockPos pos, VertexConsumer buffer, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        Ambience ambience = Modules.get().get(Ambience.class);
        AMBIENT.set(ambience.isActive() && ambience.customLavaColor.get() && fluidState.is(FluidTags.LAVA));

        // Xray and Wallhack
        int alpha = Xray.getFluidAlpha(fluidState, pos);

        if (alpha == 0) {
            FORCE_XRAY_FLUID_SIDES.set(false);
            ci.cancel();
            return;
        }

        ALPHAS.set(alpha);
        FORCE_XRAY_FLUID_SIDES.set(xray.isActive());
    }

    @WrapOperation(
        method = "tesselate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/LiquidBlockRenderer;isFaceOccludedByNeighbor(Lnet/minecraft/core/Direction;FLnet/minecraft/world/level/block/state/BlockState;)Z")
    )
    private boolean onIsFaceOccludedByNeighbor(Direction direction, float height, BlockState neighborState, Operation<Boolean> original) {
        boolean occluded = original.call(direction, height, neighborState);

        if (!occluded) return false;
        if (direction.getAxis().isVertical()) return true;
        if (!FORCE_XRAY_FLUID_SIDES.get()) return true;
        return !xray.isBlocked(neighborState.getBlock(), null);
    }

    @Inject(method = "vertex", at = @At("HEAD"), cancellable = true)
    private void onVertex(VertexConsumer builder, float x, float y, float z, float red, float green, float blue, float u, float v, int lightCoords, CallbackInfo ci) {
        int alpha = ALPHAS.get();

        if (AMBIENT.get()) {
            Color c = Modules.get().get(Ambience.class).lavaColor.get();
            vertex(builder, x, y, z, c.r, c.g, c.b, (alpha != -1 ? alpha : c.a), u, v, lightCoords);
            ci.cancel();
        } else if (alpha != -1) {
            vertex(builder, x, y, z, (int) (red * 255), (int) (green * 255), (int) (blue * 255), alpha, u, v, lightCoords);
            ci.cancel();
        }
    }

    // PORT(1.21.11): the fluid chunk layer is chosen by ItemBlockRenderTypes.getRenderLayer on 1.21.11, see ItemBlockRenderTypesMixin

    @Unique
    private void vertex(VertexConsumer vertexConsumer, float x, float y, float z, int red, int green, int blue, int alpha, float u, float v, int light) {
        vertexConsumer.addVertex(x, y, z).setColor(red, green, blue, alpha).setUv(u, v).setLight(light).setNormal(0.0f, 1.0f, 0.0f);
    }
}
