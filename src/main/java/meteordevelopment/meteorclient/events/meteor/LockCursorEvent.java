/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.meteor;

import meteordevelopment.meteorclient.events.Cancellable;

public class LockCursorEvent extends Cancellable {
    private static final LockCursorEvent INSTANCE = new LockCursorEvent();

    public static LockCursorEvent get() {
        INSTANCE.setCancelled(false);

        return INSTANCE;
    }
}
