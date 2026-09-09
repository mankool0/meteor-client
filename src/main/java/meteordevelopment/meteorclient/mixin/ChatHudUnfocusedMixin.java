/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;

// PORT(1.21.4): This mixin used to target the 26.1 ChatComponent$DrawingBackgroundGraphicsAccess inner
// type (the "unfocused"/background render pass of the newer dual-pass ActiveTextCollector rendering
// architecture). Neither that type nor ActiveTextCollector exist on 1.21.4 - ChatComponent has a single
// render(GuiGraphics, int, int, int, boolean) method, used for both the focused and unfocused states,
// with exactly one place where line text is drawn. That single injection point can only safely be owned
// by one mixin (two @ModifyReceiver/@ModifyArg handlers from different mixin classes on the exact same
// instruction risk colliding), so the "player heads" logic that used to live in this class (and its
// sibling ChatHudInteractableMixin, which handled the "focused" pass) has been merged into
// ChatHudInteractableMixin, which now handles player heads for all chat rendering regardless of focus.
// This class is kept as an intentional no-op so the mixin config (meteor-client.mixins.json) does not
// need to be touched.
@Mixin(ChatComponent.class)
public abstract class ChatHudUnfocusedMixin {
}
