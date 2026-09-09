/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.EntityControl;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Strider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// PORT(1.21.4): Mob.isSaddled() does not exist on 1.21.4 - saddle state lives on the Saddleable interface,
// implemented independently by AbstractHorse (and its subclass Camel), Pig and Strider.
@Mixin({AbstractHorse.class, Pig.class, Strider.class})
public abstract class MobMixin {
    @ModifyReturnValue(method = "isSaddled", at = @At("RETURN"))
    private boolean isSaddled(boolean original) {
        return Modules.get().get(EntityControl.class).spoofSaddle() || original;
    }
}
