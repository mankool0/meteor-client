/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world.printer;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientLevelAccessor;
import meteordevelopment.meteorclient.mixin.LocalPlayerAccessor;
import meteordevelopment.meteorclient.mixin.MultiPlayerGameModeAccessor;
import meteordevelopment.meteorclient.mixininterface.IMultiPlayerGameMode;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.PendingPlaces;
import meteordevelopment.meteorclient.utils.world.PlaceRateLimiter;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Prints the schematic Litematica renders.
 * <p>
 * Built on dino-printer by dinosaurwizard (CC0): the placement simulation, interactions, fluids, sorting, inventory
 * handling and pauses are its. The pacing is mankool0/baritone's printer's:
 * <ul>
 *     <li>Clicks go out at the start of the tick, before the movement packet, with the rotation the server already
 *     has, then the next rotation is aimed at the next block. A place never waits a tick for its own rotation, and no
 *     rotation goes out in an extra packet, which Grim sets the player back for.</li>
 *     <li>After a place the ray of that same rotation runs on into the block just predicted, so a few blocks that lie
 *     along it go out in one tick from one hotbar slot.</li>
 *     <li>Past one a tick, places are paced under the server's spam limit on uses (9 per 300 ms on Paper, about 30 a
 *     second), and placing drops back to one a tick when the server turns the extra ones down.</li>
 *     <li>A hotbar stack whose last items are still on their way to the server counts as empty, and clicks, swings,
 *     slot switches and sneaks are kept under a per tick packet budget.</li>
 *     <li>Reach and clicked faces are checked the way Grim's FarPlace and PositionPlace check them, from now and from
 *     where the player will be next tick.</li>
 * </ul>
 * Blocks that only an item used on another block makes, like a filled cauldron or a potted plant, are placed as that
 * block and then finished with the item, see {@link ItemUses}.
 */
public class Printer extends Module {
    public static final boolean LITEMATICA = FabricLoader.getInstance().isModLoaded("litematica");

    private static final int ROTATION_PRIORITY = 0;
    // a use and its swing, which every place sends
    private static final int USE_PACKETS = 2;
    // most clicks one tick's ray follows on from one rotation
    private static final int MAX_RAY_CLICKS = 8;
    // ticks between counts of what the schematic needs nearby
    private static final int DEMAND_INTERVAL = 5;
    // what counts in reach is worth this many of what's only coming up
    private static final int IN_REACH_WEIGHT = 4;
    // blocks a tick the player has to move at for the hotbar to be planned for what's ahead
    private static final double MOVING_SPEED = 0.03;
    // a last hotbar stack this low gets the next one moved in behind it
    private static final int RESTOCK_BELOW = 32;
    // places made without rotating that the server undid in a row before the printer rotates for a while instead
    private static final int NO_ROTATE_MAX_STREAK = 20;
    private static final int NO_ROTATE_RETRY_TICKS = 600;
    // a click on a block the server hasn't acked in this long never got there
    private static final int INTERACT_LOST_TICKS = 40;
    // how long after placing or clicking a sign its edit screen may still open
    private static final long SIGN_SCREEN_MS = 5000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgPause = settings.createGroup("Pause");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    public final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Max distance to the clicked block, measured like Grim does.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    public final Setting<Boolean> wallPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("wall-place")
        .description("Allow placement through walls. Turn off to only click points you can see.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow placement in the air. Grim cancels every one of these.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> sneakPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-place")
        .description("Sneaks to place against blocks that would otherwise open or toggle, like chests or trapdoors.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at each block it places. Needed for servers that check rotations, like Grim.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> strictFaces = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-faces")
        .description("Only clicks block faces that point towards you, which Grim's PositionPlace check requires.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SortAlgorithm> firstAlgorithm = sgGeneral.add(new EnumSetting.Builder<SortAlgorithm>()
        .name("first-sorting-mode")
        .description("The blocks you want to place first.")
        .defaultValue(SortAlgorithm.Nearest)
        .build()
    );

    private final Setting<SortingSecond> secondAlgorithm = sgGeneral.add(new EnumSetting.Builder<SortingSecond>()
        .name("second-sorting-mode")
        .description("Second pass of sorting, e.g. place higher blocks first and of those the closest to you.")
        .defaultValue(SortingSecond.None)
        .visible(() -> firstAlgorithm.get().applySecondSorting)
        .build()
    );

    // Speed

    private final Setting<Integer> placeDelay = sgSpeed.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The tick delay between placing blocks.")
        .defaultValue(0)
        .range(0, 10)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgSpeed.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most blocks per tick. Above 1 it's paced to the server's limit of about 30 a second.")
        .defaultValue(2)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> packetsPerTick = sgSpeed.add(new IntSetting.Builder()
        .name("packets-per-tick")
        .description("Most click, swing, slot and sneak packets per tick, so the server doesn't kick you.")
        .defaultValue(7)
        .min(2)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> placeRetryDelay = sgSpeed.add(new IntSetting.Builder()
        .name("place-retry-delay")
        .description("Ticks before placing at a position again after a place there didn't stick.")
        .defaultValue(20)
        .min(0)
        .sliderMax(100)
        .build()
    );

    // Advanced

    public final Setting<Boolean> rotationPlace = sgAdvanced.add(new BoolSetting.Builder()
        .name("rotation-place")
        .description("Respect block rotation.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> strictRotation = sgAdvanced.add(new BoolSetting.Builder()
        .name("strict-rotation")
        .description("Only place rotatable blocks with a rotation that looks at them, as a player could.")
        .defaultValue(false)
        .visible(rotationPlace::get)
        .build()
    );

    public final Setting<Boolean> halfBlocks = sgAdvanced.add(new BoolSetting.Builder()
        .name("half-blocks")
        .description("Respect block half. Necessary for properly placing slabs, stairs and trapdoors.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> incrementalStates = sgAdvanced.add(new BoolSetting.Builder()
        .name("incremental-states")
        .description("Respect states that need multiple placements. Necessary for double slabs, candles, vines and snow layers.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> miscStates = sgAdvanced.add(new BoolSetting.Builder()
        .name("misc-states")
        .description("Respect miscellaneous states. Blocks like doors, beds and lanterns are affected.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> fluids = sgAdvanced.add(new BoolSetting.Builder()
        .name("fluids")
        .description("Allow placement of fluids using their buckets.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> exitSigns = sgAdvanced.add(new BoolSetting.Builder()
        .name("exit-signs")
        .description("Closes sign editing screens the printer opens. Stops Litematica's sign text from being filled in.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> interact = sgAdvanced.add(new BoolSetting.Builder()
        .name("interact")
        .description("Right clicks blocks to match their state to the schematic. Useful for note blocks, redstone components, trapdoors, etc.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> useItems = sgAdvanced.add(new BoolSetting.Builder()
        .name("use-items")
        .description("Finishes blocks with items, like water bottles on cauldrons or plants in pots.")
        .defaultValue(true)
        .build()
    );

    // Inventory

    private final Setting<Boolean> autoSwitch = sgInventory.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to placeable blocks in your hotbar automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgInventory.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches back to your previous slot once the printer has nothing to place.")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> allowInventory = sgInventory.add(new BoolSetting.Builder()
        .name("allow-inventory")
        .description("Allows blocks to be moved into your hotbar from your inventory.")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> stationaryMove = sgInventory.add(new BoolSetting.Builder()
        .name("stationary-move")
        .description("Only moves blocks from your inventory while standing still. Required for certain anticheats, like Grim.")
        .defaultValue(false)
        .visible(() -> autoSwitch.get() && allowInventory.get())
        .build()
    );

    private final Setting<Boolean> hotbarPriority = sgInventory.add(new BoolSetting.Builder()
        .name("hotbar-priority")
        .description("Blocks in your hotbar get placement priority over blocks inside your inventory.")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() && allowInventory.get())
        .build()
    );

    private final Setting<Boolean> restockEarly = sgInventory.add(new BoolSetting.Builder()
        .name("restock-early")
        .description("Moves in the next stack of a block before the last one runs out.")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() && allowInventory.get())
        .build()
    );

    private final Setting<Boolean> planHotbar = sgInventory.add(new BoolSetting.Builder()
        .name("plan-hotbar")
        .description("Moves blocks needed nearby into the hotbar before they're needed.")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() && allowInventory.get())
        .build()
    );

    private final Setting<Integer> lookahead = sgInventory.add(new IntSetting.Builder()
        .name("lookahead")
        .description("How far ahead of where you're moving blocks count as needed soon. Standing still, only your reach.")
        .defaultValue(6)
        .range(0, 16)
        .visible(() -> autoSwitch.get() && allowInventory.get() && (restockEarly.get() || planHotbar.get()))
        .build()
    );

    private final Setting<Integer> movesPerTick = sgInventory.add(new IntSetting.Builder()
        .name("moves-per-tick")
        .description("Most stacks moved into the hotbar ahead of time in a tick.")
        .defaultValue(2)
        .range(1, 9)
        .visible(() -> autoSwitch.get() && allowInventory.get() && (restockEarly.get() || planHotbar.get()))
        .build()
    );

    private final Setting<Integer> slotLockTicks = sgInventory.add(new IntSetting.Builder()
        .name("slot-lock-ticks")
        .description("Fewest ticks a slot is left alone after a move. Held longer on a high ping, until the server has it.")
        .defaultValue(5)
        .min(0)
        .sliderMax(20)
        .visible(() -> autoSwitch.get() && allowInventory.get())
        .build()
    );

    // Pause

    private final Setting<Boolean> pauseOnUse = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Pauses while using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnMine = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-mine")
        .description("Pauses while mining blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnKillAura = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-kill-aura")
        .description("Pauses while kill aura attacks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses when the server stops responding.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Render your hand swinging when placing blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders block placements.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> fadeTime = sgRender.add(new IntSetting.Builder()
        .name("fade-time")
        .description("Time for the rendering to fade, in ticks.")
        .defaultValue(8)
        .min(0)
        .sliderMax(20)
        .visible(render::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color to render the placement as.")
        .defaultValue(new SettingColor(177, 236, 248, 100))
        .visible(() -> render.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color to render the placement as.")
        .defaultValue(new SettingColor(230, 250, 255))
        .visible(() -> render.get() && shapeMode.get().lines())
        .build()
    );

    private int placeTimer;
    private int pendingInventorySlot = -1;
    private int pendingHotbarSlot = -1;
    // signs placed or clicked, with when, whose edit screen the server opens a round trip later
    private final Map<Long, Long> signClicks = new ConcurrentHashMap<>();
    // positions placed at, with the ticks left before they may be placed at again
    private final Long2IntOpenHashMap cachedPositions = new Long2IntOpenHashMap();
    // clicks on blocks waiting on the server, by position
    private final Long2ObjectOpenHashMap<SentInteract> sentInteracts = new Long2ObjectOpenHashMap<>();
    private final List<BlockPrint> blockPrints = new ArrayList<>();
    private final List<BlockInteract> blockInteracts = new ArrayList<>();
    // whether there's anything to place each block with this tick, for the scan
    private final Object2BooleanOpenHashMap<Item> placeable = new Object2BooleanOpenHashMap<>();
    private final List<PlacedFade> placedFades = new ArrayList<>();
    private final List<ItemStack> mainInventory = new ArrayList<>(SlotUtils.MAIN_END + 1);
    private final List<PrintSlot> printSlots = createPrintSlots();
    // how much each item is needed around the player and just ahead, weighted towards what's in reach
    private final Object2IntOpenHashMap<Item> demand = new Object2IntOpenHashMap<>();
    private int demandTimer;
    // the block last placed from each hotbar slot, where a restock of it goes back to
    private final Item[] placedFrom = new Item[9];
    // this tick's ticks a slot is left alone after a move
    private int lockTicks;

    // the click aimed at last tick, made with its stored hit if the server got exactly the rotation it was aimed with
    @Nullable
    private BlockPrint plan;
    @Nullable
    private BlockInteract interactPlan;
    private float interactPlanYaw, interactPlanPitch;
    private int blocksPerTickSeen = 1;

    // this tick
    private int actionsThisTick;
    private int usesLeft;
    private int usesThisTick;
    // the hotbar slot this tick's uses go out from; vanilla can't switch slots after a use in the same tick
    private int tickSlot;
    @Nullable
    private Boolean sneakOverride;
    // the slot to go back to once the printer is idle, and the one it last selected
    private int restoreSlot = -1;
    private int selectedByPrinter = -1;

    // places made without rotating, to see whether the server kept them once it answered
    private final List<PlaceCheck> noRotateChecks = new ArrayList<>();
    private int noRotateStreak;
    // ticks left rotating because the server kept undoing places made without
    private int noRotateSuppressedTicks;

    // places of the last 20 ticks, for the info string
    private final int[] placeHistory = new int[20];
    private int placeHistoryIndex;

    public Printer() {
        super(Categories.World, "printer", "Prints the schematic Litematica renders. Based on dino-printer by dinosaurwizard.");
    }

    private List<PrintSlot> createPrintSlots() {
        List<PrintSlot> slots = new ArrayList<>(SlotUtils.MAIN_END + 1);
        for (int i = 0; i <= SlotUtils.MAIN_END; i++) slots.add(new PrintSlot(i));
        return slots;
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        restoreSneak();
        restoreSlot();
        reset();
    }

    private void reset() {
        placeTimer = 0;
        pendingInventorySlot = -1;
        pendingHotbarSlot = -1;
        signClicks.clear();
        cachedPositions.clear();
        sentInteracts.clear();
        blockPrints.clear();
        blockInteracts.clear();
        placedFades.clear();
        mainInventory.clear();
        plan = null;
        interactPlan = null;
        sneakOverride = null;
        restoreSlot = -1;
        selectedByPrinter = -1;
        Arrays.fill(placeHistory, 0);
        demand.clear();
        demandTimer = 0;
        Arrays.fill(placedFrom, null);
        noRotateChecks.clear();
        noRotateStreak = 0;
        noRotateSuppressedTicks = 0;

        for (PrintSlot slot : printSlots) slot.expire();
    }

    @Override
    public String getInfoString() {
        int placed = 0;
        for (int count : placeHistory) placed += count;
        return placed + "/s";
    }

    /**
     * @return what to place for the schematic's block: itself, or the base an item use turns into it
     */
    BlockState placeStateFor(BlockState required) {
        if (!useItems.get()) return required;

        // farmland, dirt paths and waxed copper only need a base without their own item
        BlockState fallback = ItemUses.base(required, false);
        if (fallback == null) return required;

        BlockState base = ItemUses.base(required, true);
        if (base != null) return base;

        Item own = BlockPrint.itemFor(required);
        return InvUtils.find(stack -> stack.getItem() == own).found() ? required : fallback;
    }

    boolean isCached(BlockPos pos) {
        return cachedPositions.containsKey(pos.asLong());
    }

    /**
     * @return whether clicks are rotated this tick: rotate is on, or the server kept undoing places made without
     */
    public boolean isRotating() {
        return rotate.get() || noRotateSuppressedTicks > 0;
    }

    // Tick

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onTickStart(TickEvent.Pre event) {
        actionsThisTick = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        placeHistoryIndex = (placeHistoryIndex + 1) % placeHistory.length;
        placeHistory[placeHistoryIndex] = 0;

        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        Level schematic = SchematicAccess.world();
        if (schematic == null) {
            idle();
            return;
        }

        PendingPlaces pending = PendingPlaces.of(mc.level);
        tickTimers(pending);

        if (pendingInventorySlot != -1 && pendingHotbarSlot != -1) {
            InvUtils.quickSwap().fromId(pendingHotbarSlot).to(pendingInventorySlot);
            pendingInventorySlot = -1;
            pendingHotbarSlot = -1;
        }

        if (!SchematicAccess.isRenderingEnabled() || shouldPause()) {
            plan = null;
            interactPlan = null;
            idle();
            return;
        }

        if (demandTimer-- <= 0) {
            computeDemand(schematic);
            demandTimer = DEMAND_INTERVAL;
        }
        // before this tick's uses, which then never go out from a slot that's changing
        planHotbar();

        boolean mayPlace = placeTimer++ >= placeDelay.get();
        usesLeft = mayPlace ? placesAllowed() : 0;
        usesThisTick = 0;
        tickSlot = -1;

        // a copy of the inventory to simulate this tick's moves on
        mainInventory.clear();
        for (int i = 0; i <= SlotUtils.MAIN_END; i++) mainInventory.add(mc.player.getInventory().getItem(i).copy());

        float sentYaw = ((LocalPlayerAccessor) mc.player).meteor$getYRotLast();
        float sentPitch = ((LocalPlayerAccessor) mc.player).meteor$getXRotLast();

        // 1. the click aimed at last tick that needed its exact rotation, which the server now has
        firePlan(schematic, sentYaw, sentPitch);
        fireInteractPlan(schematic, sentYaw, sentPitch);

        // 2. whatever that rotation looks at, and the blocks after it along the same ray
        if (isRotating()) fireAlongRay(schematic, sentYaw, sentPitch);

        // 3. everything that's left, for the clicks that don't need rotating and the aim
        scan(schematic, sentYaw, sentPitch);
        if (!isRotating()) fireDirect();

        restoreSneak();

        // 4. the rotation for the next tick's click
        aim();

        if (usesThisTick > 0) {
            placeTimer = 0;
        } else if (blockPrints.isEmpty() && blockInteracts.isEmpty() && plan == null && interactPlan == null) {
            restoreSlot();
        }

        blockPrints.clear();
        blockInteracts.clear();
    }

    private void tickTimers(PendingPlaces pending) {
        lockTicks = settleTicks(pending);
        for (PrintSlot slot : printSlots) slot.lockTimer = Math.min(slot.lockTimer + 1, PrintSlot.SETTLED);

        // Once acked, the client shows what the server made of a click, whether the client predicted it or not, like a
        // note block's. One that changed nothing waits like a place that didn't stick.
        for (Iterator<SentInteract> it = sentInteracts.values().iterator(); it.hasNext(); ) {
            SentInteract sent = it.next();
            if (pending.isAcked(sent.sequence)) {
                if (sent.retryTicks < 0) sent.retryTicks = mc.level.getBlockState(sent.pos) != sent.before ? 0 : placeRetryDelay.get();
                if (sent.retryTicks-- <= 0) it.remove();
            } else if (++sent.ticks > INTERACT_LOST_TICKS) {
                it.remove();
            }
        }

        long now = System.currentTimeMillis();
        signClicks.values().removeIf(time -> now - time > SIGN_SCREEN_MS);

        // a place in flight keeps its position cached; once the server has answered, the retry delay counts down
        for (Long2IntMap.Entry entry : cachedPositions.long2IntEntrySet()) {
            if (!pending.isInFlight(BlockPos.of(entry.getLongKey()))) entry.setValue(entry.getIntValue() - 1);
        }
        cachedPositions.long2IntEntrySet().removeIf(entry -> entry.getIntValue() <= 0);

        checkNoRotatePlaces(pending);

        PendingPlaces.Unanswered unhandled = pending.takeUnanswered();
        // at one a tick there's nothing to learn, and refusals of other uses would only mislead it
        if (blocksPerTick.get() > 1) PlaceRateLimiter.INSTANCE.noteUnhandled(unhandled);
    }

    /**
     * An anticheat that wants rotations, like Grim, undoes places made without them, and the server's answer shows it.
     * So once enough in a row are undone, rotate for a while, then try again, since a lag spike's set-back undoes places
     * too. From mankool0/baritone's printerNoRotate.
     */
    private void checkNoRotatePlaces(PendingPlaces pending) {
        if (noRotateSuppressedTicks > 0 && --noRotateSuppressedTicks == 0) info("Trying to place without rotating again.");

        noRotateChecks.removeIf(check -> {
            // once acked, the client shows whatever the server made of it
            if (pending.isInFlight(check.pos)) return false;

            if (mc.level.getBlockState(check.pos).is(check.block)) noRotateStreak = 0;
            else noRotateStreak++;
            return true;
        });

        if (noRotateStreak >= NO_ROTATE_MAX_STREAK) {
            noRotateStreak = 0;
            noRotateChecks.clear();
            noRotateSuppressedTicks = NO_ROTATE_RETRY_TICKS;
            warning("The server keeps undoing places made without rotating, rotating for the next %d seconds.", NO_ROTATE_RETRY_TICKS / 20);
        }
    }

    /**
     * How many uses this tick may send: blocks-per-tick, but above one only as many as the server's spam limit has room
     * for, and one once the server turned down placing more.
     */
    private int placesAllowed() {
        PlaceRateLimiter limiter = PlaceRateLimiter.INSTANCE;
        int perTick = Math.max(1, blocksPerTick.get());
        if (perTick != blocksPerTickSeen) {
            blocksPerTickSeen = perTick;
            limiter.retryMultiPlace();
        }

        // a place a tick stays under the limit by itself
        if (perTick == 1 || limiter.multiPlaceGivenUp() || limiter.backOffSecondsLeft() > 0) return 1;
        return Math.min(perTick, limiter.placesAllowed());
    }

    private void idle() {
        restoreSneak();
        restoreSlot();
    }

    private boolean shouldPause() {
        if (mc.player.hasContainerOpen() || !mc.player.connection.hasClientLoaded()) return true;

        if (pauseOnUse.get()) {
            if (Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating()) return true;
            if (mc.player.isUsingItem()) return true;
        }

        if (pauseOnMine.get() && mc.gameMode.isDestroying()) return true;
        if (pauseOnKillAura.get() && Modules.get().get(KillAura.class).attacking) return true;
        return pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1.5f;
    }

    // Firing

    private void firePlan(Level schematic, float sentYaw, float sentPitch) {
        BlockPrint print = plan;
        plan = null;
        if (print == null || usesLeft <= 0 || print.hit == null) return;

        // the movement fix turned it, so the ray finds it if it still can, or another module's rotation went out instead
        if (Math.abs(Mth.wrapDegrees(sentYaw - print.yaw)) > 1e-3 || Math.abs(sentPitch - print.pitch) > 1e-3) return;

        BlockState existing = mc.level.getBlockState(print.blockPos);
        if (existing != print.existing || !BlockPrint.isValid(print.blockPos, print.required, existing, this)) return;
        if (schematic.getBlockState(print.blockPos) != print.required || !print.isHitStillValid()) return;
        if (!print.fluid && !print.matches(print.hit, sentYaw, sentPitch, print.sneaking)) return;

        place(print, print.hit, print.sneaking);
    }

    private void fireInteractPlan(Level schematic, float sentYaw, float sentPitch) {
        BlockInteract planned = interactPlan;
        interactPlan = null;
        if (planned == null || usesLeft <= 0 || planned.hit == null) return;
        if (Math.abs(Mth.wrapDegrees(sentYaw - interactPlanYaw)) > 1e-3 || Math.abs(sentPitch - interactPlanPitch) > 1e-3) return;
        if (sentInteracts.containsKey(planned.blockPos.asLong()) || !planned.isHitStillValid()) return;

        // still the same job
        BlockInteract now = BlockInteract.create(planned.blockPos, schematic.getBlockState(planned.blockPos), mc.level.getBlockState(planned.blockPos), this);
        if (now == null || (now.use == null) != (planned.use == null)) return;

        interactBlock(now, planned.hit);
    }

    /**
     * Clicks whatever the rotation the server already has looks at, as a player clicking without moving the mouse
     * could. After a place the client shows the new block, so the same ray then ends on it and the next block along it
     * can go out in the same tick.
     */
    private void fireAlongRay(Level schematic, float sentYaw, float sentPitch) {
        Vec3 eye = mc.player.getEyePosition();
        // the server checks the click against the movement packet after it too, so the ray has to reach from there
        Vec3 nextEye = eye.add(mc.player.getDeltaMovement());
        double range = placeRange.get();

        for (int i = 0; i < MAX_RAY_CLICKS && usesLeft > 0; i++) {
            BlockHitResult hit = PrinterUtils.raycast(eye, sentYaw, sentPitch, range);
            if (hit.getType() != HitResult.Type.BLOCK) return;

            BlockPos clicked = hit.getBlockPos();
            if (!PrinterUtils.rayHitsBlock(nextEye, sentYaw, sentPitch, clicked, range)) return;
            if (!PrinterUtils.reachesNowAndNext(clicked, hit.getDirection(), range, strictFaces.get())) return;

            if (rayPlace(schematic, hit, sentYaw, sentPitch)) continue;
            rayInteract(schematic, hit);
            return;
        }
    }

    private boolean rayPlace(Level schematic, BlockHitResult hit, float sentYaw, float sentPitch) {
        BlockPos clicked = hit.getBlockPos();

        // a click places into the clicked block when it can be replaced, else next to it
        for (BlockPos target : new BlockPos[]{clicked, clicked.relative(hit.getDirection())}) {
            if (!SchematicAccess.isRendered(target) || !Level.isInSpawnableBounds(target)) continue;

            BlockState required = schematic.getBlockState(target);
            // buckets go out on the air, aimed by the plan
            if (required.getBlock() instanceof LiquidBlock) continue;

            BlockState existing = mc.level.getBlockState(target);
            if (!BlockPrint.isValid(target, required, existing, this)) continue;

            BlockPrint print = new BlockPrint(target.immutable(), required, existing, this);
            Boolean sneak = print.sneakFor(hit, sentYaw, sentPitch);
            if (sneak == null) continue;

            return place(print, hit, sneak);
        }

        return false;
    }

    private void rayInteract(Level schematic, BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        if (!SchematicAccess.isRendered(clicked)) return;

        BlockInteract blockInteract = BlockInteract.create(clicked.immutable(), schematic.getBlockState(clicked), mc.level.getBlockState(clicked), this);
        if (blockInteract == null || !blockInteract.worksOn(hit.getDirection()) || sentInteracts.containsKey(clicked.asLong())) return;

        interactBlock(blockInteract, hit);
    }

    private void fireDirect() {
        for (BlockPrint blockPrint : blockPrints) {
            if (usesLeft <= 0) return;
            if (blockPrint.needsRotation() || blockPrint.hit == null) continue;
            if (mc.level.getBlockState(blockPrint.blockPos) != blockPrint.existing) continue;

            // a place earlier this tick can change what this click makes, like the other half of a double chest
            boolean sneak = blockPrint.sneaking;
            if (usesThisTick > 0 && !blockPrint.fluid) {
                Boolean now = blockPrint.sneakFor(blockPrint.hit, blockPrint.yaw, blockPrint.pitch);
                if (now == null) continue;
                sneak = now;
            }

            if (place(blockPrint, blockPrint.hit, sneak) && !blockPrint.fluid) {
                noRotateChecks.add(new PlaceCheck(blockPrint.blockPos, blockPrint.required.getBlock()));
            }
        }

        for (BlockInteract blockInteract : blockInteracts) {
            if (usesLeft <= 0) return;
            if (sentInteracts.containsKey(blockInteract.blockPos.asLong())) continue;

            interactBlock(blockInteract, blockInteract.hit);
        }
    }

    // Aiming

    /**
     * Turns towards the first block in the sort order that needs a rotation, for the next tick to click. A click the
     * ray of its rotation finds by itself lets the movement fix move the yaw anywhere that still finds it. One whose
     * facing depends on the exact rotation, or whose point is behind another block, keeps the rotation exactly and is
     * clicked with its stored hit: Grim's RotationPlace only wants the ray to pass through the clicked block.
     */
    private void aim() {
        Vec3 eye = mc.player.getEyePosition();
        double range = placeRange.get();

        for (BlockPrint blockPrint : blockPrints) {
            if (!blockPrint.needsRotation() || blockPrint.hit == null) continue;
            if (mc.level.getBlockState(blockPrint.blockPos) != blockPrint.existing) continue;

            // clicked with its stored hit next tick if the server gets exactly this rotation
            plan = blockPrint;

            BlockHitResult wanted = blockPrint.hit;
            boolean sneak = blockPrint.sneaking;
            Rotations.Aim aim = (yaw, pitch) -> {
                BlockHitResult result = PrinterUtils.raycast(eye, yaw, pitch, range);
                return result.getType() == HitResult.Type.BLOCK
                    && result.getBlockPos().equals(wanted.getBlockPos())
                    && result.getDirection() == wanted.getDirection()
                    && blockPrint.matches(result, yaw, pitch, sneak);
            };

            // a facing that depends on the rotation, or a point behind another block, which the ray of the rotation
            // doesn't find by itself, needs the rotation exactly as it is
            if (blockPrint.needsExactRotation() || !aim.accepts(blockPrint.yaw, blockPrint.pitch)) {
                Rotations.rotate(blockPrint.yaw, blockPrint.pitch, ROTATION_PRIORITY, null);
            } else {
                Rotations.rotate(blockPrint.yaw, blockPrint.pitch, ROTATION_PRIORITY, null, aim);
            }
            return;
        }

        if (!isRotating()) return;

        for (BlockInteract blockInteract : blockInteracts) {
            if (sentInteracts.containsKey(blockInteract.blockPos.asLong())) continue;

            BlockHitResult wanted = blockInteract.hit;
            float yaw = blockInteract.getYaw();
            float pitch = blockInteract.getPitch();
            Rotations.Aim aim = (y, p) -> {
                BlockHitResult result = PrinterUtils.raycast(eye, y, p, range);
                return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(wanted.getBlockPos())
                    && blockInteract.worksOn(result.getDirection());
            };

            interactPlan = blockInteract;
            interactPlanYaw = yaw;
            interactPlanPitch = pitch;

            // a point behind another block, which the ray doesn't find by itself, needs the rotation exactly as it is
            if (aim.accepts(yaw, pitch)) Rotations.rotate(yaw, pitch, ROTATION_PRIORITY, null, aim);
            else Rotations.rotate(yaw, pitch, ROTATION_PRIORITY, null);
            return;
        }
    }

    // Scanning

    private void scan(Level schematic, float serverYaw, float serverPitch) {
        int px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
        int radius = (int) Math.ceil(placeRange.get()) + 1;
        int minY = Math.max(mc.level.getMinY(), py - radius);
        int maxY = Math.min(mc.level.getMaxY(), py + radius);
        Vec3 eye = mc.player.getEyePosition();
        double maxDistSq = (placeRange.get() + 1) * (placeRange.get() + 1);

        placeable.clear();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    // the exact reach is checked on the clicked block, so allow a block of slack here
                    if (eye.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > maxDistSq) continue;

                    blockPos.set(x, y, z);

                    // only the layers Litematica renders get printed
                    if (!SchematicAccess.isRendered(blockPos)) continue;
                    if (!Level.isInSpawnableBounds(blockPos)) continue;

                    BlockState required = schematic.getBlockState(blockPos);
                    BlockState existing = mc.level.getBlockState(blockPos);

                    if (BlockPrint.isValid(blockPos, required, existing, this)) {
                        BlockPrint blockPrint = new BlockPrint(blockPos.immutable(), required, existing, this);
                        // nothing to place it with, so nothing to turn towards either
                        Item item = blockPrint.asItem();
                        if (!placeable.containsKey(item)) placeable.put(item, canGetItem(stack -> stack.getItem() == item, true));
                        if (!placeable.getBoolean(item)) continue;

                        if (blockPrint.findHit(serverYaw, serverPitch)) blockPrints.add(blockPrint);
                        continue;
                    }

                    BlockInteract blockInteract = BlockInteract.create(blockPos.immutable(), required, existing, this);
                    if (blockInteract != null && !sentInteracts.containsKey(blockPos.asLong()) && blockInteract.canInteract()
                        && (blockInteract.use == null || blockInteract.use.items().stream().anyMatch(item -> canGetItem(item, false)))) {
                        blockInteracts.add(blockInteract);
                    }
                }
            }
        }

        sortBlockPrints();
    }

    // Placing

    /**
     * Places with this click from whatever slot holds the block, switching or moving it into the hotbar when allowed.
     *
     * @return whether it went out
     */
    private boolean place(BlockPrint blockPrint, BlockHitResult hit, boolean sneak) {
        if (usesLeft <= 0) return false;

        Item item = blockPrint.asItem();
        int hotbarSlot = prepareSlot(stack -> stack.getItem() == item, true, sneak);
        if (hotbarSlot == -1) return false;

        setSneak(sneak);
        selectSlot(hotbarSlot);
        placedFrom[hotbarSlot] = item;

        noteSignClicks(blockPrint.placeState.getBlock() instanceof SignBlock ? blockPrint.blockPos : null, blockPrint.fluid ? null : hit);

        InteractionResult result = use(blockPrint.fluid ? null : hit);
        swingFor(result);

        afterUse();
        cachedPositions.put(blockPrint.blockPos.asLong(), Math.max(1, placeRetryDelay.get()));
        placeHistory[placeHistoryIndex]++;
        if (render.get()) placedFades.add(new PlacedFade(fadeTime.get(), blockPrint.blockPos));
        return true;
    }

    private void interactBlock(BlockInteract blockInteract, BlockHitResult hit) {
        if (usesLeft <= 0 || hit == null) return;

        int hotbarSlot = -1;
        if (blockInteract.use != null) {
            Predicate<ItemStack> item = null;
            for (Predicate<ItemStack> option : blockInteract.use.items()) {
                if (getItemSlot(option, false) != -1) {
                    item = option;
                    break;
                }
            }
            if (item == null) return;

            hotbarSlot = prepareSlot(item, false, blockInteract.sneak);
            if (hotbarSlot == -1) return;

            // a wrong item blows a charged anchor up outside the nether, so only click with a slot the server had before
            if (ItemUses.isRisky(mc.level.getBlockState(blockInteract.blockPos))
                && ((MultiPlayerGameModeAccessor) mc.gameMode).meteor$getCarriedIndex() != hotbarSlot) {
                selectSlot(hotbarSlot);
                usesLeft = 0;
                return;
            }
        } else if (!packetsFit(USE_PACKETS + sneakPackets(false))) {
            usesLeft = 0;
            return;
        }

        // sneaking would place against the block instead, unless the item has to go past the block's own use
        setSneak(blockInteract.sneak);
        if (hotbarSlot != -1) selectSlot(hotbarSlot);
        noteSignClicks(null, hit);

        BlockState before = mc.level.getBlockState(blockInteract.blockPos);
        swingFor(use(hit));
        tickSlot = mc.player.getInventory().getSelectedSlot();

        afterUse();
        int sequence = ((ClientLevelAccessor) mc.level).meteor$getBlockStatePredictionHandler().currentSequence();
        sentInteracts.put(blockInteract.blockPos.asLong(), new SentInteract(blockInteract.blockPos, sequence, before));
    }

    /**
     * Clicks the way the server takes it, with the sneak state and the rotation it has. The client predicts a click from
     * its own keys and its own rotation, not from what it sent, so those are swapped in for the click alone. Else a
     * sneaked place against a chest or a repeater is predicted as opening or toggling it, a block placed with a rotation
     * the printer sent comes out facing the camera's way, and until the server answers the client shows blocks and stack
     * counts the server never had, which the next clicks are then worked out from. A bucket's use also carries the
     * rotation, which the server turns the player to.
     *
     * @param hit the click, or null to use the held item on the air
     */
    private InteractionResult use(@Nullable BlockHitResult hit) {
        LocalPlayer player = mc.player;
        ClientInput input = player.input;
        Input keys = input.keyPresses;
        float yaw = player.getYRot(), pitch = player.getXRot();

        boolean sneak = sneakSent();
        if (keys.shift() != sneak) {
            input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), sneak, keys.sprint());
        }
        player.setYRot(((LocalPlayerAccessor) player).meteor$getYRotLast());
        player.setXRot(((LocalPlayerAccessor) player).meteor$getXRotLast());

        try {
            return hit == null
                ? mc.gameMode.useItem(player, InteractionHand.MAIN_HAND)
                : mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        } finally {
            input.keyPresses = keys;
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    /**
     * Gets a stack that {@code item} accepts into the hotbar for a use, from a hotbar slot or moved in from the
     * inventory when allowed, within this tick's slot and packet budget.
     *
     * @param placing for a place, a stack whose last items are still on their way to the server counts as empty; for
     *                any other use the slot waits for the server's answer to its last use, which may have changed it
     * @return the hotbar slot, or -1 if none will do this tick
     */
    private int prepareSlot(Predicate<ItemStack> item, boolean placing, boolean sneak) {
        int inventorySlot = getItemSlot(item, placing);
        if (inventorySlot == -1) return -1;

        int hotbarSlot = inventorySlot;
        boolean move = inventorySlot > SlotUtils.HOTBAR_END;

        if (move) {
            // a move changes the slot the tick's uses go out from, so only before the first
            if (usesThisTick > 0 || !canInventoryMove()) return -1;

            hotbarSlot = getSwapSlot();
            if (hotbarSlot == -1) return -1;
        }

        if (!autoSwitch.get() && mc.player.getInventory().getSelectedSlot() != hotbarSlot) return -1;
        if (tickSlot != -1 && hotbarSlot != tickSlot) return -1;

        int packets = USE_PACKETS + sneakPackets(sneak) + (move ? 1 : 0)
            + (((MultiPlayerGameModeAccessor) mc.gameMode).meteor$getCarriedIndex() != hotbarSlot ? 1 : 0);
        if (!packetsFit(packets)) {
            usesLeft = 0;
            return -1;
        }

        if (move) {
            printSlots.get(hotbarSlot).reset();
            printSlots.get(inventorySlot).reset();

            ItemStack hotbarStack = mainInventory.get(hotbarSlot);
            ItemStack inventoryStack = mainInventory.get(inventorySlot);
            mainInventory.set(hotbarSlot, inventoryStack);
            mainInventory.set(inventorySlot, hotbarStack);

            // leave the slot alone until the server has the move, then use it
            if (printSlots.get(hotbarSlot).isLocked()) {
                pendingInventorySlot = inventorySlot;
                pendingHotbarSlot = hotbarSlot;
                usesLeft = 0;
                return -1;
            }

            if (placing) inventoryStack.consume(1, mc.player);

            InvUtils.quickSwap().fromId(hotbarSlot).to(inventorySlot);
            // refused, e.g. by CalmInventory while moving, so the slot still holds something else
            if (!item.test(mc.player.getInventory().getItem(hotbarSlot))) {
                usesLeft = 0;
                return -1;
            }
        }

        return hotbarSlot;
    }

    private void afterUse() {
        if (usesThisTick > 0) PlaceRateLimiter.INSTANCE.noteSecondPlace();
        usesThisTick++;
        usesLeft--;
    }

    private void swingFor(InteractionResult result) {
        // vanilla only swings when the client is the one to
        if (!(result instanceof InteractionResult.Success success) || success.swingSource() != InteractionResult.SwingSource.CLIENT) return;

        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        else mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    /**
     * @return whether this many more packets fit under packets-per-tick; the tick's first action always does
     */
    private boolean packetsFit(int packets) {
        return actionsThisTick == 0 || actionsThisTick + packets <= packetsPerTick.get();
    }

    // Sneaking

    /**
     * @return whether the player sneaks by itself, rather than for one of this tick's clicks
     */
    boolean isPlayerSneaking() {
        return mc.player.isShiftKeyDown();
    }

    private boolean sneakSent() {
        return sneakOverride != null ? sneakOverride : mc.player.getLastSentInput().shift();
    }

    /**
     * @return the packets it takes to click with this sneak state: the toggle, and the toggle back after the tick's
     * clicks if this is the first
     */
    private int sneakPackets(boolean sneak) {
        if (sneak == sneakSent()) return 0;
        return sneakOverride == null ? 2 : 1;
    }

    private void setSneak(boolean sneak) {
        if (sneak == sneakSent()) return;

        sneakOverride = sneak;

        Input sent = mc.player.getLastSentInput();
        mc.getConnection().send(new ServerboundPlayerInputPacket(new Input(sent.forward(), sent.backward(), sent.left(), sent.right(), sent.jump(), sneak, sent.sprint())));
    }

    /**
     * Puts the server back on the input the client last sent, which the client compares the next one against.
     */
    private void restoreSneak() {
        if (sneakOverride == null) return;

        Input sent = mc.player.getLastSentInput();
        if (sneakOverride != sent.shift()) mc.getConnection().send(new ServerboundPlayerInputPacket(sent));
        sneakOverride = null;
    }

    // Inventory

    private void selectSlot(int slot) {
        int selected = mc.player.getInventory().getSelectedSlot();
        // the player picked another slot since, so that one is theirs to keep
        if (selectedByPrinter != -1 && selected != selectedByPrinter) restoreSlot = -1;

        if (selected != slot) {
            if (swapBack.get() && restoreSlot == -1) restoreSlot = selected;
            mc.player.getInventory().setSelectedSlot(slot);
        }

        ((IMultiPlayerGameMode) mc.gameMode).meteor$syncSelected();
        selectedByPrinter = slot;
        tickSlot = slot;
    }

    private void restoreSlot() {
        if (restoreSlot == -1 || mc.player == null) return;

        if (mc.player.getInventory().getSelectedSlot() == selectedByPrinter) {
            mc.player.getInventory().setSelectedSlot(restoreSlot);
            ((IMultiPlayerGameMode) mc.gameMode).meteor$syncSelected();
        }
        restoreSlot = -1;
        selectedByPrinter = -1;
    }

    /**
     * A slot's ticks since the printer last moved a stack into or out of it. Until the server's answer to the move is
     * in, an update of the slot the server sent before it had the move can still land and show what the slot held
     * before, so the slot is left alone; the answer to the move then puts it right.
     */
    private class PrintSlot {
        static final int SETTLED = 1_000_000;

        public final int slot;
        public int lockTimer;

        PrintSlot(int slot) {
            this.slot = slot;
        }

        public void reset() {
            lockTimer = 0;
        }

        public void expire() {
            lockTimer = SETTLED;
        }

        public boolean isLocked() {
            return lockTimer < lockTicks;
        }
    }

    /**
     * @return ticks a slot is left alone after a move: slot-lock-ticks, or the round trip and a tick to spare when that's
     * longer, as the move itself may go out a tick late
     */
    private int settleTicks(PendingPlaces pending) {
        long roundTrip = pending.roundTripNanos();
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        // the ping the server measures leaves out the wait for the end of its tick, which acks and answers wait for
        if (info != null) roundTrip = Math.max(roundTrip, TimeUnit.MILLISECONDS.toNanos(info.getLatency() + 50));

        int roundTripTicks = roundTrip <= 0 ? 0 : (int) Math.ceil(roundTrip / (double) TimeUnit.MILLISECONDS.toNanos(50));
        return Math.max(slotLockTicks.get(), roundTripTicks + 2);
    }

    /**
     * A click on a block the server hasn't answered yet. The client doesn't predict every click, a note block's say, so
     * until the server's answer is in the block may still show the state from before it, and clicking again would
     * overshoot.
     */
    private static final class SentInteract {
        final BlockPos pos;
        final int sequence;
        final BlockState before;
        int ticks;
        int retryTicks = -1;

        SentInteract(BlockPos pos, int sequence, BlockState before) {
            this.pos = pos;
            this.sequence = sequence;
            this.before = before;
        }
    }

    // Runs on the network thread
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // the editor of a sign the printer placed, or clicked to place against it
        if (event.packet instanceof ClientboundOpenSignEditorPacket packet && exitSigns.get()) {
            Long time = signClicks.get(packet.getPos().asLong());
            if (time != null && System.currentTimeMillis() - time <= SIGN_SCREEN_MS) event.cancel();
        }
    }

    /**
     * Remembers the signs a click may make the server open the editor of, a round trip later.
     *
     * @param placed the sign this click places, if it places one
     * @param hit    the click, which opens the editor of a sign it lands on when not sneaking
     */
    private void noteSignClicks(@Nullable BlockPos placed, @Nullable BlockHitResult hit) {
        if (!exitSigns.get()) return;

        long now = System.currentTimeMillis();
        if (placed != null) signClicks.put(placed.asLong(), now);
        if (hit != null && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof SignBlock) signClicks.put(hit.getBlockPos().asLong(), now);
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundUseItemOnPacket
            || event.packet instanceof ServerboundUseItemPacket
            || event.packet instanceof ServerboundSwingPacket
            || event.packet instanceof ServerboundSetCarriedItemPacket
            || event.packet instanceof ServerboundPlayerActionPacket
            || event.packet instanceof ServerboundContainerClickPacket
            || event.packet instanceof ServerboundPlayerInputPacket
            || event.packet instanceof ServerboundInteractPacket) {
            actionsThisTick++;
        }
    }

    /**
     * @return the slot of the inventory copy holding a stack {@code item} accepts, preferring the slot this tick's
     * uses go out from and then the selected one, or -1 if none will do
     */
    private int getItemSlot(Predicate<ItemStack> item, boolean placing) {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (tickSlot != -1) return isUsableHotbarSlot(tickSlot, item, placing) ? tickSlot : -1;
        if (isUsableHotbarSlot(selected, item, placing)) return selected;

        for (int i = 0; i <= SlotUtils.MAIN_END; i++) {
            if (!item.test(mainInventory.get(i))) continue;

            // a hotbar slot settling after a move waits, but another one with the item may still do
            if (i <= SlotUtils.HOTBAR_END) {
                if (!isSlotLocked(i) && hotbarSlotReady(i, item, placing)) return i;
                continue;
            }

            // this item is on its way into the hotbar already
            if (isSlotLocked(i) || isSettlingInHotbar(item)) return -1;

            return i;
        }

        return -1;
    }

    /**
     * Whether the player has a stack {@code item} accepts that the printer may use, now or once a move or a place in
     * flight has landed, as opposed to {@link #getItemSlot}, which is about this tick.
     */
    private boolean canGetItem(Predicate<ItemStack> item, boolean placing) {
        PendingPlaces pending = PendingPlaces.of(mc.level);
        int selected = mc.player.getInventory().getSelectedSlot();
        // a hotbar of tools, food and totems has no room to move anything into
        boolean fromInventory = canInventoryMove() && getSwapSlot() != -1;

        for (int i = 0; i <= SlotUtils.MAIN_END; i++) {
            boolean hotbar = i <= SlotUtils.HOTBAR_END;
            if (hotbar ? !autoSwitch.get() && i != selected : !fromInventory) continue;

            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!item.test(stack)) continue;
            if (!hotbar || !placing || mc.player.hasInfiniteMaterials() || pending.countAfterInFlight(i, stack) > 0) return true;
        }
        return false;
    }

    private boolean isSettlingInHotbar(Predicate<ItemStack> item) {
        for (int i = 0; i <= SlotUtils.HOTBAR_END; i++) {
            if (isSlotLocked(i) && item.test(mainInventory.get(i))) return true;
        }
        return false;
    }

    private boolean isUsableHotbarSlot(int slot, Predicate<ItemStack> item, boolean placing) {
        return item.test(mainInventory.get(slot)) && !isSlotLocked(slot) && hotbarSlotReady(slot, item, placing);
    }

    /**
     * For a place, whether the server still has some of the stack once the places in flight from it land. For any other
     * use, whether the server has answered the slot's last use, which may have changed what it holds.
     */
    private boolean hotbarSlotReady(int slot, Predicate<ItemStack> item, boolean placing) {
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (!item.test(stack)) return false;

        PendingPlaces pending = PendingPlaces.of(mc.level);
        if (!placing) return pending.inFlight(slot) == 0;
        if (mc.player.hasInfiniteMaterials()) return true;
        return pending.countAfterInFlight(slot, stack) > 0;
    }

    private int getSwapSlot() {
        // an empty slot first
        for (int i = 0; i <= SlotUtils.HOTBAR_END; i++) {
            if (isSlotLocked(i)) continue;
            if (mainInventory.get(i).isEmpty()) return i;
        }
        // else the one holding the least needed thing that isn't valuable
        int best = -1;
        int bestDemand = Integer.MAX_VALUE;
        for (int i = 0; i <= SlotUtils.HOTBAR_END; i++) {
            int j = (mc.player.getInventory().getSelectedSlot() + i) % 9;
            if (isSlotLocked(j) || !isSwappable(mainInventory.get(j))) continue;

            int needed = demand.getInt(mainInventory.get(j).getItem());
            if (needed < bestDemand) {
                best = j;
                bestDemand = needed;
            }
        }
        return best;
    }

    // Planning the hotbar

    /**
     * Counts the items the schematic still needs around the player and around where it's heading, so the hotbar can
     * hold them before they're needed. What's in reach counts for more than what's only coming up.
     */
    private void computeDemand(Level schematic) {
        demand.clear();
        if (!autoSwitch.get() || !allowInventory.get() || !(restockEarly.get() || planHotbar.get())) return;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 velocity = mc.player.getDeltaMovement();
        double px = mc.player.getX(), pz = mc.player.getZ();
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        // Standing still, only what's in reach. Moving, also what comes into reach along the way, but not what's off to
        // the side or behind, which a player standing at the edge of a finished area or layer would never get to.
        double ahead = speed > MOVING_SPEED ? lookahead.get() : 0;
        double dirX = ahead > 0 ? velocity.x / speed : 0, dirZ = ahead > 0 ? velocity.z / speed : 0;
        // a block of slack, like the scan
        double near = placeRange.get() + 1;
        double nearSq = near * near;
        int vertical = (int) Math.ceil(near);
        int py = mc.player.getBlockY();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double endX = px + dirX * ahead, endZ = pz + dirZ * ahead;
        for (int x = Mth.floor(Math.min(px, endX) - near); x <= Mth.floor(Math.max(px, endX) + near); x++) {
            for (int z = Mth.floor(Math.min(pz, endZ) - near); z <= Mth.floor(Math.max(pz, endZ) + near); z++) {
                // how far the column is from the way ahead
                double rx = x + 0.5 - px, rz = z + 0.5 - pz;
                double along = Mth.clamp(rx * dirX + rz * dirZ, 0, ahead);
                double ox = rx - dirX * along, oz = rz - dirZ * along;
                if (ox * ox + oz * oz > nearSq) continue;

                for (int y = Math.max(mc.level.getMinY(), py - vertical); y <= Math.min(mc.level.getMaxY(), py + vertical + 1); y++) {
                    boolean inReach = eye.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) <= nearSq;
                    if (!inReach && ahead == 0) continue;

                    pos.set(x, y, z);
                    if (!SchematicAccess.isRendered(pos)) continue;

                    BlockState required = schematic.getBlockState(pos);
                    if (required.isAir()) continue;
                    BlockState existing = mc.level.getBlockState(pos);
                    if (required == existing) continue;

                    Item item = neededItem(required, existing, pos);
                    if (item == null || item == Items.AIR) continue;

                    demand.addTo(item, inReach ? IN_REACH_WEIGHT : 1);
                }
            }
        }
    }

    /**
     * @return the item it takes to move the existing block towards the schematic's, or null if none does
     */
    @Nullable
    private Item neededItem(BlockState required, BlockState existing, BlockPos pos) {
        // Only what the printer would place there: not where a place is in flight, nor what can't stand there, nor
        // what has nothing to be placed against yet, like a layer shown above ones not built.
        if (BlockPrint.isValid(pos, required, existing, this)) {
            return hasSomethingToClick(pos, required, existing) ? BlockPrint.itemFor(placeStateFor(required)) : null;
        }
        if (existing.canBeReplaced() && required.getBlock() != existing.getBlock()) return null;

        if (!useItems.get()) return null;
        ItemUses.ItemUse use = ItemUses.find(required, existing, pos);
        if (use == null) return null;

        // the item of the first stack that does it
        for (Predicate<ItemStack> option : use.items()) {
            for (int i = 0; i <= SlotUtils.MAIN_END; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (option.test(stack)) return stack.getItem();
            }
        }
        return null;
    }

    /**
     * Whether a place there has a block to click, the cheap part of {@link BlockPrint#findHit}: a neighbour that isn't
     * replaceable, or the cell itself when air placing or adding to what's there.
     */
    private boolean hasSomethingToClick(BlockPos pos, BlockState required, BlockState existing) {
        if (airPlace.get() || (incrementalStates.get() && required.getBlock() == existing.getBlock())) return true;
        for (Direction direction : Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(direction)).canBeReplaced()) return true;
        }
        return false;
    }

    /**
     * Moves what the schematic needs nearby into the hotbar ahead of time, most needed first, a few stacks a tick: a
     * block that isn't on the hotbar at all, and the next stack of one whose last hotbar stack is running low, so it
     * settles in another slot while the old one still lasts. Room comes from empty slots, then slots holding things
     * needed far less.
     */
    private void planHotbar() {
        if (demand.isEmpty() || pendingInventorySlot != -1 || !canInventoryMove()) return;

        List<Item> wanted = new ArrayList<>(demand.keySet());
        wanted.sort(Comparator.comparingInt(demand::getInt).reversed());

        PendingPlaces pending = PendingPlaces.of(mc.level);
        boolean[] taken = new boolean[9];
        int moves = 0;

        for (Item item : wanted) {
            if (moves >= movesPerTick.get() || actionsThisTick + 1 > packetsPerTick.get()) return;

            int stacks = 0;
            int left = 0;
            boolean settling = false;
            for (int h = 0; h <= SlotUtils.HOTBAR_END; h++) {
                ItemStack stack = mc.player.getInventory().getItem(h);
                if (stack.getItem() != item) continue;
                if (isSlotLocked(h)) settling = true;

                int count = mc.player.hasInfiniteMaterials() ? stack.getCount() : pending.countAfterInFlight(h, stack);
                if (count > 0) {
                    stacks++;
                    left += count;
                }
            }
            // a move of it is still settling
            if (settling) continue;

            boolean move = stacks == 0 ? planHotbar.get() : stacks == 1 && left <= RESTOCK_BELOW && restockEarly.get();
            if (!move) continue;

            int source = fullestInventoryStack(item);
            if (source == -1) continue;

            int target = chooseHotbarTarget(item, taken);
            if (target == -1) continue;

            InvUtils.quickSwap().fromId(target).to(source);
            // refused, e.g. by CalmInventory while moving
            if (mc.player.getInventory().getItem(target).getItem() != item) return;

            printSlots.get(target).reset();
            printSlots.get(source).reset();
            taken[target] = true;
            moves++;
        }
    }

    private int fullestInventoryStack(Item item) {
        int best = -1;
        for (int i = SlotUtils.MAIN_START; i <= SlotUtils.MAIN_END; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() != item || isSlotLocked(i)) continue;
            if (best == -1 || stack.getCount() > mc.player.getInventory().getItem(best).getCount()) best = i;
        }
        return best;
    }

    /**
     * @return the hotbar slot to move a stack of this item into: the slot it was last placed from if that's empty, any
     * empty slot, or the one holding the least needed thing, needed a quarter as much as this at most; -1 if none
     */
    private int chooseHotbarTarget(Item item, boolean[] taken) {
        PendingPlaces pending = PendingPlaces.of(mc.level);
        // the slot the player had before the printer took over keeps what's in it
        int playerSlot = restoreSlot != -1 ? restoreSlot : selectedByPrinter == -1 ? mc.player.getInventory().getSelectedSlot() : -1;

        int empty = -1;
        int best = -1;
        int bestDemand = demand.getInt(item) / IN_REACH_WEIGHT;
        for (int h = 0; h <= SlotUtils.HOTBAR_END; h++) {
            if (taken[h] || h == playerSlot || isSlotLocked(h) || pending.inFlight(h) > 0) continue;

            ItemStack stack = mc.player.getInventory().getItem(h);
            if (stack.isEmpty()) {
                if (placedFrom[h] == item) return h;
                if (empty == -1) empty = h;
                continue;
            }
            if (stack.getItem() == item || !isSwappable(stack)) continue;

            int needed = demand.getInt(stack.getItem());
            if (needed < bestDemand || (needed == 0 && best == -1)) {
                best = h;
                bestDemand = needed;
            }
        }

        return empty != -1 ? empty : best;
    }

    private boolean isSwappable(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof BlockItem) return true;
        if (stack.isEnchanted()) return false;
        if (item == Items.EXPERIENCE_BOTTLE || item == Items.TOTEM_OF_UNDYING) return false;
        if (item.components().get(DataComponents.FOOD) != null) return item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE;
        return true;
    }

    private boolean isSlotLocked(int slot) {
        if (slot < 0 || slot >= printSlots.size()) return false;
        return printSlots.get(slot).isLocked();
    }

    private boolean canInventoryMove() {
        if (!autoSwitch.get() || !allowInventory.get()) return false;
        return !stationaryMove.get() || !isPlayerMoving();
    }

    private boolean isPlayerMoving() {
        // what Grim checks clicks against
        Input input = mc.player.getLastSentInput();
        return input.forward() || input.backward() || input.left() || input.right() || input.jump()
            || ((LocalPlayerAccessor) mc.player).meteor$wasSprinting() || input.shift();
    }

    private record PlaceCheck(BlockPos pos, Block block) {
    }

    // Rendering

    private static class PlacedFade {
        public float ticks;
        public final BlockPos pos;

        PlacedFade(float ticks, BlockPos pos) {
            this.ticks = ticks;
            this.pos = pos;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        placedFades.removeIf(fade -> {
            fade.ticks -= event.tickDelta;
            if (fade.ticks <= 0) return true;

            double fadeMultiplier = fade.ticks / fadeTime.get();
            Color fadedSide = new Color(sideColor.get()).a((int) (fadeMultiplier * sideColor.get().a));
            Color fadedLine = new Color(lineColor.get()).a((int) (fadeMultiplier * lineColor.get().a));
            event.renderer.box(fade.pos, fadedSide, fadedLine, shapeMode.get(), 0);
            return false;
        });
    }

    // Sorting

    private void sortBlockPrints() {
        Comparator<BlockPrint> comparator = firstAlgorithm.get().algorithm;

        if (firstAlgorithm.get().applySecondSorting && secondAlgorithm.get() != SortingSecond.None) {
            comparator = secondAlgorithm.get().algorithm.thenComparing(comparator);
        }

        if (autoSwitch.get() && allowInventory.get() && hotbarPriority.get()) {
            comparator = Comparator.<BlockPrint, Boolean>comparing(print -> !isOnSettledHotbar(print.asItem())).thenComparing(comparator);
        }

        blockPrints.sort(comparator);
    }

    @SuppressWarnings("unused")
    public enum SortAlgorithm {
        None(false, (a, b) -> 0),
        TopDown(true, Comparator.comparingInt(p -> -p.blockPos.getY())),
        DownTop(true, Comparator.comparingInt(p -> p.blockPos.getY())),
        Nearest(false, Comparator.comparingDouble(p -> distanceToPlayer(p.blockPos))),
        Furthest(false, Comparator.comparingDouble(p -> -distanceToPlayer(p.blockPos)));

        final boolean applySecondSorting;
        final Comparator<BlockPrint> algorithm;

        SortAlgorithm(boolean applySecondSorting, Comparator<BlockPrint> algorithm) {
            this.applySecondSorting = applySecondSorting;
            this.algorithm = algorithm;
        }
    }

    @SuppressWarnings("unused")
    public enum SortingSecond {
        None(SortAlgorithm.None.algorithm),
        Nearest(SortAlgorithm.Nearest.algorithm),
        Furthest(SortAlgorithm.Furthest.algorithm);

        final Comparator<BlockPrint> algorithm;

        SortingSecond(Comparator<BlockPrint> algorithm) {
            this.algorithm = algorithm;
        }
    }

    private static double distanceToPlayer(BlockPos pos) {
        if (MeteorClient.mc.player == null) return 0;
        return MeteorClient.mc.player.getEyePosition().distanceToSqr(pos.getCenter());
    }

    /**
     * @return whether a hotbar slot that isn't settling after a move holds the item
     */
    private boolean isOnSettledHotbar(Item item) {
        for (int i = 0; i <= SlotUtils.HOTBAR_END; i++) {
            if (!isSlotLocked(i) && mc.player.getInventory().getItem(i).getItem() == item) return true;
        }
        return false;
    }
}
