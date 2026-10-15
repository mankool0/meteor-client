/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientInputAccessor;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.ClientInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Rotations {
    private static final Pool<Rotation> rotationPool = new Pool<>(Rotation::new);
    private static final List<Rotation> rotations = new ArrayList<>();
    public static float serverYaw;
    public static float serverPitch;
    public static int rotationTimer;
    private static float preYaw, prePitch;
    private static int i = 0;

    private static Rotation lastRotation;
    private static int lastRotationTimer;
    private static boolean sentLastRotation;
    public static boolean rotating = false;

    private static boolean fixingMovement, movementRotated;
    private static float fixYaw, fixPitch;
    private static float preMoveYaw, preMovePitch;

    private Rotations() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(Rotations.class);
    }

    public static void rotate(double yaw, double pitch, int priority, boolean clientSide, Runnable callback) {
        Rotation rotation = rotationPool.get();
        rotation.set(yaw, pitch, priority, clientSide, callback);

        int i = 0;
        for (; i < rotations.size(); i++) {
            if (priority > rotations.get(i).priority) break;
        }

        rotations.add(i, rotation);
    }

    public static void rotate(double yaw, double pitch, int priority, Runnable callback) {
        rotate(yaw, pitch, priority, false, callback);
    }

    public static void rotate(double yaw, double pitch, Runnable callback) {
        rotate(yaw, pitch, 0, callback);
    }

    public static void rotate(double yaw, double pitch, int priority) {
        rotate(yaw, pitch, priority, null);
    }

    public static void rotate(double yaw, double pitch) {
        rotate(yaw, pitch, 0, null);
    }

    private static void resetLastRotation() {
        if (lastRotation != null) {
            rotationPool.free(lastRotation);

            lastRotation = null;
            lastRotationTimer = 0;
        }
    }

    @EventHandler
    private static void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (mc.getCameraEntity() != mc.player) return;
        sentLastRotation = false;

        if (!rotations.isEmpty()) {
            rotating = true;
            resetLastRotation();

            Rotation rotation = rotations.get(i);
            setupMovementPacketRotation(rotation);

            if (rotations.size() > 1) rotationPool.free(rotation);

            i++;
        } else if (lastRotation != null) {
            if (lastRotationTimer >= Config.get().rotationHoldTicks.get()) {
                resetLastRotation();
                rotating = false;
            } else {
                setupMovementPacketRotation(lastRotation);
                sentLastRotation = true;

                lastRotationTimer++;
            }
        }
    }

    private static void setupMovementPacketRotation(Rotation rotation) {
        setClientRotation(rotation);
        setCamRotation(rotation.yaw, rotation.pitch);
    }

    private static void setClientRotation(Rotation rotation) {
        preYaw = mc.player.getYRot();
        prePitch = mc.player.getXRot();

        mc.player.setYRot((float) rotation.yaw);
        mc.player.setXRot((float) rotation.pitch);
    }

    @EventHandler
    private static void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (!rotations.isEmpty()) {
            if (mc.getCameraEntity() == mc.player) {
                rotations.get(i - 1).runCallback();

                if (rotations.size() == 1) lastRotation = rotations.get(i - 1);

                resetPreRotation();
            }

            for (; i < rotations.size(); i++) {
                Rotation rotation = rotations.get(i);

                setCamRotation(rotation.yaw, rotation.pitch);
                if (rotation.clientSide) setClientRotation(rotation);
                rotation.sendPacket();
                if (rotation.clientSide) resetPreRotation();

                if (i == rotations.size() - 1) lastRotation = rotation;
                else rotationPool.free(rotation);
            }

            rotations.clear();
            i = 0;
        } else if (sentLastRotation) {
            resetPreRotation();
        }
    }

    private static void resetPreRotation() {
        mc.player.setYRot(preYaw);
        mc.player.setXRot(prePitch);
    }

    // Movement fix

    /**
     * Grim simulates movement as the movement keys turned by the rotation in the movement packet, so moving relative to the
     * camera while sending another rotation gets the player set back. Re-picks the keys as the 8-way combination closest to
     * the direction the player wants to go, relative to the rotation this tick's movement packet will carry.
     */
    public static void fixMovementInput(ClientInput input) {
        fixingMovement = false;
        if (!Config.get().movementFix.get()) return;
        // elytra flight goes where the player looks, so it can't go one way while looking another
        if (mc.getCameraEntity() != mc.player || mc.player.isPassenger() || mc.player.isFallFlying()) return;

        Rotation rotation = getPendingRotation();
        if (rotation == null) return;

        fixingMovement = true;
        fixYaw = (float) rotation.yaw;
        fixPitch = (float) rotation.pitch;

        Vec2 move = input.getMoveVector();
        if (move.lengthSquared() == 0) return;

        // the wanted direction, turned from the camera's frame into the sent rotation's frame
        double delta = Math.toRadians(mc.player.getYRot() - fixYaw);
        double sin = Math.sin(delta);
        double cos = Math.cos(delta);
        double strafe = move.x * cos - move.y * sin;
        double forward = move.y * cos + move.x * sin;

        double angle = Math.round(Math.atan2(forward, strafe) / (Math.PI / 4)) * (Math.PI / 4);
        int s = (int) Math.round(Math.cos(angle));
        int f = (int) Math.round(Math.sin(angle));

        Input keys = input.keyPresses;
        input.keyPresses = new Input(f > 0, f < 0, s > 0, s < 0, keys.jump(), keys.shift(), keys.sprint());
        ((ClientInputAccessor) input).meteor$setMoveVector(new Vec2(s, f).normalized());
    }

    /**
     * Grim treats a sprinting player as holding forward, so sprint has to stop when the re-picked keys don't include it.
     */
    public static void fixMovementSprint(ClientInput input) {
        if (fixingMovement && mc.player.isSprinting() && !mc.player.isSwimming() && !input.keyPresses.forward()) {
            mc.player.setSprinting(false);
        }
    }

    /**
     * Turns the player to the sent rotation for jumping and travel, after the input is applied (which also eases the hand
     * sway towards the real rotation).
     */
    public static void startMovementRotation() {
        if (!fixingMovement || mc.player.isFallFlying()) return;

        preMoveYaw = mc.player.getYRot();
        preMovePitch = mc.player.getXRot();
        mc.player.setYRot(fixYaw);
        mc.player.setXRot(fixPitch);
        movementRotated = true;
    }

    public static void stopMovementRotation() {
        fixingMovement = false;
        if (!movementRotated) return;

        movementRotated = false;
        mc.player.setYRot(preMoveYaw);
        mc.player.setXRot(preMovePitch);
        // Player.aiStep copies the yaw into the head
        mc.player.setYHeadRot(preMoveYaw);
    }

    /**
     * @return the rotation {@link #onSendMovementPacketsPre} will put in this tick's movement packet
     */
    private static Rotation getPendingRotation() {
        if (!rotations.isEmpty()) return rotations.getFirst();
        if (lastRotation != null && lastRotationTimer < Config.get().rotationHoldTicks.get()) return lastRotation;
        return null;
    }

    @EventHandler
    private static void onTick(TickEvent.Pre event) {
        rotationTimer++;
    }

    public static double getYaw(Entity entity) {
        return mc.player.getYRot() + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(entity.getZ() - mc.player.getZ(), entity.getX() - mc.player.getX())) - 90f - mc.player.getYRot());
    }

    public static double getYaw(Vec3 pos) {
        return mc.player.getYRot() + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(pos.z() - mc.player.getZ(), pos.x() - mc.player.getX())) - 90f - mc.player.getYRot());
    }

    public static double getPitch(Vec3 pos) {
        double diffX = pos.x() - mc.player.getX();
        double diffY = pos.y() - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = pos.z() - mc.player.getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getXRot() + Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getXRot());
    }

    public static double getPitch(Entity entity, Target target) {
        double y = switch (target) {
            case Head -> entity.getEyeY();
            case Body -> entity.getY() + entity.getBbHeight() / 2;
            case Feet -> entity.getY();
        };

        double diffX = entity.getX() - mc.player.getX();
        double diffY = y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = entity.getZ() - mc.player.getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getXRot() + Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getXRot());
    }

    public static double getPitch(Entity entity) {
        return getPitch(entity, Target.Body);
    }

    public static double getYaw(BlockPos pos) {
        return mc.player.getYRot() + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(pos.getZ() + 0.5 - mc.player.getZ(), pos.getX() + 0.5 - mc.player.getX())) - 90f - mc.player.getYRot());
    }

    public static double getPitch(BlockPos pos) {
        double diffX = pos.getX() + 0.5 - mc.player.getX();
        double diffY = pos.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = pos.getZ() + 0.5 - mc.player.getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getXRot() + Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getXRot());
    }

    public static void setCamRotation(double yaw, double pitch) {
        serverYaw = (float) yaw;
        serverPitch = (float) pitch;
        rotationTimer = 0;
    }

    private static class Rotation {
        public double yaw, pitch;
        public int priority;
        public boolean clientSide;
        public Runnable callback;

        public void set(double yaw, double pitch, int priority, boolean clientSide, Runnable callback) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.priority = priority;
            this.clientSide = clientSide;
            this.callback = callback;
        }

        public void sendPacket() {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot((float) yaw, (float) pitch, mc.player.onGround(), mc.player.horizontalCollision));
            runCallback();
        }

        public void runCallback() {
            if (callback != null) callback.run();
        }
    }
}
