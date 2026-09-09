/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// PORT(1.21.4): master has no counterpart - on 1.21.6+ the chunk render type no longer decides whether
// vertex alpha survives. Here it does: a block batched into the solid or cutout layer is drawn with
// blending off, so the alpha Xray and Ambience write is discarded and the block comes out opaque.
// Anything they make partially transparent has to be moved to the translucent layer first.
@Mixin(ItemBlockRenderTypes.class)
public abstract class ItemBlockRenderTypesMixin {
    @Inject(method = "getChunkRenderType", at = @At("HEAD"), cancellable = true)
    private static void onGetChunkRenderType(BlockState state, CallbackInfoReturnable<RenderType> cir) {
        if (Modules.get() == null) return;

        int alpha = Xray.getAlpha(state, null);
        if (alpha > 0 && alpha < 255) cir.setReturnValue(RenderType.translucent());
    }

    @Inject(method = "getRenderLayer", at = @At("HEAD"), cancellable = true)
    private static void onGetRenderLayer(FluidState state, CallbackInfoReturnable<RenderType> cir) {
        if (Modules.get() == null) return;

        int alpha = Xray.getAlpha(state.createLegacyBlock(), null);
        if (alpha > 0 && alpha < 255) {
            cir.setReturnValue(RenderType.translucent());
            return;
        }

        Ambience ambience = Modules.get().get(Ambience.class);
        int a = ambience.lavaColor.get().a;

        if (ambience.isActive() && ambience.customLavaColor.get() && a > 0 && a < 255) {
            cir.setReturnValue(RenderType.translucent());
        }
    }
}
