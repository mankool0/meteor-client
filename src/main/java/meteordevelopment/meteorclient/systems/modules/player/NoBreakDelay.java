/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

public class NoBreakDelay extends Module {
    public NoBreakDelay() {
        super(Categories.Player, "no-break-delay", "Completely removes the delay between breaking blocks.");
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).setBlockBreakingCooldown(0);
    }
}
