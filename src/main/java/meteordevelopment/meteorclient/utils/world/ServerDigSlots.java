/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.world;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.MultiPlayerGameModeAccessor;
import meteordevelopment.meteorclient.mixininterface.IMultiPlayerGameMode;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The server's two dig slots as the dig packets that went out leave them, whoever sent them, and the packets to work
 * them. Ported from mankool0/baritone's DoubleMine.
 * <p>
 * The server keeps one regular dig per player, timed from the last start packet. Any start restarts that timer, even
 * one for a block it breaks at once, but only a start for a block that survives it moves the dig to that block. A stop
 * on the dig's block breaks it once the time since that start covers {@link #FINISH_PROGRESS} of its break time. A stop
 * that comes earlier parks the block in a single delayed slot instead, which the server finishes by itself once the full
 * break time has passed, with whatever is in hand then: no more packets and no reach check. With one block parked and
 * another dug in the regular slot, two blocks break at once.
 */
public final class ServerDigSlots {
    /**
     * A stop breaks the regular dig's block once the time since its start covers this much of its break time.
     */
    public static final float FINISH_PROGRESS = 0.7f;

    /**
     * Ticks a stop that relies on {@link #FINISH_PROGRESS} waits past it, so a start packet held back by network jitter,
     * or a server stall, can't make the stop land early.
     */
    public static final int FINISH_MARGIN_TICKS = 2;

    /**
     * How long past its expected finish a parked block, or an unanswered stop, may stay before it's presumed lost.
     */
    private static final int LOST_AFTER_TICKS = 40;

    /**
     * Breaker ids the slots show their digs under in the level renderer, which vanilla only fills from its own dig and
     * from the server's packets about other players' digs. Negative so they can't collide with an entity id, and apart
     * from the ones Baritone uses.
     */
    private static final int REGULAR_BREAKER_ID = Integer.MIN_VALUE + 16;
    private static final int PARKED_BREAKER_ID = Integer.MIN_VALUE + 17;

    public static final class Dig {
        public final BlockPos pos;
        public final BlockState state;
        /**
         * The tick the start went out, or the last start of any block since then, which restarted the server's timer.
         * A parked block keeps the start it had when it was parked.
         */
        long start;

        Dig(BlockPos pos, BlockState state, long start) {
            this.pos = pos;
            this.state = state;
            this.start = start;
        }

        public long start() {
            return start;
        }
    }

    // the server's dig position and the start it times from, null when not known
    private static Dig target;
    // whether the server is digging the target, as opposed to having finished, parked or aborted it
    private static boolean destroying;
    private static Dig parked;

    /**
     * A predicted stop the server hasn't answered yet. One it judges short of the threshold breaks nothing and the ack
     * puts the block back, parked if the delayed slot was free.
     */
    private static Dig finishing;
    private static int finishingSequence;
    private static long finishingSent;
    private static boolean finishingParks;

    private static long tick;
    // the server's dig state goes with its player, which a rejoin, a respawn and a dimension change all replace
    private static LocalPlayer player;

    private ServerDigSlots() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(ServerDigSlots.class);
    }

    // State

    /**
     * @return a client tick count that the slots time digs by
     */
    public static long tick() {
        return tick;
    }

    /**
     * @return the dig in the server's regular slot, or null
     */
    public static Dig regular() {
        return destroying ? target : null;
    }

    /**
     * @return the block parked in the server's delayed slot, or null
     */
    public static Dig parked() {
        return parked;
    }

    /**
     * @return whether the delayed slot is free to park a block in
     */
    public static boolean canPark() {
        return parked == null && (finishing == null || !finishingParks);
    }

    /**
     * @return whether either slot has the block, or a stop that may have broken it is unanswered
     */
    public static boolean isDigging(BlockPos pos) {
        return destroying && target.pos.equals(pos)
            || parked != null && parked.pos.equals(pos)
            || finishing != null && finishing.pos.equals(pos);
    }

    /**
     * @return the server's per-tick progress on the dig with the item selected now: it judges the stop with whatever is
     * in hand when the stop arrives
     */
    public static float progress(Dig dig) {
        return ServerDigSpeed.serverProgress(dig.state, dig.pos);
    }

    /**
     * @param early finish at {@link #FINISH_PROGRESS} plus a margin when that's sooner than the full break time
     * @return the tick a stop for the regular dig breaks its block, or Long.MAX_VALUE if it never does
     */
    public static long finishTick(Dig dig, boolean early) {
        float progress = progress(dig);
        int full = ticksToReach(progress, 1.0f);
        if (early) full = Math.min(full, ticksToReach(progress, FINISH_PROGRESS) + FINISH_MARGIN_TICKS);
        return full == Integer.MAX_VALUE ? Long.MAX_VALUE : dig.start + full;
    }

    /**
     * @return the ticks after the start at which the server counts the dig as reaching the fraction; it compares
     * {@code progress * (elapsed + 1)} in float
     */
    public static int ticksToReach(float progress, float fraction) {
        // never, say a server zeroing the block break speed attribute
        if (progress <= 0 || fraction / progress >= Integer.MAX_VALUE) return Integer.MAX_VALUE;

        int elapsed = Math.max(0, (int) Math.ceil(fraction / progress) - 1);
        while (elapsed > 0 && progress * (float) elapsed >= fraction) elapsed--;
        while (progress * (float) (elapsed + 1) < fraction) elapsed++;
        return elapsed;
    }

    // Packets

    /**
     * Breaks a block the server breaks on the start: a lone start, predicted like vanilla's instant break. The caller
     * has the tool selected; the held item is synced here.
     */
    public static void breakInstant(BlockPos pos, Direction face) {
        stopVanillaDig();
        sync();
        mc.gameMode.startPrediction(mc.level, sequence -> {
            mc.gameMode.destroyBlock(pos);
            return new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, pos, face, sequence);
        });
    }

    /**
     * Breaks a block the server finishes within the tick with a start and a stop, predicted under the stop's sequence
     * like a vanilla finished dig, so a refused break is restored by the server's ack instead of leaving ghost air.
     */
    public static void breakFinishing(BlockPos pos, Direction face) {
        stopVanillaDig();
        sync();
        mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, pos, face, sequence));
        mc.gameMode.startPrediction(mc.level, sequence -> {
            mc.gameMode.destroyBlock(pos);
            return new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, pos, face, sequence);
        });
    }

    /**
     * Starts digging a block that takes the server more than a tick, which becomes the regular dig.
     */
    public static void start(BlockPos pos, Direction face) {
        stopVanillaDig();
        sync();
        mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, pos, face, sequence));
    }

    /**
     * Sends a stop for the regular dig: it breaks the block once {@link #finishTick} is reached, otherwise it parks the
     * block if the delayed slot is free.
     *
     * @param predict remove the block client-side, for a stop that breaks it
     */
    public static void stop(Direction face, boolean predict) {
        Dig dig = regular();
        if (dig == null) return;

        sync();
        mc.gameMode.startPrediction(mc.level, sequence -> {
            if (predict) mc.gameMode.destroyBlock(dig.pos);
            return new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, dig.pos, face, sequence);
        });
    }

    /**
     * Gives up the regular dig. The face is down like vanilla's, which an anticheat doesn't hold the next start to.
     */
    public static void abort() {
        Dig dig = regular();
        if (dig != null) mc.getConnection().send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, dig.pos, Direction.DOWN));
    }

    /**
     * Ends the client's own dig, as bare dig packets take over the server's dig slot and vanilla would otherwise finish
     * it into a block the server isn't digging.
     */
    public static void stopVanillaDig() {
        if (!mc.gameMode.isDestroying()) return;

        BlockPos pos = ((MultiPlayerGameModeAccessor) mc.gameMode).meteor$getCurrentBreakingBlockPos();
        mc.getConnection().send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN));
        ((MultiPlayerGameModeAccessor) mc.gameMode).meteor$setDestroying(false);
        mc.level.destroyBlockProgress(mc.player.getId(), pos, -1);
    }

    /**
     * The server judges a dig packet with the item it believes is held when the packet arrives.
     */
    private static void sync() {
        ((IMultiPlayerGameMode) mc.gameMode).meteor$syncSelected();
    }

    /**
     * @return the face of the block whose side the eye is on by the widest margin, which an anticheat requires of every
     * start and stop. Eye heights from crouching to standing count, as Grim allows them all.
     */
    public static Direction face(BlockPos pos) {
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        Vec3 eye = mc.player.getEyePosition();
        double feet = mc.player.getY();

        Direction best = null;
        double bestMargin = 0;
        for (Direction direction : Direction.values()) {
            double margin = switch (direction) {
                case EAST -> eye.x - box.maxX;
                case WEST -> box.minX - eye.x;
                case SOUTH -> eye.z - box.maxZ;
                case NORTH -> box.minZ - eye.z;
                case UP -> feet + 1.62 - box.maxY;
                case DOWN -> box.minY - (feet + 0.4);
            };
            if (margin > bestMargin) {
                bestMargin = margin;
                best = direction;
            }
        }

        return best != null ? best : Direction.getApproximateNearest(eye.subtract(box.getCenter()));
    }

    // Tracking

    @EventHandler(priority = EventPriority.HIGHEST + 200)
    private static void onTickPre(TickEvent.Pre event) {
        tick++;
        if (mc.player != player || mc.level == null) {
            reset();
            player = mc.player;
        }
        if (player == null) return;

        if (finishing != null && PendingPlaces.of(mc.level).isAcked(finishingSequence)) {
            if (!gone(finishing)) {
                // the server judged the stop short of the threshold
                if (finishingParks) parked = finishing;
                else if (target == finishing) destroying = true; // the delayed slot was busy, so it's still digging
            }
            finishing = null;
        } else if (finishing != null && tick > finishingSent + LOST_AFTER_TICKS) {
            finishing = null; // never answered
        }

        if (parked != null && (gone(parked) || tick > parked.start + ticksToReach(progress(parked), 1.0f) + LOST_AFTER_TICKS)) {
            parked = null;
        }

        // the server stops digging a block that's gone, whoever broke it
        if (destroying && gone(target)) destroying = false;
    }

    @EventHandler(priority = EventPriority.LOWEST - 200)
    private static void onTickPost(TickEvent.Post event) {
        if (mc.level == null || mc.gameMode == null) return;

        Dig regular = regular();
        // vanilla shows its own dig
        if (regular != null && mc.gameMode.isDestroying() && regular.pos.equals(((MultiPlayerGameModeAccessor) mc.gameMode).meteor$getCurrentBreakingBlockPos())) {
            regular = null;
        }
        show(REGULAR_BREAKER_ID, regular, FINISH_PROGRESS, FINISH_MARGIN_TICKS);
        show(PARKED_BREAKER_ID, parked, 1.0f, 0);
    }

    @EventHandler
    private static void onGameLeft(GameLeftEvent event) {
        reset();
        player = null;
    }

    @EventHandler(priority = EventPriority.LOWEST - 200)
    private static void onPacketSent(PacketEvent.Sent event) {
        if (!(event.packet instanceof ServerboundPlayerActionPacket packet) || mc.level == null || mc.player == null) return;

        BlockPos pos = packet.getPos();
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> {
                // a predicted instant break is already air here
                BlockState state = mc.level.getBlockState(pos);
                if (state.isAir() || ServerDigSpeed.serverInstant(state, pos)) {
                    if (target != null) target.start = tick;
                } else {
                    target = new Dig(pos.immutable(), state, tick);
                    destroying = true;
                }
            }
            case STOP_DESTROY_BLOCK -> {
                if (target == null || !target.pos.equals(pos)) return;

                if (progress(target) * (tick - target.start + 1) >= FINISH_PROGRESS) {
                    destroying = false;
                    finishing = target;
                    finishingSequence = packet.getSequence();
                    finishingSent = tick;
                    finishingParks = canPark();
                } else if (canPark()) {
                    destroying = false;
                    parked = new Dig(target.pos, target.state, target.start);
                }
                // otherwise the server digs on
            }
            case ABORT_DESTROY_BLOCK -> {
                destroying = false;
                if (target != null && !target.pos.equals(pos)) target = null;
            }
            default -> {}
        }
    }

    private static void reset() {
        target = null;
        destroying = false;
        parked = null;
        finishing = null;
        if (mc.levelRenderer != null) {
            mc.levelRenderer.destroyBlockProgress(REGULAR_BREAKER_ID, BlockPos.ZERO, -1);
            mc.levelRenderer.destroyBlockProgress(PARKED_BREAKER_ID, BlockPos.ZERO, -1);
        }
    }

    private static boolean gone(Dig dig) {
        return mc.level.getBlockState(dig.pos).getBlock() != dig.state.getBlock();
    }

    /**
     * Shows a slot's dig like vanilla shows its own, staged by how far it is to the tick the block breaks, or clears the
     * slot's entry. Client-side only.
     */
    private static void show(int breakerId, Dig dig, float fraction, int marginTicks) {
        if (dig == null) {
            mc.levelRenderer.destroyBlockProgress(breakerId, BlockPos.ZERO, -1);
            return;
        }

        int reach = ticksToReach(progress(dig), fraction);
        long ticks = reach == Integer.MAX_VALUE ? Long.MAX_VALUE : (long) reach + marginTicks;
        int stage = ticks <= 0 ? 9 : (int) Math.min(9, (tick - dig.start) * 10 / ticks);
        mc.levelRenderer.destroyBlockProgress(breakerId, dig.pos, stage);
    }
}
