/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.Settings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

public interface IPathManager {
    String getName();

    boolean isPathing();

    void pause();

    void resume();

    /**
     * Pauses for the next tick only. Unlike {@link #pause()} it has to be requested again every tick it should
     * last, so it ends on its own once the caller stops asking.
     */
    default void pauseForTick() {
    }

    /**
     * @return whether movement can stop right now without breaking the current path, e.g. not in the middle of a jump
     */
    default boolean isSafeToPause() {
        return true;
    }

    void stop();

    default void moveTo(BlockPos pos) {
        moveTo(pos, false);
    }

    void moveTo(BlockPos pos, boolean ignoreY);

    void moveInDirection(float yaw);

    void mine(Block... blocks);

    void follow(Predicate<Entity> entity);

    float getTargetYaw();

    float getTargetPitch();

    /**
     * @return the yaw the player walks with this tick, which isn't the given camera yaw when the path manager steers
     * the player with a rotation of its own
     */
    default float getMovementYaw(float yaw) {
        return yaw;
    }

    ISettings getSettings();

    interface ISettings {
        Settings get();

        Setting<Boolean> getWalkOnWater();

        Setting<Boolean> getWalkOnLava();

        Setting<Boolean> getStep();

        Setting<Boolean> getNoFall();

        void save();
    }
}
