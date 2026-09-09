/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.HorseInventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// PORT(1.21.4): AbstractMountInventoryMenu (a generalized mount-menu superclass covering horses, llamas, camels
// etc.) doesn't exist yet - 1.21.4 only has the concrete HorseInventoryMenu, which holds the ridden animal in a
// field named "horse" of type AbstractHorse, instead of a "mount" field.
@Mixin(HorseInventoryMenu.class)
public interface AbstractMountInventoryMenuAccessor {
    @Accessor("horse")
    AbstractHorse meteor$getMount();
}
