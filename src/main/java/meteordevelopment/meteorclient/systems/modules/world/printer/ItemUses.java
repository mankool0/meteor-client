/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Blocks the schematic wants that only an item used on another block makes: water in a cauldron, a plant in a pot, a
 * candle on a cake, a lit candle, a charged respawn anchor and so on. {@link #base} is what to place first, and
 * {@link #find} the item that moves what's there one step towards the schematic.
 */
public final class ItemUses {
    private static final Predicate<ItemStack> WATER_BOTTLE = stack -> stack.is(Items.POTION)
        && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    private static final Predicate<ItemStack> FIRE = stack -> stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);
    // hoes and shovels do nothing on a bottom face
    private static final Predicate<Direction> NOT_DOWN = face -> face != Direction.DOWN;
    private static final Predicate<Direction> ANY_FACE = face -> true;

    private static Map<Block, Block> candleByCake;

    /**
     * An item to use on a block, and the faces it works on.
     *
     * @param items     what to use, in order of preference
     * @param itemFirst the item does the work rather than the block, so a block that takes clicks itself has to be
     *                  sneaked at; the block's own use goes first otherwise, which sneaking skips
     */
    public record ItemUse(List<Predicate<ItemStack>> items, Predicate<Direction> faces, boolean itemFirst) {
        static ItemUse block(List<Predicate<ItemStack>> items) {
            return new ItemUse(items, ANY_FACE, false);
        }

        static ItemUse item(Predicate<Direction> faces, List<Predicate<ItemStack>> items) {
            return new ItemUse(items, faces, true);
        }
    }

    private ItemUses() {
    }

    /**
     * @param ownItemAvailable whether the player has the block's own item, for blocks that only need a base without it
     * @return what to place for the schematic's block when it can't be placed as it is, or null if it can
     */
    @Nullable
    public static BlockState base(BlockState required, boolean ownItemAvailable) {
        Block block = required.getBlock();

        // the cauldron item places an empty one
        if (block instanceof AbstractCauldronBlock && block != Blocks.CAULDRON) return Blocks.CAULDRON.defaultBlockState();
        if (block instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) return Blocks.FLOWER_POT.defaultBlockState();
        if (block instanceof CandleCakeBlock) return Blocks.CAKE.defaultBlockState();

        if (ownItemAvailable) return null;

        // no item for these in survival
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH) return Blocks.DIRT.defaultBlockState();

        Block unwaxed = HoneycombItem.WAX_OFF_BY_BLOCK.get().get(block);
        if (unwaxed != null) return unwaxed.withPropertiesOf(required);

        return null;
    }

    /**
     * @return the item to use on the existing block to move it towards the schematic's, or null if there is none
     */
    @Nullable
    public static ItemUse find(BlockState required, BlockState existing, BlockPos pos) {
        Block want = required.getBlock();
        Block have = existing.getBlock();

        if (have instanceof AbstractCauldronBlock && want instanceof AbstractCauldronBlock) return cauldron(required, existing);

        // a plant into an empty pot
        if (have == Blocks.FLOWER_POT && want instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            return ItemUse.block(List.of(stack -> stack.is(pot.getPotted().asItem())));
        }

        // a candle onto an untouched cake
        if (have == Blocks.CAKE && want instanceof CandleCakeBlock && existing.getValue(BlockStateProperties.BITES) == 0) {
            Block candle = candleFor(want);
            if (candle != null) return ItemUse.block(List.of(stack -> stack.is(candle.asItem())));
        }

        if (have == want) {
            // flint and steel places fire on anything it can't light, so only where it can
            if (required.hasProperty(BlockStateProperties.LIT) && required.getValue(BlockStateProperties.LIT) && !existing.getValue(BlockStateProperties.LIT)
                && (CandleBlock.canLight(existing) || CandleCakeBlock.canLight(existing) || CampfireBlock.canLight(existing))) {
                return ItemUse.item(ANY_FACE, List.of(FIRE));
            }

            // campfires place lit
            if (have instanceof CampfireBlock && !required.getValue(BlockStateProperties.LIT) && existing.getValue(BlockStateProperties.LIT)) {
                return ItemUse.item(NOT_DOWN, List.of(stack -> stack.is(ItemTags.SHOVELS)));
            }

            if (have == Blocks.END_PORTAL_FRAME && required.getValue(BlockStateProperties.EYE) && !existing.getValue(BlockStateProperties.EYE)) {
                return ItemUse.item(ANY_FACE, List.of(stack -> stack.is(Items.ENDER_EYE)));
            }

            if (have == Blocks.LECTERN && required.getValue(BlockStateProperties.HAS_BOOK) && !existing.getValue(BlockStateProperties.HAS_BOOK)) {
                return ItemUse.block(List.of(stack -> stack.is(ItemTags.LECTERN_BOOKS)));
            }

            // a charged anchor explodes outside the nether when clicked with anything but glowstone, so never past full
            if (have == Blocks.RESPAWN_ANCHOR) {
                int charges = existing.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
                if (charges < required.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) && charges < RespawnAnchorBlock.MAX_CHARGES) {
                    return ItemUse.block(List.of(stack -> stack.is(Items.GLOWSTONE)));
                }
            }

            return null;
        }

        // tilling and flattening need air above
        boolean airAbove = mc.level.getBlockState(pos.above()).isAir();
        if (want == Blocks.FARMLAND && airAbove && (have == Blocks.DIRT || have == Blocks.GRASS_BLOCK || have == Blocks.DIRT_PATH)) {
            return ItemUse.item(NOT_DOWN, List.of(stack -> stack.is(ItemTags.HOES)));
        }
        if (want == Blocks.DIRT_PATH && airAbove && (have == Blocks.DIRT || have == Blocks.GRASS_BLOCK || have == Blocks.PODZOL
            || have == Blocks.COARSE_DIRT || have == Blocks.MYCELIUM || have == Blocks.ROOTED_DIRT)) {
            return ItemUse.item(NOT_DOWN, List.of(stack -> stack.is(ItemTags.SHOVELS)));
        }

        Block waxed = HoneycombItem.WAXABLES.get().get(have);
        if (waxed == want && waxed.withPropertiesOf(existing) == required) {
            return ItemUse.item(ANY_FACE, List.of(stack -> stack.is(Items.HONEYCOMB)));
        }

        return null;
    }

    private static ItemUse cauldron(BlockState required, BlockState existing) {
        Block want = required.getBlock();
        Block have = existing.getBlock();
        int wantLevel = want == Blocks.WATER_CAULDRON ? required.getValue(BlockStateProperties.LEVEL_CAULDRON) : 0;

        if (have == Blocks.WATER_CAULDRON) {
            int level = existing.getValue(BlockStateProperties.LEVEL_CAULDRON);
            if (want == Blocks.WATER_CAULDRON && level < wantLevel) {
                return ItemUse.block(wantLevel == 3 ? List.of(stack -> stack.is(Items.WATER_BUCKET), WATER_BOTTLE) : List.of(WATER_BOTTLE));
            }
            if (want == Blocks.WATER_CAULDRON && level == wantLevel) return null;

            // too much water, or water where something else goes: a bottle takes one level, a bucket a full cauldron
            boolean emptyIt = want != Blocks.WATER_CAULDRON;
            if (level == 3 && emptyIt) return ItemUse.block(List.of(stack -> stack.is(Items.BUCKET), stack -> stack.is(Items.GLASS_BOTTLE)));
            return ItemUse.block(List.of(stack -> stack.is(Items.GLASS_BOTTLE)));
        }

        if (have == Blocks.LAVA_CAULDRON || have == Blocks.POWDER_SNOW_CAULDRON) {
            if (want == have) return null;
            // a bucket only takes a full powder snow cauldron, and nothing else lowers one
            if (have == Blocks.POWDER_SNOW_CAULDRON && existing.getValue(BlockStateProperties.LEVEL_CAULDRON) != 3) return null;
            return ItemUse.block(List.of(stack -> stack.is(Items.BUCKET)));
        }

        // an empty cauldron
        if (want == Blocks.WATER_CAULDRON) {
            return ItemUse.block(wantLevel == 3 ? List.of(stack -> stack.is(Items.WATER_BUCKET), WATER_BOTTLE) : List.of(WATER_BOTTLE));
        }
        if (want == Blocks.LAVA_CAULDRON) return ItemUse.block(List.of(stack -> stack.is(Items.LAVA_BUCKET)));
        if (want == Blocks.POWDER_SNOW_CAULDRON && required.getValue(BlockStateProperties.LEVEL_CAULDRON) == 3) {
            return ItemUse.block(List.of(stack -> stack.is(Items.POWDER_SNOW_BUCKET)));
        }
        return null;
    }

    @Nullable
    private static Block candleFor(Block candleCake) {
        if (candleByCake == null) {
            candleByCake = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof CandleBlock candle) candleByCake.put(CandleCakeBlock.byCandle(candle).getBlock(), candle);
            }
        }
        return candleByCake.get(candleCake);
    }

    /**
     * @return whether clicking this block with the wrong item blows it up, which a respawn anchor does outside the nether
     */
    public static boolean isRisky(BlockState existing) {
        return existing.is(Blocks.RESPAWN_ANCHOR) && mc.level.dimension() != Level.NETHER;
    }
}
