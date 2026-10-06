/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixininterface;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// PORT(1.21.11): there is no ServerboundAttackPacket on 1.21.11, attacks are sent as a ServerboundInteractPacket
public interface IServerboundInteractPacket {
    boolean meteor$isAttack();

    Entity meteor$getEntity();

    final class AttackCheck implements ServerboundInteractPacket.Handler {
        public boolean attack;

        @Override
        public void onInteraction(InteractionHand hand) {
        }

        @Override
        public void onInteraction(InteractionHand hand, Vec3 location) {
        }

        @Override
        public void onAttack() {
            attack = true;
        }
    }
}
