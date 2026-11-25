/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.network;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.*;

/**
 * Counts the action packets that went out this tick, whoever sent them: digs, swings, uses, slot changes and entity
 * interactions. Some servers kick for a burst of them in one tick (Constantiam takes 7 a tick but kicks at 9), so
 * modules that send a lot of them keep under a per-tick budget.
 */
public final class ActionPackets {
    private static int sentThisTick;

    private ActionPackets() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(ActionPackets.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 200)
    private static void onTickPre(TickEvent.Pre event) {
        sentThisTick = 0;
    }

    @EventHandler
    private static void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundPlayerActionPacket
            || event.packet instanceof ServerboundSwingPacket
            || event.packet instanceof ServerboundUseItemOnPacket
            || event.packet instanceof ServerboundUseItemPacket
            || event.packet instanceof ServerboundSetCarriedItemPacket
            || event.packet instanceof ServerboundInteractPacket) {
            sentThisTick++;
        }
    }

    public static int sentThisTick() {
        return sentThisTick;
    }

    /**
     * @return whether this many more action packets fit under the budget this tick; the tick's first action always does
     */
    public static boolean fit(int packets, int budget) {
        return sentThisTick == 0 || sentThisTick + packets <= budget;
    }
}
