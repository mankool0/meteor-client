/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// PORT(1.21.11): on 1.21.11 the chunk layer is picked per block / fluid state instead of per quad, so the Xray, WallHack
// and Ambience translucent layer overrides from ModelBlockRendererMixin and FluidRendererMixin are done here
@Mixin(ItemBlockRenderTypes.class)
public abstract class ItemBlockRenderTypesMixin {
    @Inject(method = "getChunkRenderType", at = @At("HEAD"), cancellable = true)
    private static void onGetChunkRenderType(BlockState state, CallbackInfoReturnable<ChunkSectionLayer> cir) {
        if (Modules.get() == null) return;

        int alpha = Xray.getAlpha(state, null);
        if (alpha > 0 && alpha < 255) cir.setReturnValue(ChunkSectionLayer.TRANSLUCENT);
    }

    @Inject(method = "getRenderLayer", at = @At("HEAD"), cancellable = true)
    private static void onGetFluidRenderLayer(FluidState state, CallbackInfoReturnable<ChunkSectionLayer> cir) {
        if (Modules.get() == null) return;

        int alpha = Xray.getFluidAlpha(state, null);
        if (alpha > 0 && alpha < 255) {
            cir.setReturnValue(ChunkSectionLayer.TRANSLUCENT);
            return;
        }

        Ambience ambience = Modules.get().get(Ambience.class);
        int a = ambience.lavaColor.get().a;
        if (ambience.isActive() && ambience.customLavaColor.get() && state.is(FluidTags.LAVA) && a > 0 && a < 255) {
            cir.setReturnValue(ChunkSectionLayer.TRANSLUCENT);
        }
    }
}
