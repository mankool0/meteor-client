/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.world;

import meteordevelopment.meteorclient.events.Cancellable;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

public class SetBlockStateEvent extends Cancellable {
    private static final SetBlockStateEvent INSTANCE = new SetBlockStateEvent();

    public BlockPos pos;
    public BlockState state;
    public int flags;

    public static SetBlockStateEvent get(BlockPos pos, BlockState state, int flags)
    {
        INSTANCE.pos = pos;
        INSTANCE.state = state;
        INSTANCE.flags = flags;
        return INSTANCE;
    }
}
