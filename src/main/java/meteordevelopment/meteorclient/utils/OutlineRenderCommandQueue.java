/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils;

import meteordevelopment.meteorclient.utils.render.color.Color;

// PORT(1.21.4): The 26.1 SubmitNodeStorage/SubmitNodeCollection submit architecture does not exist on 1.21.4.
// Entity/block outline tint on 1.21.4 is done through LevelRenderer's OutlineBufferSource
// (outlineBufferSource.setColor(r, g, b, a)) — see the WorldRendererMixin pattern from the 1.21.4 tree.
// This class is kept only as a color holder so call sites keep compiling; the render queueing itself
// must be handled in LevelRendererMixin.
public class OutlineRenderCommandQueue {
    private int color;

    public void setColor(Color color) {
        this.color = color.getPacked();
    }

    public int getColor() {
        return color;
    }

    public void endFrame() {
    }
}
