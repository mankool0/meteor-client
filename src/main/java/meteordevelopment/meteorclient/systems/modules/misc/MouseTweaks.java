/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.meteor.LockCursorEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;


public class MouseTweaks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> unlock = sgGeneral.add(new BoolSetting.Builder()
        .name("Unlock")
        .description("Always keeps the mouse cursor unlocked.")
        .defaultValue(true)
        .build()
    );

    public MouseTweaks() {
        super(Categories.Misc, "mouse-tweaks", "Allows modification of the mouse.");
    }

    @Override
    public void toggle() {
        if (this.isActive() && unlock.get()) {
            mc.mouse.lockCursor();
        } else if (!this.isActive() && unlock.get()) {
            mc.mouse.unlockCursor();
        }
        super.toggle();
    }

    @EventHandler
    private void onLockCursor(LockCursorEvent event) {
        if (unlock.get())
            event.cancel();
    }
}
