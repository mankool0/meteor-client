/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class KeyBinds {
    // PORT(1.21.4): KeyMapping.Category does not exist on 1.21.4 - categories are plain strings and custom ones
    // must be registered in KeyMapping.CATEGORY_SORT_ORDER (private static, needs an accessor in mixin scope).
    // Using the vanilla misc category to avoid an NPE in the controls screen sort until such an accessor exists.
    private static final String CATEGORY = "key.categories.misc";

    public static KeyMapping OPEN_GUI = new KeyMapping("key.meteor-client.open-gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY);
    public static KeyMapping OPEN_COMMANDS = new KeyMapping("key.meteor-client.open-commands", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, CATEGORY);

    private KeyBinds() {
    }

    public static KeyMapping[] apply(KeyMapping[] binds) {
        // Add key binding
        KeyMapping[] newBinds = new KeyMapping[binds.length + 2];

        System.arraycopy(binds, 0, newBinds, 0, binds.length);
        newBinds[binds.length] = OPEN_GUI;
        newBinds[binds.length + 1] = OPEN_COMMANDS;

        return newBinds;
    }
}
