/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Main hand uses on blocks the server hasn't acked yet, per hotbar slot. One per client level, since the prediction
 * sequences are. Ported from mankool0/baritone's printer.
 * <p>
 * The client takes a placed block off its own stack, but every inventory update the server sends replaces the stack
 * with the server's count, which is a round trip stale. So while places are in flight the client's count runs high by
 * about one per tick of round trip, and once the server has run the slot dry the client still shows a few. Places sent
 * on those go out with whatever the server holds in the slot by then.
 * <p>
 * The server sends the update for a place in the same tick as its ack and just ahead of it, so once a place is acked the
 * stack shows it. Until then, the stack's count plus the client's own decrements on it, minus the places in flight, is
 * what the server will have left.
 * <p>
 * A use dropped on its way to the server, by an anticheat or a spam limit, is never acked by itself, only by the ack of
 * some later action, so one unacked for well over any round trip counts as lost.
 * <p>
 * Every use the server handles is answered with the state of the block it went into, ahead of the ack, whether it
 * placed anything or not. One acked without that answer, or never acked, wasn't handled. Acked by itself, it was turned
 * down on its own, the way an anticheat cancelling a place acks it. Covered only by the ack of something sent after it,
 * or by none, it never reached the server's handler.
 */
public final class PendingPlaces {
    private static final long LOST_AFTER_NANOS = TimeUnit.SECONDS.toNanos(2);
    // how long a use acked by a later one may still be answered
    private static final long ANSWER_AFTER_ACK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static PendingPlaces instance;
    private static ClientLevel instanceLevel;

    private static final class Use {
        final int sequence;
        final int slot;
        final long sentNanos;
        final long target; // BlockPos.asLong of where it went
        final boolean later; // another use went out earlier in the same tick
        boolean answered;
        long ackedNanos;

        Use(int sequence, int slot, long sentNanos, long target, boolean later) {
            this.sequence = sequence;
            this.slot = slot;
            this.sentNanos = sentNanos;
            this.target = target;
            this.later = later;
        }
    }

    /**
     * Uses the server didn't handle.
     *
     * @param dropped      never reached its handler
     * @param refused      turned down on their own
     * @param refusedLater of the refused, how many went out after another use in the same tick
     */
    public record Unanswered(int dropped, int refused, int refusedLater) {
        public static final Unanswered NONE = new Unanswered(0, 0, 0);

        public int total() {
            return dropped + refused;
        }

        public Unanswered plus(Unanswered other) {
            return new Unanswered(dropped + other.dropped, refused + other.refused, refusedLater + other.refusedLater);
        }
    }

    private final LongSupplier clock;
    private final ArrayDeque<Use> unacked = new ArrayDeque<>();
    // acked by a later use without their answer so far
    private final ArrayDeque<Use> awaitingAnswer = new ArrayDeque<>();
    private final int[] inFlight = new int[9];
    // the stack the client took its own decrements off; any update from the server replaces it
    private final ItemStack[] decremented = new ItemStack[9];
    private final int[] decrements = new int[9];
    private int lastUseTick = Integer.MIN_VALUE;
    private int dropped;
    private int refused;
    private int refusedLater;
    private int lastAck = -1;
    // smoothed time from sending a use to its ack, -1 before the first
    private long roundTripNanos = -1;

    public PendingPlaces() {
        this(System::nanoTime);
    }

    PendingPlaces(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * @return the uses in flight on this level, made afresh for every new level
     */
    public static PendingPlaces of(ClientLevel level) {
        if (instance == null || instanceLevel != level) {
            instance = new PendingPlaces();
            instanceLevel = level;
        }
        return instance;
    }

    /**
     * Called on the main thread after a use on a block went out from this hotbar slot, whether or not the client
     * predicted it doing anything: the server may still place it.
     *
     * @param stack       the stack in the slot when the use was predicted
     * @param countBefore its count before the prediction
     * @param target      the block next to the clicked face, or the clicked block if it was replaced, which the server
     *                    always answers for
     * @param tick        the player's tick count, to tell a use from the ones before it in its tick
     */
    public void onUse(int slot, int sequence, ItemStack stack, int countBefore, BlockPos target, int tick) {
        if (slot < 0 || slot >= inFlight.length) return;

        unacked.add(new Use(sequence, slot, clock.getAsLong(), target.asLong(), tick == lastUseTick));
        lastUseTick = tick;
        inFlight[slot]++;
        if (decremented[slot] != stack) {
            decremented[slot] = stack;
            decrements[slot] = 0;
        }
        decrements[slot] += Math.max(0, countBefore - stack.getCount());
    }

    /**
     * Called on the main thread with each ack, after the inventory updates sent ahead of it.
     */
    public void onAck(int sequence) {
        lastAck = Math.max(lastAck, sequence);

        Use newest = null;
        while (!unacked.isEmpty() && unacked.peekFirst().sequence <= sequence) {
            Use use = unacked.removeFirst();
            newest = use;
            inFlight[use.slot]--;
            if (use.answered) continue;

            if (use.sequence == sequence) {
                refused++;
                if (use.later) refusedLater++;
            } else {
                use.ackedNanos = clock.getAsLong();
                awaitingAnswer.add(use);
            }
        }

        // the newest use an ack covers waited least for it, so it's the round trip
        if (newest != null) {
            long sample = clock.getAsLong() - newest.sentNanos;
            roundTripNanos = roundTripNanos < 0 ? sample : roundTripNanos + (sample - roundTripNanos) / 8;
        }
    }

    /**
     * @return whether the server has acked this prediction sequence, or something sent after it, so the client shows
     * what the server made of it
     */
    public boolean isAcked(int sequence) {
        return sequence <= lastAck;
    }

    /**
     * @return the smoothed time from sending a use to the server's ack of it, which takes in the wait for the end of
     * the server's tick, or -1 before any use was acked
     */
    public long roundTripNanos() {
        return roundTripNanos;
    }

    /**
     * Called on the main thread with each block state the server sends.
     */
    public void onServerState(BlockPos pos) {
        long key = pos.asLong();
        for (Use use : unacked) {
            if (use.target == key) use.answered = true;
        }
        awaitingAnswer.removeIf(use -> use.target == key);
    }

    /**
     * @return the uses the server didn't handle since this was last asked
     */
    public Unanswered takeUnanswered() {
        dropLost();
        Unanswered taken = new Unanswered(dropped, refused, refusedLater);
        dropped = 0;
        refused = 0;
        refusedLater = 0;
        return taken;
    }

    private void dropLost() {
        long now = clock.getAsLong();
        while (!unacked.isEmpty() && now - unacked.peekFirst().sentNanos > LOST_AFTER_NANOS) {
            inFlight[unacked.removeFirst().slot]--;
            dropped++;
        }
        while (!awaitingAnswer.isEmpty() && now - awaitingAnswer.peekFirst().ackedNanos > ANSWER_AFTER_ACK_NANOS) {
            awaitingAnswer.removeFirst();
            dropped++;
        }
    }

    /**
     * @return where the uses the server hasn't acked yet went. Once a use is acked, the client shows whatever the
     * server made of it there.
     */
    public List<BlockPos> targetsInFlight() {
        dropLost();
        List<BlockPos> targets = new ArrayList<>(unacked.size());
        for (Use use : unacked) targets.add(BlockPos.of(use.target));
        return targets;
    }

    /**
     * @return whether a use into this block is still waiting for the server's ack
     */
    public boolean isInFlight(BlockPos pos) {
        dropLost();
        long key = pos.asLong();
        for (Use use : unacked) {
            if (use.target == key) return true;
        }
        return false;
    }

    /**
     * @return uses from this hotbar slot the server hasn't acked yet
     */
    public int inFlight(int slot) {
        dropLost();
        return slot >= 0 && slot < inFlight.length ? inFlight[slot] : 0;
    }

    /**
     * @param stack what the client shows in the slot
     * @return how many of it the server holds once every use in flight from the slot has landed, or fewer if some of
     * them don't consume anything
     */
    public int countAfterInFlight(int slot, ItemStack stack) {
        if (slot < 0 || slot >= inFlight.length) return stack.getCount();
        dropLost();

        int standing = decremented[slot] == stack ? decrements[slot] : 0;
        return stack.getCount() + standing - inFlight[slot];
    }
}
