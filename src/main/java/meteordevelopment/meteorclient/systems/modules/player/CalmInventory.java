/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.mixin.ClientInputAccessor;
import meteordevelopment.meteorclient.mixin.LocalPlayerAccessor;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

public class CalmInventory extends Module {
    private boolean holdNextTick;
    private boolean holding;

    public CalmInventory() {
        super(Categories.Player, "calm-inventory", "Stops moving, sprinting and sneaking for inventory clicks so anticheats like Grim don't cancel them. Pauses Baritone too.");
    }

    @Override
    public void onDeactivate() {
        holdNextTick = false;
        holding = false;
    }

    /**
     * Gates every inventory click. A refused click is cancelled before the client applies it, so callers that
     * re-check the inventory each tick just retry it once the player has held still for a tick.
     */
    public boolean allowClick() {
        if (!isActive()) return true;

        // also after an allowed click, so the next click of a multi-tick sequence still goes out calm
        holdNextTick = true;
        PathManagers.get().pauseForTick();
        return isCalm();
    }

    /**
     * What Grim's MultiActionsC checks a click against: the sprint and sneak state the server was last sent, and
     * whether the last input packet held a movement key. Jump counts as one, momentum doesn't.
     */
    private boolean isCalm() {
        Input sent = mc.player.getLastSentInput();
        return !((LocalPlayerAccessor) mc.player).meteor$wasSprinting()
            && !(sent.forward() || sent.backward() || sent.left() || sent.right() || sent.jump() || sent.shift());
    }

    /**
     * Runs once the player's input for this tick is final, before it moves the player or goes out to the server.
     */
    public void onInput(ClientInput input) {
        // never stop the path manager mid-jump; the click keeps waiting until it can stop safely
        holding = isActive() && holdNextTick && PathManagers.get().isSafeToPause();
        if (!holding) return;

        holdNextTick = false;
        input.keyPresses = Input.EMPTY;
        ((ClientInputAccessor) input).meteor$setMoveVector(Vec2.ZERO);
    }

    /**
     * @return whether movement is held this tick, so sprint and sneak have to stay off in what's sent to the server
     */
    public boolean isHolding() {
        return holding;
    }
}
