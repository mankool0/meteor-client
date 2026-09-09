/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.meteor;

import meteordevelopment.meteorclient.events.Cancellable;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;

// PORT(1.21.4): net.minecraft.client.input.MouseButtonEvent/MouseButtonInfo do not exist on 1.21.4 - the event carries plain ints.
public class MouseClickEvent extends Cancellable {
    private static final MouseClickEvent INSTANCE = new MouseClickEvent();

    public int button;
    public int modifiers;
    public KeyAction action;

    public static MouseClickEvent get(int button, int modifiers, KeyAction action) {
        INSTANCE.setCancelled(false);
        INSTANCE.button = button;
        INSTANCE.modifiers = modifiers;
        INSTANCE.action = action;
        return INSTANCE;
    }

    public int button() {
        return button;
    }

    public int modifiers() {
        return modifiers;
    }
}
