/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import net.minecraft.core.Holder;
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

    public static ItemStack toStack(Item item) {
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(directHolder(item));
    }

    public static ItemStack toStack(Block block) {
        return toStack(block.asItem());
    }

    public static ItemStack toStack(Item item, int count) {
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(directHolder(item), count);
    }

    // PORT(1.21.11): item components are always bound on 1.21.11, so the registry holder can be used directly
    @SuppressWarnings("deprecation")
    private static Holder<Item> directHolder(Item item) {
        return item.builtInRegistryHolder();
    }
}
