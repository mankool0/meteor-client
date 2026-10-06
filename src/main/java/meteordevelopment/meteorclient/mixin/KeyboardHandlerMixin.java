/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.CharTypedEvent;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.gui.GuiKeyEvents;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    public void onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (key != InputConstants.UNKNOWN.getValue()) {
            // on Linux/X11 the modifier is not active when the key is pressed and still active when the key is released
            // https://github.com/glfw/glfw/issues/1630
            if (action == InputConstants.PRESS) {
                modifiers |= Input.getModifier(key);
            } else if (action == InputConstants.RELEASE) {
                modifiers &= ~Input.getModifier(key);
            }

            if (minecraft.screen instanceof WidgetScreen widgetScreen && action == InputConstants.REPEAT) {
                widgetScreen.keyRepeated(key, modifiers);
            }

            if (GuiKeyEvents.canUseKeys) {
                Input.setKeyState(key, action != InputConstants.RELEASE);
                if (MeteorClient.EVENT_BUS.post(KeyInputEvent.get(key, scancode, modifiers, KeyAction.get(action))).isCancelled())
                    ci.cancel();
            }
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onChar(long window, int codepoint, int modifiers, CallbackInfo ci) {
        if (Utils.canUpdate() && !minecraft.isPaused() && (minecraft.screen == null || minecraft.screen instanceof WidgetScreen)) {
            if (MeteorClient.EVENT_BUS.post(CharTypedEvent.get((char) codepoint)).isCancelled()) ci.cancel();
        }
    }
}
