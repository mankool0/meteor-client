/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReceiver;
import com.llamalad7.mixinextras.sugar.Local;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.BetterChat;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT(1.21.4): This mixin used to target the 26.1 ChatComponent$DrawingFocusedGraphicsAccess inner
// type (the "focused"/interactable render pass of the newer dual-pass ActiveTextCollector rendering
// architecture). Neither that type nor ActiveTextCollector exist on 1.21.4 - ChatComponent has a single
// render(GuiGraphics, int, int, int, boolean) method that draws every line with one
// GuiGraphics.drawString(Font, FormattedCharSequence, int, int, int) call (verified via javap: there is
// exactly one such call in render()), regardless of focus state. So the "player heads" feature (which
// used to be duplicated across this mixin and ChatHudUnfocusedMixin, one per render pass) is implemented
// once here, matching what the pre-refactor (Yarn) 1.21.4-era ChatHudMixin.onRender_beforeDrawTextWithShadow
// / onRender_modifyIndicator did. ChatHudUnfocusedMixin is now an intentional no-op stub to avoid a
// duplicate/conflicting injection into the same drawString call from two different mixin classes.
@Mixin(ChatComponent.class)
public abstract class ChatHudInteractableMixin {
    @Unique
    private static BetterChat betterChat;

    // Player Heads - draw the head/icon immediately before the line's text is drawn.
    @ModifyReceiver(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private GuiGraphics onRender_beforeDrawText(GuiGraphics instance, Font font, FormattedCharSequence text, int x, int y, int color, @Local GuiMessage.Line line) {
        BetterChat betterChat = getBetterChat();

        betterChat.line = line;
        betterChat.beforeDrawMessage(instance, y, color);

        return instance;
    }

    // Offset the text to make room for the head drawn above.
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"), index = 2)
    private int modifyX(int x) {
        return getBetterChat().modifyChatWidth(x);
    }

    // Clean up after drawing this line.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I", shift = At.Shift.AFTER))
    private void onRender_afterDrawText(GuiGraphics guiGraphics, int mouseX, int mouseY, int tickDelta, boolean focused, CallbackInfo ci) {
        getBetterChat().afterDrawMessage();
    }

    // No Message Signature Indicator

    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/GuiMessage$Line;tag()Lnet/minecraft/client/GuiMessageTag;"))
    private GuiMessageTag onRender_modifyIndicator(GuiMessageTag indicator) {
        return Modules.get().get(NoRender.class).noMessageSignatureIndicator() ? null : indicator;
    }

    @Unique
    private static BetterChat getBetterChat() {
        if (betterChat == null) {
            betterChat = Modules.get().get(BetterChat.class);
        }

        return betterChat;
    }
}
