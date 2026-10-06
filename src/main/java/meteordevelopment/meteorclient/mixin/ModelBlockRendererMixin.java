/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @Unique
    private static final ThreadLocal<Integer> ALPHAS = ThreadLocal.withInitial(() -> -1);

    @Inject(method = {"tesselateWithAO", "tesselateWithoutAO"}, at = @At("HEAD"), cancellable = true)
    private void tesselate$xray(BlockAndTintGetter level, List<BlockModelPart> parts, BlockState state, BlockPos pos, PoseStack poseStack, VertexConsumer buffer, boolean cull, int overlayCoords, CallbackInfo ci) {
        int alpha = Xray.getAlpha(state, pos);

        if (alpha == 0) ci.cancel();
        else ALPHAS.set(alpha);
    }

    // PORT(1.21.11): there is no per-quad chunk layer on 1.21.11, translucent Xray blocks are moved to the
    // translucent layer in ItemBlockRenderTypesMixin instead
    @ModifyArg(
        method = "putQuadData",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[II)V"),
        index = 6
    )
    private float putQuadData$xray(float alpha) {
        int xrayAlpha = ALPHAS.get();

        return xrayAlpha == -1 ? alpha : xrayAlpha / 255f;
    }

    @ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
    private static boolean shouldRenderFace$xray(boolean original, BlockAndTintGetter level, BlockState state, boolean cull, Direction direction, BlockPos neighborPos) {
        Xray xray = Modules.get().get(Xray.class);

        if (xray.isActive()) {
            return xray.modifyDrawSide(state, level, neighborPos.relative(direction.getOpposite()), direction, original);
        }

        return original;
    }
}
