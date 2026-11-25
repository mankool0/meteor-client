/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.world;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.MultiPlayerGameModeAccessor;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Per-tick dig progress the way the server judges a dig packet sent now. Ported from mankool0/baritone.
 * <p>
 * Efficiency is the mining efficiency attribute, which the server only updates from the held item when it ticks the
 * player, after handling the packets that arrived since its last tick. So a dig packet is judged with the speed of the
 * tool selected for it but the efficiency of what was held at the end of our last tick. The client's copy of the
 * attribute is the server's from a round trip ago, which right after switching between a block and the pick is wrong
 * either way: too high and an instant break is refused, too low and instant breaks are missed and slow digs are timed as
 * if the pick had no Efficiency.
 * <p>
 * An anticheat keeps its own copy of the world and replays digs in it with the client's copy of the attribute, so it
 * only clears a block a lone start broke when the client's judgment has it instant too. The other way round, it clears
 * the block on the start while the server digs on, so the stop that would finish it there is a dig on air, which Grim
 * flags and cancels; such a block waits until the two agree again.
 */
public final class ServerDigSpeed {
    /**
     * Enchantments name their attribute modifiers after the slot they apply from.
     */
    private static final String MAIN_HAND_SUFFIX = "/" + EquipmentSlot.MAINHAND.getSerializedName();

    /**
     * Near-instant: a dig finished with a stop in the tick it starts, which every copy of the world clears.
     */
    private static final float JUST_UNDER_INSTANT = Math.nextDown(1.0f);

    /**
     * The two efficiencies agree again within two ticks of holding a block at a tick's end, so disagreeing for longer
     * means the server's is worked out wrong, and waiting on it would hold digs for good.
     */
    private static final int MOST_TICKS_DISAGREEING = 40;

    // what the server held at the end of our last two ticks gives this much efficiency, NaN until known
    private static double efficiencyLastTick = Double.NaN;
    private static double efficiencyTickBefore = Double.NaN;
    // ticks in a row the server's has differed from the client's copy of the attribute
    private static int ticksDisagreeing;

    /**
     * Set while working out the client's own progress, so that client-side speed changes, like Speed Mine's modifier,
     * stay out of it: neither the server nor an anticheat knows of them.
     */
    public static boolean judging;

    private ServerDigSpeed() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(ServerDigSpeed.class);
    }

    @EventHandler(priority = EventPriority.LOWEST - 200)
    private static void onTickPost(TickEvent.Post event) {
        if (mc.player == null || mc.gameMode == null) {
            reset();
            return;
        }

        // the slot last sent, not the one selected: a later switch goes out with the next tick's packets
        int slot = ((MultiPlayerGameModeAccessor) mc.gameMode).meteor$getCarriedIndex();
        efficiencyTickBefore = efficiencyLastTick;
        efficiencyLastTick = efficiencyWith(mc.player.getInventory().getItem(slot));
        double server = Math.min(efficiencyLastTick, efficiencyTickBefore);
        boolean agree = Double.isNaN(server) || server == mc.player.getAttributeValue(Attributes.MINING_EFFICIENCY);
        ticksDisagreeing = agree ? 0 : ticksDisagreeing + 1;
    }

    @EventHandler
    private static void onGameLeft(GameLeftEvent event) {
        reset();
    }

    private static void reset() {
        efficiencyLastTick = Double.NaN;
        efficiencyTickBefore = Double.NaN;
        ticksDisagreeing = 0;
    }

    /**
     * @return per-tick progress on the block with the item selected now, as the server judges a dig packet sent this
     * tick, but instant only when the client's own judgment agrees: a block only the server has instant comes out just
     * under, so it's finished with a stop, and one only the client has instant comes out 0, see {@link #mustWait}
     */
    public static float progress(BlockState state, BlockPos pos) {
        float client = clientProgress(state, pos);
        float server = serverProgress(state, client);
        if (mustWait(client, server)) return 0;
        return server >= 1.0f && client < 1.0f ? JUST_UNDER_INSTANT : server;
    }

    /**
     * @return whether a dig of the block with the item selected now has to wait for the server's efficiency to catch up
     * with the client's copy, because only the client's judgment has it instant. The anticheat clears it on the start,
     * so the stop that would finish it on the server is a dig on air there; a lone start is refused by the server instead
     */
    public static boolean mustWait(BlockState state, BlockPos pos) {
        float client = clientProgress(state, pos);
        return mustWait(client, serverProgress(state, client));
    }

    private static boolean mustWait(float client, float server) {
        return client >= 1.0f && server < 1.0f && ticksDisagreeing < MOST_TICKS_DISAGREEING;
    }

    /**
     * @return whether the client's own judgment, the one an anticheat replays, has the block instant with the item
     * selected now
     */
    public static boolean clientInstant(BlockState state, BlockPos pos) {
        return clientProgress(state, pos) >= 1.0f;
    }

    /**
     * @return whether the server breaks the block on a start sent now with the item selected now
     */
    public static boolean serverInstant(BlockState state, BlockPos pos) {
        return serverProgress(state, pos) >= 1.0f;
    }

    /**
     * @return per-tick progress on the block with the item selected now as the server judges it, which times a dig's
     * stop: progress of the moment times the ticks since its start
     */
    public static float serverProgress(BlockState state, BlockPos pos) {
        return serverProgress(state, clientProgress(state, pos));
    }

    private static float clientProgress(BlockState state, BlockPos pos) {
        boolean was = judging;
        judging = true;
        try {
            return state.getDestroyProgress(mc.player, mc.level, pos);
        } finally {
            judging = was;
        }
    }

    private static float serverProgress(BlockState state, float clientProgress) {
        LocalPlayer player = mc.player;
        // the lower of the two, since two ticks' packets can reach the server between two of its player ticks; NaN
        // until both are known
        double server = Math.min(efficiencyLastTick, efficiencyTickBefore);
        double synced = player.getAttributeValue(Attributes.MINING_EFFICIENCY);
        float speed = player.getMainHandItem().getDestroySpeed(state);
        // efficiency only adds to a tool that is faster than a hand
        if (Double.isNaN(server) || server == synced || speed <= 1.0f) return clientProgress;
        // it adds to the tool's speed, and everything else in the progress multiplies that
        return clientProgress * ((speed + (float) server) / (speed + (float) synced));
    }

    /**
     * @return the mining efficiency the server gives the player with this in the main hand: the client's copy of the
     * attribute with whatever the main hand gave swapped for this item's
     */
    private static double efficiencyWith(ItemStack mainHand) {
        AttributeInstance synced = mc.player.getAttribute(Attributes.MINING_EFFICIENCY);
        if (synced == null) return 0;

        AttributeInstance with = new AttributeInstance(Attributes.MINING_EFFICIENCY, instance -> {});
        with.setBaseValue(synced.getBaseValue());
        for (AttributeModifier modifier : synced.getModifiers()) {
            if (!modifier.id().getPath().endsWith(MAIN_HAND_SUFFIX)) with.addTransientModifier(modifier);
        }

        // the server applies nothing from a broken item
        if (!mainHand.isBroken()) {
            mainHand.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                if (attribute.value() == Attributes.MINING_EFFICIENCY.value()) with.addOrUpdateTransientModifier(modifier);
            });
        }

        return with.getValue();
    }
}
