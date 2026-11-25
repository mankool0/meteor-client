/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;


import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import meteordevelopment.meteorclient.systems.modules.world.InfinityMiner;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.ServerDigSlots;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

public class AutoTool extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    // General

    private final Setting<EnchantPreference> prefer = sgGeneral.add(new EnumSetting.Builder<EnchantPreference>()
        .name("prefer")
        .description("Either to prefer Silk Touch, Fortune, or none.")
        .defaultValue(EnchantPreference.Fortune)
        .build()
    );

    private final Setting<Boolean> silkTouchForEnderChest = sgGeneral.add(new BoolSetting.Builder()
        .name("silk-touch-for-ender-chest")
        .description("Mines Ender Chests only with the Silk Touch enchantment.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fortuneForOresCrops = sgGeneral.add(new BoolSetting.Builder()
        .name("fortune-for-ores-and-crops")
        .description("Mines Ores and crops only with the Fortune enchantment.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> saveDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("save-durability")
        .description("Breaks blocks no tool is faster on, like tinted glass, by hand, as tools wear down on any block. Keeps a preferred Silk Touch or Fortune tool where it changes the drops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Stops you from breaking your tool.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> breakDurability = sgGeneral.add(new IntSetting.Builder()
        .name("anti-break-percentage")
        .description("The durability percentage to stop using a tool.")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(antiBreak::get)
        .build()
    );

    private final Setting<Boolean> switchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("switch-back")
        .description("Switches your hand to whatever was selected when releasing your attack key.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> switchDelay = sgGeneral.add((new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay in ticks before switching tools.")
        .defaultValue(0)
        .build()
    ));

    // Whitelist and blacklist

    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("Selection mode.")
        .defaultValue(ListMode.Blacklist)
        .build()
    );

    private final Setting<List<Item>> whitelist = sgWhitelist.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("The tools you want to use.")
        .visible(() -> listMode.get() == ListMode.Whitelist)
        .filter(AutoTool::isTool)
        .build()
    );

    private final Setting<List<Item>> blacklist = sgWhitelist.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("The tools you don't want to use.")
        .visible(() -> listMode.get() == ListMode.Blacklist)
        .filter(AutoTool::isTool)
        .build()
    );

    private boolean wasPressed;
    private boolean shouldSwitch;
    private int ticks;
    private int bestSlot;

    public AutoTool() {
        super(Categories.Player, "auto-tool", "Automatically switches to the most effective tool when performing an action.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (Modules.get().isActive(InfinityMiner.class)) return;

        if (switchBack.get() && !mc.options.keyAttack.isDown() && wasPressed && InvUtils.previousSlot != -1) {
            InvUtils.swapBack();
            wasPressed = false;
            return;
        }

        if (ticks <= 0 && shouldSwitch && bestSlot != -1) {
            InvUtils.swap(bestSlot, switchBack.get());
            shouldSwitch = false;
        } else {
            ticks--;
        }

        wasPressed = mc.options.keyAttack.isDown();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (Modules.get().isActive(InfinityMiner.class)) return;
        if (mc.player.isCreative()) return;

        // Get blockState
        BlockState blockState = mc.level.getBlockState(event.blockPos);
        if (!BlockUtils.canBreak(event.blockPos, blockState)) return;

        // Check if we should switch to a better tool. A block parked in the server's delayed slot finishes with
        // whatever is held when its time comes, so its tool stays until it has
        bestSlot = -1;
        ServerDigSlots.Dig parked = ServerDigSlots.parked();
        int slot = pickSlot(parked != null ? parked.state : blockState);
        if (slot != mc.player.getInventory().getSelectedSlot()) {
            bestSlot = slot;
            ticks = switchDelay.get();

            if (ticks == 0) InvUtils.swap(bestSlot, true);
            else shouldSwitch = true;
        }

        // Anti break
        if (refuses(mc.player.getMainHandItem())) {
            mc.options.keyAttack.setDown(false);
            event.cancel();
        }
    }

    /**
     * @return the hotbar slot to break the block with by these settings: the best tool, or the selected slot when no
     * other one beats it
     */
    public int pickSlot(BlockState blockState) {
        ItemStack currentStack = mc.player.getMainHandItem();

        double bestScore = -1;
        int best = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = mc.player.getInventory().getItem(i);

            if (listMode.get() == ListMode.Whitelist && !whitelist.get().contains(itemStack.getItem())) continue;
            if (listMode.get() == ListMode.Blacklist && blacklist.get().contains(itemStack.getItem())) continue;

            double score = getScore(itemStack, blockState, silkTouchForEnderChest.get(), fortuneForOresCrops.get(), prefer.get(), itemStack2 -> !shouldStopUsing(itemStack2));
            if (score < 0) continue;

            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }

        if (best != -1 && (bestScore > getScore(currentStack, blockState, silkTouchForEnderChest.get(), fortuneForOresCrops.get(), prefer.get(), itemStack -> !shouldStopUsing(itemStack)) || shouldStopUsing(currentStack) || !isTool(currentStack))) {
            return best;
        }

        // No tool is faster on it or gets more from it than a hand does, but the held one would wear down
        if (best == -1 && saveDurability.get() && wears(currentStack) && blockState.getDestroySpeed(mc.level, BlockPos.ZERO) != 0
            && currentStack.getDestroySpeed(blockState) <= 1 && !enchantmentMatters(currentStack, blockState)) {
            int hand = handSlot();
            if (hand != -1) return hand;
        }

        return mc.player.getInventory().getSelectedSlot();
    }

    /**
     * @return whether mining with the stack takes durability, which it does for any block that isn't instant
     */
    private static boolean wears(ItemStack itemStack) {
        return itemStack.has(DataComponents.TOOL) && itemStack.isDamageableItem();
    }

    /**
     * @return whether the stack's preferred enchantment changes what the block drops, whatever tool it's on
     */
    private boolean enchantmentMatters(ItemStack itemStack, BlockState state) {
        if (prefer.get() == EnchantPreference.SilkTouch) return Utils.hasEnchantments(itemStack, Enchantments.SILK_TOUCH);
        if (prefer.get() != EnchantPreference.Fortune || !Utils.hasEnchantments(itemStack, Enchantments.FORTUNE)) return false;

        Block block = state.getBlock();
        return isFortunable(block) || block == Blocks.GLOWSTONE || block == Blocks.SEA_LANTERN || block == Blocks.MELON || block == Blocks.GRAVEL;
    }

    /**
     * @return an empty hotbar slot, else one holding something that doesn't wear down, or -1
     */
    private int handSlot() {
        int item = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (itemStack.isEmpty()) return i;
            if (item == -1 && !wears(itemStack)) item = i;
        }
        return item;
    }

    /**
     * @return whether anti-break keeps this stack from breaking blocks
     */
    public boolean refuses(ItemStack itemStack) {
        return shouldStopUsing(itemStack) && isTool(itemStack);
    }

    public boolean switchesBack() {
        return switchBack.get();
    }

    private boolean shouldStopUsing(ItemStack itemStack) {
        return antiBreak.get() && (itemStack.getMaxDamage() - itemStack.getDamageValue()) < (itemStack.getMaxDamage() * breakDurability.get() / 100);
    }

    public static double getScore(ItemStack itemStack, BlockState state, boolean silkTouchEnderChest, boolean fortuneOre, EnchantPreference enchantPreference, Predicate<ItemStack> good) {
        if (!good.test(itemStack) || !isTool(itemStack)) return -1;
        if (!itemStack.isCorrectToolForDrops(state) &&
            !(itemStack.is(ItemTags.SWORDS) && (state.getBlock() instanceof BambooStalkBlock || state.getBlock() instanceof BambooSaplingBlock)) &&
            !(itemStack.getItem() instanceof ShearsItem && state.getBlock() instanceof LeavesBlock || state.is(BlockTags.WOOL)))
            return -1;

        if (silkTouchEnderChest
            && state.getBlock() == Blocks.ENDER_CHEST
            && !Utils.hasEnchantments(itemStack, Enchantments.SILK_TOUCH)) {
            return -1;
        }

        if (fortuneOre
            && isFortunable(state.getBlock())
            && !Utils.hasEnchantments(itemStack, Enchantments.FORTUNE)) {
            return -1;
        }

        double score = 0;

        score += itemStack.getDestroySpeed(state) * 1000;
        score += Utils.getEnchantmentLevel(itemStack, Enchantments.UNBREAKING);
        score += Utils.getEnchantmentLevel(itemStack, Enchantments.EFFICIENCY);
        score += Utils.getEnchantmentLevel(itemStack, Enchantments.MENDING);

        if (enchantPreference == EnchantPreference.Fortune)
            score += Utils.getEnchantmentLevel(itemStack, Enchantments.FORTUNE);
        if (enchantPreference == EnchantPreference.SilkTouch)
            score += Utils.getEnchantmentLevel(itemStack, Enchantments.SILK_TOUCH);

        if (itemStack.is(ItemTags.SWORDS) && (state.getBlock() instanceof BambooStalkBlock || state.getBlock() instanceof BambooSaplingBlock))
            score += 9000 + (itemStack.get(DataComponents.TOOL).getMiningSpeed(state) * 1000);

        return score;
    }

    public static boolean isTool(Item item) {
        return isTool(item.getDefaultInstance());
    }

    public static boolean isTool(ItemStack itemStack) {
        return itemStack.is(ItemTags.AXES) || itemStack.is(ItemTags.HOES) || itemStack.is(ItemTags.PICKAXES) || itemStack.is(ItemTags.SHOVELS) || itemStack.getItem() instanceof ShearsItem;
    }

    private static boolean isFortunable(Block block) {
        if (block == Blocks.ANCIENT_DEBRIS) return false;
        return Xray.ORES.contains(block) || block instanceof CropBlock;
    }

    public enum EnchantPreference {
        None,
        Fortune,
        SilkTouch
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }
}
