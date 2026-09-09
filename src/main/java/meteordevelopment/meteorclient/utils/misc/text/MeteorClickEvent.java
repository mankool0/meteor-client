/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.text;

import net.minecraft.network.chat.ClickEvent;

/**
 * This class does nothing except ensure that {@link ClickEvent}'s containing Meteor Client commands can only be executed if they come from the client.
 *
 * @see meteordevelopment.meteorclient.mixin.ScreenMixin
 */
public class MeteorClickEvent extends ClickEvent {
    public final String value;

    public MeteorClickEvent(String value) {
        this(Action.RUN_COMMAND, value);
    }

    public MeteorClickEvent(Action action, String value) {
        super(action, value);
        this.value = value;
    }
}
