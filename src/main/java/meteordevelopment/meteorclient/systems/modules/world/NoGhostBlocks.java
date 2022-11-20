/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.world.SetBlockStateEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

public class NoGhostBlocks extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> breaking = sgGeneral.add(new BoolSetting.Builder()
        .name("Breaking")
        .description("No ghost blocks when breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> placing = sgGeneral.add(new BoolSetting.Builder()
        .name("Placing")
        .description("No ghost blocks when placing")
        .defaultValue(true)
        .build()
    );

    public NoGhostBlocks() {
        super(Categories.World, "no-ghost-blocks", "Attempts to prevent ghost blocks arising from breaking/placing blocks quickly. Especially useful with multiconnect.");
    }

    @EventHandler
    public void onBreakBlock(BreakBlockEvent event) {
        if (mc.isInSingleplayer() || !breaking.get())
            return;

        event.setCancelled(true);

        // play the related sounds and particles for the user.
        BlockState blockState = mc.world.getBlockState(event.blockPos);
        blockState.getBlock().onBreak(mc.world, event.blockPos, blockState, mc.player); // this doesn't alter the state of the block in the world
    }

    @EventHandler
    public void onSetBlockState(SetBlockStateEvent event)
    {
        if (mc.isInSingleplayer() || !placing.get())
            return;

        if (event.flags == 11 && event.state.getBlock() != Blocks.AIR) // There might be a better way to do this
            event.setCancelled(true);
    }
}
