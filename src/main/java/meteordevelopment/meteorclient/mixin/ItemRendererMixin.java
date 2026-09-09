/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @ModifyVariable(
        method = "renderItem",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private static ItemStackRenderState.FoilType modifyEnchant(ItemStackRenderState.FoilType foilType) {
        if (Modules.get().get(NoRender.class).noEnchantGlint()) {
            return ItemStackRenderState.FoilType.NONE;
        }

        return foilType;
    }
}
