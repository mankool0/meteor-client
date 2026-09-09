/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.utils;

// PORT(1.21.4): net.minecraft.client.input.KeyEvent does not exist on 1.21.4, so the gui widget tree uses this
// meteor-internal carrier instead. Screens receive plain ints from the 1.21.4 Screen overrides and wrap them.
public record KeyEvent(int key, int scancode, int modifiers) {
}
