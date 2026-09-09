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
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT(1.21.4): The 26.1.2 FluidRenderer/FluidRenderer.Output/ChunkSectionLayer split does not exist here.
// On 1.21.4 this class is LiquidBlockRenderer and tesselate() writes straight into a single VertexConsumer
// (no per-call chunk render layer selection), so forcing the fluid onto the translucent layer for
// partial-alpha xray/wallhack can't be done from within this mixin (there's no equivalent of
// FluidRenderer.Output.getBuilder(ChunkSectionLayer) to redirect). Vanilla layer choice for a fluid's
// BlockState is made before tesselate() is even called (via ItemBlockRenderTypes/RenderLayers on 26.1.2's
// ancestor), which is out of scope for this file.
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

    @Unique
    private Xray getXray() {
        if (xray == null) xray = Modules.get().get(Xray.class);
        return xray;
    }

    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void onTesselate(BlockAndTintGetter level, BlockPos pos, VertexConsumer vertexConsumer, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
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
        FORCE_XRAY_FLUID_SIDES.set(getXray().isActive());
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
        return !getXray().isBlocked(neighborState.getBlock(), null);
    }

    @Inject(method = "vertex", at = @At("HEAD"), cancellable = true)
    private void onVertex(VertexConsumer vertexConsumer, float x, float y, float z, float red, float green, float blue, float u, float v, int light, CallbackInfo ci) {
        int alpha = ALPHAS.get();

        if (AMBIENT.get()) {
            Color c = Modules.get().get(Ambience.class).lavaColor.get();
            vertex(vertexConsumer, x, y, z, c.r, c.g, c.b, (alpha != -1 ? alpha : c.a), u, v, light);
            ci.cancel();
        } else if (alpha != -1) {
            vertex(vertexConsumer, x, y, z, (int) (red * 255), (int) (green * 255), (int) (blue * 255), alpha, u, v, light);
            ci.cancel();
        }
    }

    @Unique
    private void vertex(VertexConsumer vertexConsumer, float x, float y, float z, int red, int green, int blue, int alpha, float u, float v, int light) {
        vertexConsumer.addVertex(x, y, z).setColor(red, green, blue, alpha).setUv(u, v).setLight(light).setNormal(0.0f, 1.0f, 0.0f);
    }
}
