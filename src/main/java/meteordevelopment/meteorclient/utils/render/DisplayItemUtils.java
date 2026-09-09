/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Creates display-only {@link ItemStack}s that can be used for rendering
 * before item components are bound (i.e. before joining a world).
 */
public class DisplayItemUtils {
    private DisplayItemUtils() {}

    // PORT(1.21.4): item components are bound at registration time on 1.21.4, so plain
    // ItemStacks are safe to create before joining a world - no direct holder needed.
    public static ItemStack toStack(Item item) {
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }

    public static ItemStack toStack(Block block) {
        return toStack(block.asItem());
    }

    public static ItemStack toStack(Item item, int count) {
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, count);
    }
}
