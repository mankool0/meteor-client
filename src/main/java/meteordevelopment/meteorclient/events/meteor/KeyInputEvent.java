/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.meteor;

import meteordevelopment.meteorclient.events.Cancellable;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;

// PORT(1.21.4): net.minecraft.client.input.KeyEvent does not exist on 1.21.4 - the event carries plain ints.
public class KeyInputEvent extends Cancellable {
    private static final KeyInputEvent INSTANCE = new KeyInputEvent();

    public int key;
    public int scancode;
    public int modifiers;
    public KeyAction action;

    public static KeyInputEvent get(int key, int scancode, int modifiers, KeyAction action) {
        INSTANCE.setCancelled(false);
        INSTANCE.key = key;
        INSTANCE.scancode = scancode;
        INSTANCE.modifiers = modifiers;
        INSTANCE.action = action;
        return INSTANCE;
    }

    public int key() {
        return key;
    }

    public int scancode() {
        return scancode;
    }

    public int modifiers() {
        return modifiers;
    }
}
