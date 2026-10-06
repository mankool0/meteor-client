/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.indigo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

// PORT(1.21.11): replaces the face culling part of AltModelBlockRendererImplMixin (Indigo has no AltModelBlockRendererImpl on 1.21.11)
@Mixin(BlockRenderInfo.class)
public abstract class BlockRenderInfoMixin {
    @Shadow(remap = false)
    public BlockState blockState;

    @Shadow(remap = false)
    public BlockAndTintGetter blockView;

    @Shadow(remap = false)
    public BlockPos blockPos;

    @ModifyReturnValue(method = "shouldDrawSide", at = @At("RETURN"), remap = false)
    private boolean shouldDrawSide$xray(boolean original, Direction direction) {
        if (direction == null) return original;

        Xray xray = Modules.get().get(Xray.class);

        if (xray.isActive()) {
            return xray.modifyDrawSide(blockState, blockView, blockPos, direction, original);
        }

        return original;
    }
}
