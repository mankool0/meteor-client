/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// PORT(1.21.4): Font.getGlyph does not exist on 1.21.4 - the obfuscated-style check lives in the
// package-private Font.StringRenderOutput.accept(int, Style, int) instead.
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontMixin {
    @ModifyExpressionValue(method = "accept", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Style;isObfuscated()Z"))
    private boolean onRenderObfuscatedStyle(boolean original) {
        if (Modules.get() == null || Modules.get().get(NoRender.class) == null) {
            return original;
        }
        return !Modules.get().get(NoRender.class).noObfuscation() && original;
    }
}
