/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class ElytraBoost extends Module {
    public enum BoostModes {
        Rocket,
        Fall
    }
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<BoostModes> boostMode = sgGeneral.add(new EnumSetting.Builder<BoostModes>()
        .name("mode")
        .description("The boost mode.")
        .defaultValue(BoostModes.Fall)
        .build()
    );

    private final Setting<Boolean> dontConsumeFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-consume")
        .description("Prevents fireworks from being consumed when using Elytra Boost.")
        .defaultValue(true)
        .visible(() -> boostMode.get() == BoostModes.Rocket)
        .build()
    );

    private final Setting<Integer> fireworkLevel = sgGeneral.add(new IntSetting.Builder()
        .name("firework-duration")
        .description("The duration of the firework.")
        .defaultValue(0)
        .range(0, 255)
        .sliderMax(255)
        .visible(() -> boostMode.get() == BoostModes.Rocket)
        .build()
    );

    private final Setting<Boolean> playSound = sgGeneral.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Plays the firework sound when a boost is triggered.")
        .defaultValue(true)
        .visible(() -> boostMode.get() == BoostModes.Rocket)
        .build()
    );

    private final Setting<Keybind> keybind = sgGeneral.add(new KeybindSetting.Builder()
        .name("keybind")
        .description("The keybind to boost.")
        .action(this::boost)
        .visible(() -> boostMode.get() == BoostModes.Rocket)
        .build()
    );

    public final Setting<Double> boostSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("boost-speed")
        .description("Boost amount")
        .defaultValue(0.05)
        .min(0)
        .max(0.15)
        .visible(() -> boostMode.get() == BoostModes.Fall)
        .build()
    );

    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Speed")
        .defaultValue(0.8)
        .min(0)
        .max(5)
        .visible(() -> boostMode.get() == BoostModes.Fall)
        .build()
    );

    private final List<FireworkRocketEntity> fireworks = new ArrayList<>();

    public ElytraBoost() {
        super(Categories.Movement, "elytra-boost", "Boosts your elytra.");
    }

    @Override
    public void onDeactivate() {
        fireworks.clear();
    }

    @EventHandler
    private void onInteractItem(InteractItemEvent event) {
        if (boostMode.get() != BoostModes.Rocket)
            return;
        ItemStack itemStack = mc.player.getStackInHand(event.hand);

        if (itemStack.getItem() instanceof FireworkRocketItem && dontConsumeFirework.get()) {
            event.toReturn = ActionResult.PASS;

            boost();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        fireworks.removeIf(Entity::isRemoved);

        if (boostMode.get() == BoostModes.Fall)
        {
            // Credits to: https://github.com/BleachDev/BleachHack/blob/7c96e556ef10562a09375ed1893e25bbacf3db92/src/main/java/org/bleachhack/module/mods/ElytraFly.java
            double currentVel = Math.abs(mc.player.getVelocity().x) + Math.abs(mc.player.getVelocity().y) + Math.abs(mc.player.getVelocity().z);
            float radianYaw = (float) Math.toRadians(mc.player.getYaw());
            float boost = boostSpeed.get().floatValue();

            if (mc.player.isFallFlying() && currentVel <= speed.get()) {
                if (mc.options.backKey.isPressed()) {
                    mc.player.addVelocity(MathHelper.sin(radianYaw) * boost, 0, MathHelper.cos(radianYaw) * -boost);
                } else if (mc.player.getPitch() > 0) {
                    mc.player.addVelocity(MathHelper.sin(radianYaw) * -boost, 0, MathHelper.cos(radianYaw) * boost);
                }
            }
        }
    }

    private void boost() {
        if (!Utils.canUpdate()) return;

        if (mc.player.isFallFlying() && mc.currentScreen == null) {
            ItemStack itemStack = Items.FIREWORK_ROCKET.getDefaultStack();
            itemStack.getOrCreateSubNbt("Fireworks").putByte("Flight", fireworkLevel.get().byteValue());

            FireworkRocketEntity entity = new FireworkRocketEntity(mc.world, itemStack, mc.player);
            fireworks.add(entity);
            if (playSound.get()) mc.world.playSoundFromEntity(mc.player, entity, SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.AMBIENT, 3.0F, 1.0F);
            mc.world.addEntity(entity.getId(), entity);
        }
    }

    public boolean isFirework(FireworkRocketEntity firework) {
        return isActive() && fireworks.contains(firework);
    }
}
