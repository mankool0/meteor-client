/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import com.google.common.util.concurrent.AtomicDouble;
import it.unimi.dsi.fastutil.ints.*;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IAABB;
import meteordevelopment.meteorclient.mixininterface.IClipContext;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class CrystalAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSwitch = settings.createGroup("Switch");
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgFacePlace = settings.createGroup("Face Place");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgPause = settings.createGroup("Pause");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range in which to target players.")
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Predicts target movement.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum damage the crystal needs to deal to your target.")
        .defaultValue(6)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-damage")
        .description("Maximum damage crystals can deal to yourself.")
        .defaultValue(6)
        .range(0, 36)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Will not place and break crystals if they will kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-nakeds")
        .description("Ignore players with no items.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side towards the crystals being hit/placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<YawStepMode> yawStepMode = sgGeneral.add(new EnumSetting.Builder<YawStepMode>()
        .name("yaw-steps-mode")
        .description("When to run the yaw steps check.")
        .defaultValue(YawStepMode.Break)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Double> yawSteps = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-steps")
        .description("Maximum number of degrees its allowed to rotate in one tick.")
        .defaultValue(180)
        .range(1, 180)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER, EntityType.WARDEN, EntityType.WITHER)
        .build()
    );

    // Switch

    private final Setting<AutoSwitchMode> autoSwitch = sgSwitch.add(new EnumSetting.Builder<AutoSwitchMode>()
        .name("auto-switch")
        .description("Switches to crystals in your hotbar once a target is found.")
        .defaultValue(AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Integer> switchDelay = sgSwitch.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("The delay in ticks to wait to break a crystal after switching hotbar slot.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-gap-switch")
        .description("Won't auto switch if you're holding a gapple.")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() == AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Boolean> noBowSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-bow-switch")
        .description("Won't auto switch if you're holding a bow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiWeakness = sgSwitch.add(new BoolSetting.Builder()
        .name("anti-weakness")
        .description("Switches to tools with so you can break crystals with the weakness effect.")
        .defaultValue(true)
        .build()
    );

    // Place

    private final Setting<Boolean> doPlace = sgPlace.add(new BoolSetting.Builder()
        .name("place")
        .description("If the CA should place crystals.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The delay in ticks to wait to place a crystal after it's exploded.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Range in which to place crystals, measured from your eyes to the block.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to place crystals when behind blocks, measured from your eyes to the block.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> placement112 = sgPlace.add(new BoolSetting.Builder()
        .name("1.12-placement")
        .description("Uses 1.12 crystal placement.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SupportMode> support = sgPlace.add(new EnumSetting.Builder<SupportMode>()
        .name("support")
        .description("Places a support block in air if no other position have been found.")
        .defaultValue(SupportMode.Disabled)
        .build()
    );

    private final Setting<Integer> supportDelay = sgPlace.add(new IntSetting.Builder()
        .name("support-delay")
        .description("Delay in ticks after placing support block.")
        .defaultValue(1)
        .min(0)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    // Face place

    private final Setting<Boolean> facePlace = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place")
        .description("Will face-place when target is below a certain health or armor durability threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("The health the target has to be at to start face placing.")
        .defaultValue(8)
        .min(1)
        .sliderMin(1)
        .sliderMax(36)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Double> facePlaceDurability = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-durability")
        .description("The durability threshold percentage to be able to face-place.")
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .sliderMax(100)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Boolean> facePlaceArmor = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place-missing-armor")
        .description("Automatically starts face placing when a target misses a piece of armor.")
        .defaultValue(false)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Keybind> forceFacePlace = sgFacePlace.add(new KeybindSetting.Builder()
        .name("force-face-place")
        .description("Starts face place when this button is pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    // Break

    private final Setting<Boolean> doBreak = sgBreak.add(new BoolSetting.Builder()
        .name("break")
        .description("If the CA should break crystals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("The delay in ticks to wait to break a crystal after it's placed.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> smartDelay = sgBreak.add(new BoolSetting.Builder()
        .name("smart-delay")
        .description("Only breaks crystals when the target can receive damage.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Range in which to break crystals, measured from your eyes to the crystal's hitbox. Grim only allows 3.")
        .defaultValue(3)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to break crystals when behind blocks, measured from your eyes to the crystal's hitbox.")
        .defaultValue(3)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> onlyBreakOwn = sgBreak.add(new BoolSetting.Builder()
        .name("only-own")
        .description("Only breaks own crystals.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> breakAttempts = sgBreak.add(new IntSetting.Builder()
        .name("break-attempts")
        .description("How many times to hit a crystal before stopping to target it.")
        .defaultValue(2)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("ticks-existed")
        .description("Amount of ticks a crystal needs to have lived for it to be attacked by CrystalAura.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name("attack-frequency")
        .description("Maximum hits to do per second.")
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name("fast-break")
        .description("Ignores break delay and tries to break the crystal as soon as it's spawned in the world.")
        .defaultValue(true)
        .build()
    );

    // Pause

    public final Setting<PauseMode> pauseOnUse = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-use")
        .description("Which processes should be paused while using an item.")
        .defaultValue(PauseMode.Place)
        .build()
    );

    public final Setting<PauseMode> pauseOnMine = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-mine")
        .description("Which processes should be paused while mining a block.")
        .defaultValue(PauseMode.None)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Whether to pause if the server is not responding.")
        .defaultValue(true)
        .build()
    );

    public final Setting<List<Module>> pauseModules = sgPause.add(new ModuleListSetting.Builder()
        .name("pause-modules")
        .description("Pauses while any of the selected modules are active.")
        .defaultValue(BedAura.class)
        .build()
    );

    public final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name("pause-health")
        .description("Pauses when you go below a certain health.")
        .defaultValue(5)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    // Render

    public final Setting<SwingMode> swingMode = sgRender.add(new EnumSetting.Builder<SwingMode>()
        .name("swing-mode")
        .description("How to swing when placing and breaking. Grim cancels attacks that aren't followed by a swing packet.")
        .defaultValue(SwingMode.Both)
        .build()
    );

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("The mode to render in.")
        .defaultValue(RenderMode.Normal)
        .build()
    );

    private final Setting<Boolean> renderPlace = sgRender.add(new BoolSetting.Builder()
        .name("render-place")
        .description("Renders a block overlay over the block the crystals are being placed on.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> placeRenderTime = sgRender.add(new IntSetting.Builder()
        .name("place-time")
        .description("How long to render placements.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderPlace.get())
        .build()
    );

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("render-break")
        .description("Renders a block overlay over the block the crystals are broken on.")
        .defaultValue(false)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> breakRenderTime = sgRender.add(new IntSetting.Builder()
        .name("break-time")
        .description("How long to render breaking for.")
        .defaultValue(13)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderBreak.get())
        .build()
    );

    private final Setting<Integer> smoothness = sgRender.add(new IntSetting.Builder()
        .name("smoothness")
        .description("How smoothly the render should move around.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth)
        .build()
    );

    private final Setting<Double> height = sgRender.add(new DoubleSetting.Builder()
        .name("height")
        .description("How tall the gradient should be.")
        .defaultValue(0.7)
        .min(0)
        .sliderMax(1)
        .visible(() -> renderMode.get() == RenderMode.Gradient)
        .build()
    );

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("render-time")
        .description("How long to render placements.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth || renderMode.get() == RenderMode.Fading)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the block overlay.")
        .defaultValue(new SettingColor(255, 255, 255, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the block overlay.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<Boolean> renderDamageText = sgRender.add(new BoolSetting.Builder()
        .name("damage")
        .description("Renders crystal damage text in the block overlay.")
        .defaultValue(true)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> damageColor = sgRender.add(new ColorSetting.Builder()
        .name("damage-color")
        .description("The color of the damage text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    private final Setting<Double> damageTextScale = sgRender.add(new DoubleSetting.Builder()
        .name("damage-scale")
        .description("How big the damage text should be.")
        .defaultValue(1.25)
        .min(1)
        .sliderMax(4)
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    // Fields

    private Item mainItem, offItem;

    private int breakTimer, placeTimer, switchTimer, ticksPassed;
    private final List<LivingEntity> targets = new ArrayList<>();

    private final Vec3 vec3d = new Vec3(0, 0, 0);
    private final Vec3 playerEyePos = new Vec3(0, 0, 0);
    private final Vector3d vec3 = new Vector3d();
    private final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
    private final AABB box = new AABB(0, 0, 0, 0, 0, 0);

    private final Vec3 vec3dRayTraceEnd = new Vec3(0, 0, 0);
    private ClipContext clipContext;

    // How far inside a hitbox to aim, so the look ray still enters it after float rotation rounding
    private static final double AIM_INSET = 0.05;
    private final Vec3 breakAimPos = new Vec3(0, 0, 0);
    private final AABB rangeBox = new AABB(0, 0, 0, 0, 0, 0);

    private final IntSet placedCrystals = new IntOpenHashSet();
    private boolean placing;
    private int placingTimer;
    public int kaTimer;
    private final BlockPos.MutableBlockPos placingCrystalBlockPos = new BlockPos.MutableBlockPos();

    private final IntSet removed = new IntOpenHashSet();
    private final Int2IntMap attemptedBreaks = new Int2IntOpenHashMap();
    private final Int2IntMap waitingToExplode = new Int2IntOpenHashMap();
    private int attacks;

    private double serverYaw;

    private LivingEntity bestTarget;
    private double bestTargetDamage;
    private int bestTargetTimer;

    private boolean didRotateThisTick;
    private boolean isLastRotationPos;
    private final Vec3 lastRotationPos = new Vec3(0, 0, 0);
    private double lastYaw, lastPitch;
    private int lastRotationTimer;

    private int placeRenderTimer, breakRenderTimer;
    private final BlockPos.MutableBlockPos placeRenderPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos breakRenderPos = new BlockPos.MutableBlockPos();
    private AABB renderBoxOne, renderBoxTwo;

    private double renderDamage;

    public CrystalAura() {
        super(Categories.Combat, "crystal-aura", "Automatically places and attacks crystals.");
    }

    @Override
    public void onActivate() {
        breakTimer = 0;
        placeTimer = 0;
        ticksPassed = 0;

        clipContext = new ClipContext(new Vec3(0, 0, 0), new Vec3(0, 0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);

        placing = false;
        placingTimer = 0;
        kaTimer = 0;

        attacks = 0;

        serverYaw = mc.player.getYRot();

        didRotateThisTick = false;

        bestTargetDamage = 0;
        bestTargetTimer = 0;

        lastRotationTimer = getLastRotationStopDelay();

        placeRenderTimer = 0;
        breakRenderTimer = 0;
    }

    @Override
    public void onDeactivate() {
        targets.clear();

        placedCrystals.clear();

        attemptedBreaks.clear();
        waitingToExplode.clear();

        removed.clear();

        bestTarget = null;
    }

    private int getLastRotationStopDelay() {
        return Math.max(10, placeDelay.get() / 2 + breakDelay.get() / 2 + 10);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        // Update last rotation
        lastRotationTimer++;

        // Decrement placing timer
        if (placing) {
            if (placingTimer > 0) placingTimer--;
            else placing = false;
        }

        if (kaTimer > 0) kaTimer--;

        if (ticksPassed < 20) ticksPassed++;
        else {
            ticksPassed = 0;
            attacks = 0;
        }

        // Decrement best target timer
        if (bestTargetTimer > 0) bestTargetTimer--;
        bestTargetDamage = 0;

        // Decrement break, place and switch timers
        if (breakTimer > 0) breakTimer--;
        if (placeTimer > 0) placeTimer--;
        if (switchTimer > 0) switchTimer--;

        // Decrement render timers
        if (placeRenderTimer > 0) placeRenderTimer--;
        if (breakRenderTimer > 0) breakRenderTimer--;

        mainItem = mc.player.getMainHandItem().getItem();
        offItem = mc.player.getOffhandItem().getItem();

        // Update waiting to explode crystals and mark them as existing if reached threshold
        for (IntIterator it = waitingToExplode.keySet().iterator(); it.hasNext(); ) {
            int id = it.nextInt();
            int ticks = waitingToExplode.get(id);

            if (ticks > 3) {
                it.remove();
                removed.remove(id);
            } else {
                waitingToExplode.put(id, ticks + 1);
            }
        }

        updateEyePos();

        // Find targets, break and place
        findTargets();

        if (!targets.isEmpty()) {
            if (!didRotateThisTick) doBreak();
            if (!didRotateThisTick) doPlace();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST - 666)
    private void onPreTickLast(TickEvent.Pre event) {
        // Rotate to last rotation
        if (rotate.get() && lastRotationTimer < getLastRotationStopDelay() && !didRotateThisTick) {
            Rotations.rotate(isLastRotationPos ? Rotations.getYaw(lastRotationPos) : lastYaw, isLastRotationPos ? Rotations.getPitch(lastRotationPos) : lastPitch, -100, null);
        }
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        // Reset here instead of in pre tick, so a fast break rotation queued between ticks blocks the next tick's
        // rotations. Otherwise both get sent, the second one as an extra rotation packet.
        didRotateThisTick = false;
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystal)) return;

        if (placing && event.entity.blockPosition().equals(placingCrystalBlockPos)) {
            placing = false;
            placingTimer = 0;
            placedCrystals.add(event.entity.getId());
        }

        if (fastBreak.get() && !didRotateThisTick && attacks < attackFrequency.get()) {
            // The player has moved since the last tick
            updateEyePos();

            float damage = getBreakDamage(event.entity, true);
            if (damage > minDamage.get()) doBreak(event.entity);
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof EndCrystal) {
            placedCrystals.remove(event.entity.getId());
            removed.remove(event.entity.getId());
            waitingToExplode.remove(event.entity.getId());
        }
    }

    private void updateEyePos() {
        ((IVec3) playerEyePos).meteor$set(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
    }

    private void setRotation(boolean isPos, Vec3 pos, double yaw, double pitch) {
        didRotateThisTick = true;
        isLastRotationPos = isPos;

        if (isPos) ((IVec3) lastRotationPos).meteor$set(pos.x, pos.y, pos.z);
        else {
            lastYaw = yaw;
            lastPitch = pitch;
        }

        lastRotationTimer = 0;
    }

    // Break

    private void doBreak() {
        if (!doBreak.get() || breakTimer > 0 || switchTimer > 0 || attacks >= attackFrequency.get()) return;
        if (shouldPause(PauseMode.Break)) return;

        float bestDamage = 0;
        Entity crystal = null;

        // Find best crystal to break
        for (Entity entity : mc.level.entitiesForRendering()) {
            float damage = getBreakDamage(entity, true);

            if (damage > bestDamage) {
                bestDamage = damage;
                crystal = entity;
            }
        }

        // Break the crystal
        if (crystal != null) doBreak(crystal);
    }

    private float getBreakDamage(Entity entity, boolean checkCrystalAge) {
        if (!(entity instanceof EndCrystal)) return 0;

        // Check only break own
        if (onlyBreakOwn.get() && !placedCrystals.contains(entity.getId())) return 0;

        // Check if it should already be removed
        if (removed.contains(entity.getId())) return 0;

        // Check attempted breaks
        if (attemptedBreaks.get(entity.getId()) > breakAttempts.get()) return 0;

        // Check crystal age
        if (checkCrystalAge && entity.tickCount < ticksExisted.get()) return 0;

        // Check range
        if (!isInBreakRange(entity.getBoundingBox())) return 0;

        // Check damage to self and anti suicide
        blockPos.set(entity.blockPosition()).move(0, -1, 0);
        float selfDamage = DamageUtils.crystalDamage(mc.player, entity.position(), predictMovement.get(), blockPos);
        if (selfDamage > maxDamage.get() || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player)))
            return 0;

        // Check damage to targets and face place
        float damage = getDamageToTargets(entity.position(), blockPos, true, false);
        boolean shouldFacePlace = shouldFacePlace();
        double minimumDamage = shouldFacePlace ? Math.min(minDamage.get(), 1.5d) : minDamage.get();

        if (damage < minimumDamage) return 0f;

        return damage;
    }

    private void doBreak(Entity crystal) {
        // Anti weakness
        if (antiWeakness.get()) {
            MobEffectInstance weakness = mc.player.getEffect(MobEffects.WEAKNESS);
            MobEffectInstance strength = mc.player.getEffect(MobEffects.STRENGTH);

            // Check for strength
            if (weakness != null && (strength == null || strength.getAmplifier() <= weakness.getAmplifier())) {
                // Check if the item in your hand is already valid
                if (!isValidWeaknessItem(mc.player.getMainHandItem(), crystal)) {
                    // Find valid item to break with
                    if (!InvUtils.swap(InvUtils.findInHotbar(stack -> isValidWeaknessItem(stack, crystal)).slot(), false))
                        return;

                    switchTimer = 1;
                    return;
                }
            }
        }

        // Rotate and attack
        if (rotate.get()) {
            // Aim at the closest part of the hitbox, reach is measured along the look ray to where it enters the box
            Vec3 aimPos = getBreakAimPos(crystal.getBoundingBox());
            double yaw = Rotations.getYaw(aimPos);
            double pitch = Rotations.getPitch(aimPos);

            if (doYawSteps(yaw, pitch)) {
                setRotation(true, aimPos, 0, 0);
                Rotations.rotate(yaw, pitch, 50, () -> attackCrystal(crystal));

                breakTimer = breakDelay.get();
            }
        } else {
            attackCrystal(crystal);
            breakTimer = breakDelay.get();
        }
    }

    private boolean isValidWeaknessItem(ItemStack itemStack, Entity crystal) {
        return DamageUtils.getAttackDamage(mc.player, crystal, itemStack) > 0;
    }

    private void attackCrystal(Entity crystal) {
        // The player moved since this attack was planned, recheck reach from the position the server now has
        if (crystal.isRemoved() || !canHitNow(crystal.getBoundingBox())) return;

        // Attack
        mc.player.connection.send(ServerboundInteractPacket.createAttackPacket(crystal, mc.player.isShiftKeyDown()));
        swing(InteractionHand.MAIN_HAND);

        attacks++;

        // Update state
        removed.add(crystal.getId());
        attemptedBreaks.put(crystal.getId(), attemptedBreaks.get(crystal.getId()) + 1);
        waitingToExplode.put(crystal.getId(), 0);

        // Break render
        breakRenderPos.set(crystal.blockPosition().below());
        breakRenderTimer = breakRenderTime.get();
    }

    private boolean canHitNow(AABB crystalBox) {
        Vec3 eyePos = mc.player.getEyePosition();
        if (crystalBox.contains(eyePos)) return true;

        double range = breakRange.get();
        if (!rotate.get()) return crystalBox.distanceToSqr(eyePos) <= range * range;

        // Grim checks that the look ray of the last sent rotation enters the hitbox within reach
        Vec3 end = eyePos.add(Vec3.directionFromRotation(Rotations.serverPitch, Rotations.serverYaw).scale(range));
        return crystalBox.clip(eyePos, end).isPresent();
    }

    private void swing(InteractionHand hand) {
        switch (swingMode.get()) {
            // LocalPlayer.swing() already sends the packet
            case Both -> mc.player.swing(hand);
            case Client -> mc.player.swing(hand, false);
            case Packet -> mc.getConnection().send(new ServerboundSwingPacket(hand));
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundSetCarriedItemPacket) {
            switchTimer = switchDelay.get();
        }
    }

    // Place

    private void doPlace() {
        if (!doPlace.get() || placeTimer > 0) return;
        if (shouldPause(PauseMode.Place)) return;

        // Return if there are no crystals in hotbar or offhand
        if (!InvUtils.testInHotbar(Items.END_CRYSTAL)) return;

        // Return if there are no crystals in either hand and auto switch mode is none
        if (autoSwitch.get() != AutoSwitchMode.None) {
            if (noGapSwitch.get() && autoSwitch.get() == AutoSwitchMode.Normal && offItem != Items.END_CRYSTAL) {
                if (mainItem == Items.ENCHANTED_GOLDEN_APPLE
                    || offItem == Items.ENCHANTED_GOLDEN_APPLE
                    || mainItem == Items.GOLDEN_APPLE
                    || offItem == Items.GOLDEN_APPLE) return;
            }
            if (noBowSwitch.get() && (mainItem == Items.BOW || offItem == Items.BOW)) return;
        } else if (mainItem != Items.END_CRYSTAL && offItem != Items.END_CRYSTAL) return;

        // Check for multiplace
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (getBreakDamage(entity, false) > 0) return;
        }

        // Setup variables
        AtomicDouble bestDamage = new AtomicDouble(0);
        AtomicReference<BlockPos.MutableBlockPos> bestBlockPos = new AtomicReference<>(new BlockPos.MutableBlockPos());
        AtomicBoolean isSupport = new AtomicBoolean(support.get() != SupportMode.Disabled);

        // Find best position to place the crystal on
        BlockIterator.register((int) Math.ceil(placeRange.get()), (int) Math.ceil(placeRange.get()), (bp, blockState) -> {
            // Check if its bedrock or obsidian and return if isSupport is false
            boolean hasBlock = blockState.is(Blocks.BEDROCK) || blockState.is(Blocks.OBSIDIAN);
            if (!hasBlock && (!isSupport.get() || !blockState.canBeReplaced())) return;

            // Check if there is air on top
            blockPos.set(bp.getX(), bp.getY() + 1, bp.getZ());
            if (!mc.level.getBlockState(blockPos).isAir()) return;

            if (placement112.get()) {
                blockPos.move(0, 1, 0);
                if (!mc.level.getBlockState(blockPos).isAir()) return;
            }

            // Check range
            if (!isInPlaceRange(bp)) return;

            // A crystal that can't be broken from here is wasted, and break reach is shorter than place reach
            if (doBreak.get() && !isInBreakRange(getCrystalBox(bp))) return;

            ((IVec3) vec3d).meteor$set(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);

            // Check damage to self and anti suicide
            float selfDamage = DamageUtils.crystalDamage(mc.player, vec3d, predictMovement.get(), bp);
            if (selfDamage > maxDamage.get() || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player)))
                return;

            // Check damage to targets and face place
            float damage = getDamageToTargets(vec3d, bp, false, !hasBlock && support.get() == SupportMode.Fast);

            boolean shouldFacePlace = shouldFacePlace();
            double minimumDamage = Math.min(minDamage.get(), shouldFacePlace ? 1.5 : minDamage.get());

            if (damage < minimumDamage) return;

            // Check if it can be placed
            double x = bp.getX();
            double y = bp.getY() + 1;
            double z = bp.getZ();
            ((IAABB) box).meteor$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);

            if (intersectsWithEntities(box)) return;

            // Compare damage
            if (damage > bestDamage.get() || (isSupport.get() && hasBlock)) {
                bestDamage.set(damage);
                bestBlockPos.get().set(bp);
            }

            if (hasBlock) isSupport.set(false);
        });

        // Place the crystal
        BlockIterator.after(() -> {
            if (bestDamage.get() == 0) return;

            BlockHitResult result = getPlaceInfo(bestBlockPos.get());

            if (rotate.get()) {
                // Look at exactly where we click, the hit position is on the clicked face
                double yaw = Rotations.getYaw(result.getLocation());
                double pitch = Rotations.getPitch(result.getLocation());

                if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                    setRotation(true, result.getLocation(), 0, 0);
                    Rotations.rotate(yaw, pitch, 50, () -> placeCrystal(result, bestDamage.get(), isSupport.get() ? bestBlockPos.get() : null));

                    placeTimer += placeDelay.get();
                }
            } else {
                placeCrystal(result, bestDamage.get(), isSupport.get() ? bestBlockPos.get() : null);
                placeTimer += placeDelay.get();
            }
        });
    }

    private BlockHitResult getPlaceInfo(BlockPos blockPos) {
        BlockHitResult result = getVisiblePlaceInfo(blockPos);
        if (result != null) return result;

        // Behind walls, click the face pointing towards the eyes, anticheats reject faces that point away
        Direction side = Direction.getApproximateNearest(
            playerEyePos.x - (blockPos.getX() + 0.5),
            playerEyePos.y - (blockPos.getY() + 0.5),
            playerEyePos.z - (blockPos.getZ() + 0.5)
        );

        // The server rejects hit positions more than a block away from the block
        Vec3 hitPos = new Vec3(
            blockPos.getX() + 0.5 + side.getStepX() * 0.5,
            blockPos.getY() + 0.5 + side.getStepY() * 0.5,
            blockPos.getZ() + 0.5 + side.getStepZ() * 0.5
        );

        return new BlockHitResult(hitPos, side, blockPos, false);
    }

    /**
     * Returns a hit on the center of a face of the block that points towards the eyes and can be seen, or null if
     * there is none.
     */
    private BlockHitResult getVisiblePlaceInfo(BlockPos blockPos) {
        for (Direction side : Direction.values()) {
            ((IVec3) vec3dRayTraceEnd).meteor$set(
                blockPos.getX() + 0.5 + side.getStepX() * 0.5,
                blockPos.getY() + 0.5 + side.getStepY() * 0.5,
                blockPos.getZ() + 0.5 + side.getStepZ() * 0.5
            );

            // Skip faces pointing away from the eyes
            if ((playerEyePos.x - vec3dRayTraceEnd.x) * side.getStepX()
                + (playerEyePos.y - vec3dRayTraceEnd.y) * side.getStepY()
                + (playerEyePos.z - vec3dRayTraceEnd.z) * side.getStepZ() <= 0) continue;

            ((IClipContext) clipContext).meteor$set(playerEyePos, vec3dRayTraceEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            BlockHitResult result = mc.level.clip(clipContext);

            if (result != null && result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(blockPos)) {
                return result;
            }
        }

        return null;
    }

    private boolean canPlaceNow(BlockPos blockPos) {
        Vec3 eyePos = mc.player.getEyePosition();
        double range = placeRange.get();

        ((IAABB) rangeBox).meteor$set(blockPos);
        if (rangeBox.distanceToSqr(eyePos) > range * range) return false;
        if (!rotate.get() || rangeBox.contains(eyePos)) return true;

        // Grim checks that the look ray of the last sent rotation hits the clicked block
        Vec3 end = eyePos.add(Vec3.directionFromRotation(Rotations.serverPitch, Rotations.serverYaw).scale(range));
        return rangeBox.clip(eyePos, end).isPresent();
    }

    private void placeCrystal(BlockHitResult result, double damage, BlockPos supportBlock) {
        // The player moved since this place was planned, recheck reach from the position the server now has
        if (!canPlaceNow(result.getBlockPos())) return;

        // Switch
        Item targetItem = supportBlock == null ? Items.END_CRYSTAL : Items.OBSIDIAN;

        FindItemResult item = InvUtils.findInHotbar(targetItem);
        if (!item.found()) return;

        int prevSlot = mc.player.getInventory().getSelectedSlot();

        if (autoSwitch.get() != AutoSwitchMode.None && !item.isOffhand()) InvUtils.swap(item.slot(), false);

        InteractionHand hand = item.getHand();
        if (hand == null) return;

        // Place
        if (supportBlock == null) {
            // Place crystal
            mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundUseItemOnPacket(hand, result, sequence));

            swing(hand);

            placing = true;
            placingTimer = 4;
            kaTimer = 8;
            placingCrystalBlockPos.set(result.getBlockPos()).move(0, 1, 0);

            placeRenderPos.set(result.getBlockPos());
            renderDamage = damage;

            if (renderMode.get() == RenderMode.Normal) {
                placeRenderTimer = placeRenderTime.get();
            } else {
                placeRenderTimer = renderTime.get();
                if (renderMode.get() == RenderMode.Fading) {
                    RenderUtils.renderTickingBlock(
                        placeRenderPos, sideColor.get(),
                        lineColor.get(), shapeMode.get(),
                        0, renderTime.get(), true,
                        false
                    );
                }
            }
        } else {
            // Place support block
            BlockUtils.place(supportBlock, item, false, 0, swingMode.get().client(), true, false);
            placeTimer += supportDelay.get();

            if (supportDelay.get() == 0) placeCrystal(result, damage, null);
        }

        // Switch back
        if (autoSwitch.get() == AutoSwitchMode.Silent) InvUtils.swap(prevSlot, false);
    }

    // Yaw steps

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundMovePlayerPacket packet) {
            serverYaw = packet.getYRot((float) serverYaw);
        }
    }

    public boolean doYawSteps(double targetYaw, double targetPitch) {
        targetYaw = Mth.wrapDegrees(targetYaw) + 180;
        double serverYaw = Mth.wrapDegrees(this.serverYaw) + 180;

        if (distanceBetweenAngles(serverYaw, targetYaw) <= yawSteps.get()) return true;

        double delta = Math.abs(targetYaw - serverYaw);
        double yaw = this.serverYaw;

        if (serverYaw < targetYaw) {
            if (delta < 180) yaw += yawSteps.get();
            else yaw -= yawSteps.get();
        } else {
            if (delta < 180) yaw -= yawSteps.get();
            else yaw += yawSteps.get();
        }

        setRotation(false, null, yaw, targetPitch);
        Rotations.rotate(yaw, targetPitch, -100, null); // Priority -100 so it sends the packet as the last one, im pretty sure it doesn't matte but idc
        return false;
    }

    private static double distanceBetweenAngles(double alpha, double beta) {
        double phi = Math.abs(beta - alpha) % 360;
        return phi > 180 ? 360 - phi : phi;
    }

    // Face place

    private boolean shouldFacePlace() {
        if (!facePlace.get()) return false;

        if (forceFacePlace.get().isPressed()) return true;

        // Checks if the provided crystal position should face place to any target
        for (LivingEntity target : targets) {
            if (EntityUtils.getTotalHealth(target) <= facePlaceHealth.get()) return true;

            for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR) {
                ItemStack itemStack = target.getItemBySlot(slot);

                if (itemStack == null || itemStack.isEmpty()) {
                    if (facePlaceArmor.get()) return true;
                } else {
                    if ((double) (itemStack.getMaxDamage() - itemStack.getDamageValue()) / itemStack.getMaxDamage() * 100 <= facePlaceDurability.get())
                        return true;
                }
            }
        }

        return false;
    }

    // Others

    private boolean shouldPause(PauseMode process) {
        if (mc.player.isUsingItem() || mc.options.keyUse.isDown()) {
            if (pauseOnUse.get().matches(process)) return true;
        }

        if (pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1.0f) return true;
        for (Module module : pauseModules.get()) if (module.isActive()) return true;
        if (pauseOnMine.get().matches(process) && mc.gameMode.isDestroying()) return true;
        return (EntityUtils.getTotalHealth(mc.player) <= pauseHealth.get());
    }

    // Range checks measure from the eyes to the closest point of the block or hitbox, like the server and Grim do

    private boolean isInPlaceRange(BlockPos blockPos) {
        ((IAABB) rangeBox).meteor$set(blockPos);
        double distance = rangeBox.distanceToSqr(playerEyePos);

        if (distance > placeRange.get() * placeRange.get()) return false;
        return distance <= placeWallsRange.get() * placeWallsRange.get() || getVisiblePlaceInfo(blockPos) != null;
    }

    private boolean isInBreakRange(AABB crystalBox) {
        Vec3 aimPos = getBreakAimPos(crystalBox);
        double distance = playerEyePos.distanceToSqr(aimPos);

        if (distance > breakRange.get() * breakRange.get()) return false;
        if (distance <= breakWallsRange.get() * breakWallsRange.get()) return true;

        // Behind wall if a block is in the way
        ((IClipContext) clipContext).meteor$set(playerEyePos, aimPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        return mc.level.clip(clipContext).getType() == HitResult.Type.MISS;
    }

    /**
     * The point of the hitbox closest to the eyes, moved slightly inside it.
     */
    private Vec3 getBreakAimPos(AABB crystalBox) {
        ((IVec3) breakAimPos).meteor$set(
            Mth.clamp(playerEyePos.x, crystalBox.minX + AIM_INSET, crystalBox.maxX - AIM_INSET),
            Mth.clamp(playerEyePos.y, crystalBox.minY + AIM_INSET, crystalBox.maxY - AIM_INSET),
            Mth.clamp(playerEyePos.z, crystalBox.minZ + AIM_INSET, crystalBox.maxZ - AIM_INSET)
        );

        return breakAimPos;
    }

    /**
     * The hitbox a crystal placed on top of this block would have.
     */
    private AABB getCrystalBox(BlockPos blockPos) {
        double x = blockPos.getX() + 0.5;
        double y = blockPos.getY() + 1;
        double z = blockPos.getZ() + 0.5;

        double radius = EntityType.END_CRYSTAL.getWidth() / 2;
        ((IAABB) rangeBox).meteor$set(x - radius, y, z - radius, x + radius, y + EntityType.END_CRYSTAL.getHeight(), z + radius);
        return rangeBox;
    }

    private LivingEntity getNearestTarget() {
        LivingEntity nearestTarget = null;
        double nearestDistance = Double.MAX_VALUE;

        for (LivingEntity target : targets) {
            double distance = PlayerUtils.squaredDistanceTo(target);

            if (distance < nearestDistance) {
                nearestTarget = target;
                nearestDistance = distance;
            }
        }

        return nearestTarget;
    }

    private float getDamageToTargets(Vec3 vec3d, BlockPos obsidianPos, boolean breaking, boolean fast) {
        float damage = 0;

        if (fast) {
            LivingEntity target = getNearestTarget();
            if (!(smartDelay.get() && breaking && target.hurtTime > 0))
                damage = DamageUtils.crystalDamage(target, vec3d, predictMovement.get(), obsidianPos);
        } else {
            for (LivingEntity target : targets) {
                if (smartDelay.get() && breaking && target.hurtTime > 0) continue;

                float dmg = DamageUtils.crystalDamage(target, vec3d, predictMovement.get(), obsidianPos);

                // Update best target
                if (dmg > bestTargetDamage) {
                    bestTarget = target;
                    bestTargetDamage = dmg;
                    bestTargetTimer = 10;
                }

                damage += dmg;
            }
        }

        return damage;
    }

    @Override
    public String getInfoString() {
        return bestTarget != null && bestTargetTimer > 0 ? EntityUtils.getName(bestTarget) : null;
    }

    private void findTargets() {
        targets.clear();

        // Living Entities
        for (Entity entity : mc.level.entitiesForRendering()) {
            // Ignore non-living
            if (!(entity instanceof LivingEntity livingEntity)) continue;
            if (!livingEntity.isAlive()) continue;

            // Withers can't be damaged while charging up after being built
            if (livingEntity instanceof WitherBoss wither && wither.getInvulnerableTicks() > 0) continue;

            // Player
            if (livingEntity instanceof Player player) {
                if (player.getAbilities().instabuild || livingEntity == mc.player) continue;
                if (!Friends.get().shouldAttack(player)) continue;

                if (ignoreNakeds.get()) {
                    if (player.getOffhandItem().isEmpty()
                        && player.getMainHandItem().isEmpty()
                        && player.getItemBySlot(EquipmentSlot.FEET).isEmpty()
                        && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                        && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                    ) continue;
                }
            }

            // Animals, water animals, monsters, bats, misc
            if (!(entities.get().contains(livingEntity.getType()))) continue;

            // Close enough to damage
            if (livingEntity.distanceToSqr(mc.player) > targetRange.get() * targetRange.get()) continue;

            targets.add(livingEntity);
        }
    }

    private boolean intersectsWithEntities(AABB box) {
        return EntityUtils.intersectsWithEntity(box, entity -> !entity.isSpectator() && !removed.contains(entity.getId()));
    }

    // Rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (renderMode.get() == RenderMode.None) return;

        switch (renderMode.get()) {
            case Normal -> {
                if (renderPlace.get() && placeRenderTimer > 0) {
                    event.renderer.box(placeRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
                if (renderBreak.get() && breakRenderTimer > 0) {
                    event.renderer.box(breakRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }

            case Smooth -> {
                if (placeRenderTimer <= 0) return;

                if (renderBoxOne == null) renderBoxOne = new AABB(placeRenderPos);
                if (renderBoxTwo == null) renderBoxTwo = new AABB(placeRenderPos);
                else ((IAABB) renderBoxTwo).meteor$set(placeRenderPos);

                double offsetX = (renderBoxTwo.minX - renderBoxOne.minX) / smoothness.get();
                double offsetY = (renderBoxTwo.minY - renderBoxOne.minY) / smoothness.get();
                double offsetZ = (renderBoxTwo.minZ - renderBoxOne.minZ) / smoothness.get();

                ((IAABB) renderBoxOne).meteor$set(
                    renderBoxOne.minX + offsetX,
                    renderBoxOne.minY + offsetY,
                    renderBoxOne.minZ + offsetZ,
                    renderBoxOne.maxX + offsetX,
                    renderBoxOne.maxY + offsetY,
                    renderBoxOne.maxZ + offsetZ
                );

                event.renderer.box(renderBoxOne, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }

            case Gradient -> {
                if (placeRenderTimer <= 0) return;

                Color bottom = new Color(0, 0, 0, 0);

                int x = placeRenderPos.getX();
                int y = placeRenderPos.getY() + 1;
                int z = placeRenderPos.getZ();

                if (shapeMode.get().sides()) {
                    event.renderer.quadHorizontal(x, y, z, x + 1, z + 1, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z, x + 1, y - height.get(), z, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z, x, y - height.get(), z + 1, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x + 1, y, z, x + 1, y - height.get(), z + 1, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z + 1, x + 1, y - height.get(), z + 1, bottom, sideColor.get());
                }

                if (shapeMode.get().lines()) {
                    event.renderer.line(x, y, z, x + 1, y, z, lineColor.get());
                    event.renderer.line(x, y, z, x, y, z + 1, lineColor.get());
                    event.renderer.line(x + 1, y, z, x + 1, y, z + 1, lineColor.get());
                    event.renderer.line(x, y, z + 1, x + 1, y, z + 1, lineColor.get());

                    event.renderer.line(x, y, z, x, y - height.get(), z, lineColor.get(), bottom);
                    event.renderer.line(x + 1, y, z, x + 1, y - height.get(), z, lineColor.get(), bottom);
                    event.renderer.line(x, y, z + 1, x, y - height.get(), z + 1, lineColor.get(), bottom);
                    event.renderer.line(x + 1, y, z + 1, x + 1, y - height.get(), z + 1, lineColor.get(), bottom);
                }
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (renderMode.get() == RenderMode.None || !renderDamageText.get()) return;
        if (placeRenderTimer <= 0 && breakRenderTimer <= 0) return;

        if (renderMode.get() == RenderMode.Smooth) {
            if (renderBoxOne == null) return;
            vec3.set(renderBoxOne.minX + 0.5, renderBoxOne.minY + 0.5, renderBoxOne.minZ + 0.5);
        } else vec3.set(placeRenderPos.getX() + 0.5, placeRenderPos.getY() + 0.5, placeRenderPos.getZ() + 0.5);

        if (NametagUtils.to2D(vec3, damageTextScale.get())) {
            NametagUtils.begin(vec3);
            TextRenderer.get().begin(1, false, true);

            String text = String.format("%.1f", renderDamage);
            double w = TextRenderer.get().getWidth(text) / 2;
            TextRenderer.get().render(text, -w, 0, damageColor.get(), true);

            TextRenderer.get().end();
            NametagUtils.end();
        }
    }

    public enum YawStepMode {
        Break,
        All,
    }

    public enum AutoSwitchMode {
        Normal,
        Silent,
        None
    }

    public enum SupportMode {
        Disabled,
        Accurate,
        Fast
    }

    public enum PauseMode {
        Both,
        Place,
        Break,
        None;

        public boolean matches(PauseMode process) {
            return this == process || this == PauseMode.Both;
        }
    }

    public enum SwingMode {
        Both,
        Packet,
        Client,
        None;

        public boolean packet() {
            return this == Packet || this == Both;
        }

        public boolean client() {
            return this == Client || this == Both;
        }
    }

    public enum RenderMode {
        Normal,
        Smooth,
        Fading,
        Gradient,
        None
    }
}
