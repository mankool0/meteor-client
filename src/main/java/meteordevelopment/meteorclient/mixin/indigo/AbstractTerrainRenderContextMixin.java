/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.indigo;

import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractTerrainRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT(1.21.11): replaces AltModelBlockRendererImplMixin (Indigo has no AltModelBlockRendererImpl on 1.21.11), face culling is in BlockRenderInfoMixin
@SuppressWarnings("UnstableApiUsage")
@Mixin(AbstractTerrainRenderContext.class)
public abstract class AbstractTerrainRenderContextMixin {
    @Final
    @Shadow(remap = false)
    protected BlockRenderInfo blockInfo;

    @Unique
    private int alpha = -1;

    @Inject(method = "bufferQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bufferQuad$xray(MutableQuadViewImpl quad, CallbackInfo ci) {
        alpha = Xray.getAlpha(blockInfo.blockState, blockInfo.blockPos);

        if (alpha == 0) {
            ci.cancel();
        } else if (alpha > 0 && alpha < 255) {
            quad.renderLayer(ChunkSectionLayer.TRANSLUCENT);
        }
    }

    @Inject(method = "bufferQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;)V", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AbstractTerrainRenderContext;bufferQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void bufferQuad$xrayAlpha(MutableQuadViewImpl quad, CallbackInfo ci) {
        if (alpha != -1) {
            for (int i = 0; i < 4; i++) {
                quad.color(i, rewriteQuadAlpha(quad.color(i), alpha));
            }
        }
    }

    @Unique
    private static int rewriteQuadAlpha(int color, int alpha) {
        return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
    }
}
