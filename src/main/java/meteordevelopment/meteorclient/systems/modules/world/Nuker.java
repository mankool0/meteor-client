/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.LocalPlayerAccessor;
import meteordevelopment.meteorclient.mixin.MultiPlayerGameModeAccessor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import meteordevelopment.meteorclient.systems.modules.player.SpeedMine;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.network.ActionPackets;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.ServerDigSlots;
import meteordevelopment.meteorclient.utils.world.ServerDigSpeed;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class Nuker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Shape> shape = sgGeneral.add(new EnumSetting.Builder<Shape>()
        .name("shape")
        .description("The shape of nuking algorithm.")
        .defaultValue(Shape.Sphere)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("The way the blocks are broken.")
        .defaultValue(Mode.Flatten)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The break range, from your eyes to the closest point of the block like the server measures it. 4.5 is vanilla reach.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(5.5)
        .visible(() -> shape.get() != Shape.Cube)
        .build()
    );

    private final Setting<Integer> range_up = sgGeneral.add(new IntSetting.Builder()
        .name("up")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> range_down = sgGeneral.add(new IntSetting.Builder()
        .name("down")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> range_left = sgGeneral.add(new IntSetting.Builder()
        .name("left")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> range_right = sgGeneral.add(new IntSetting.Builder()
        .name("right")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> range_forward = sgGeneral.add(new IntSetting.Builder()
        .name("forward")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> range_back = sgGeneral.add(new IntSetting.Builder()
        .name("back")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Double> wallsRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to break blocks you can't see, from your eyes to the closest point of the block.")
        .defaultValue(4.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between breaking blocks.")
        .defaultValue(0)
        .build()
    );

    private final Setting<Integer> maxBlocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks-per-tick")
        .description("Most blocks to break per tick. Only blocks the server breaks within a tick can go more than one at a time.")
        .defaultValue(1)
        .min(1)
        .build()
    );

    private final Setting<Integer> packetsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("packets-per-tick")
        .description("Most dig, swing and slot packets per tick, counting other modules', so the server doesn't kick you.")
        .defaultValue(7)
        .min(2)
        .sliderMax(20)
        .build()
    );

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("The blocks you want to mine first.")
        .defaultValue(SortMode.Closest)
        .build()
    );

    private final Setting<Boolean> suitableTools = sgGeneral.add(new BoolSetting.Builder()
        .name("only-suitable-tools")
        .description("Only mines when using an appropriate for the block.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> interact = sgGeneral.add(new BoolSetting.Builder()
        .name("interact")
        .description("Interacts with the block instead of mining.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at each block before digging it, server-side. Blocks in a line along the look break together.")
        .defaultValue(true)
        .build()
    );

    // Whitelist and blacklist

    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("Selection mode.")
        .defaultValue(ListMode.Blacklist)
        .build()
    );

    private final Setting<List<Block>> blacklist = sgWhitelist.add(new BlockListSetting.Builder()
        .name("blacklist")
        .description("The blocks you don't want to mine.")
        .visible(() -> listMode.get() == ListMode.Blacklist)
        .build()
    );

    private final Setting<List<Block>> whitelist = sgWhitelist.add(new BlockListSetting.Builder()
        .name("whitelist")
        .description("The blocks you want to mine.")
        .visible(() -> listMode.get() == ListMode.Whitelist)
        .build()
    );

    private final Setting<Keybind> selectBlockBind = sgWhitelist.add(new KeybindSetting.Builder()
        .name("select-block-bind")
        .description("Adds targeted block to list when this button is pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    // Rendering

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Whether to swing hand client-side.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableRenderBounding = sgRender.add(new BoolSetting.Builder()
        .name("bounding-box")
        .description("Enable rendering bounding box for Cube and Uniform Cube.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeModeBox = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("nuke-box-mode")
        .description("How the shape for the bounding box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColorBox = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the bounding box.")
        .defaultValue(new SettingColor(16, 106, 144, 100))
        .build()
    );

    private final Setting<SettingColor> lineColorBox = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the bounding box.")
        .defaultValue(new SettingColor(16, 106, 144, 255))
        .build()
    );

    private final Setting<Boolean> enableRenderBreaking = sgRender.add(new BoolSetting.Builder()
        .name("broken-blocks")
        .description("Enable rendering bounding box for Cube and Uniform Cube.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeModeBreak = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("nuke-block-mode")
        .description("How the shapes for broken blocks are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(enableRenderBreaking::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the target block rendering.")
        .defaultValue(new SettingColor(255, 0, 0, 80))
        .visible(enableRenderBreaking::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the target block rendering.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(enableRenderBreaking::get)
        .build()
    );

    private static final List<AABB> FULL_BLOCK = List.of(new AABB(0, 0, 0, 1, 1, 1));
    private static final int ROTATION_PRIORITY = 10;

    private final List<BlockPos> blocks = new ArrayList<>();
    private final Set<BlockPos> interacted = new ObjectOpenHashSet<>();

    // the block in the server's regular dig slot that this started, which it finishes, parks or gives up
    private BlockPos digging;
    private int timer;
    // the slot selected before switching to a tool, to go back to with Auto Tool's switch back, or -1
    private int previousSlot = -1;

    private final BlockPos.MutableBlockPos pos1 = new BlockPos.MutableBlockPos(); // Rendering for cubes
    private final BlockPos.MutableBlockPos pos2 = new BlockPos.MutableBlockPos();
    int maxh = 0;
    int maxv = 0;

    public Nuker() {
        super(Categories.World, "nuker", "Breaks blocks around you. Follows Speed Mine's damage mode settings.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        digging = null;
        previousSlot = -1;
        interacted.clear();
    }

    @Override
    public void onDeactivate() {
        ServerDigSlots.Dig regular = ServerDigSlots.regular();
        if (digging != null && regular != null && regular.pos.equals(digging) && mc.getConnection() != null) ServerDigSlots.abort();
        digging = null;
        switchBack();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (enableRenderBounding.get()) {
            // Render bounding box if cube and should break stuff
            if (shape.get() != Shape.Sphere && mode.get() != Mode.Smash) {
                int minX = Math.min(pos1.getX(), pos2.getX());
                int minY = Math.min(pos1.getY(), pos2.getY());
                int minZ = Math.min(pos1.getZ(), pos2.getZ());
                int maxX = Math.max(pos1.getX(), pos2.getX());
                int maxY = Math.max(pos1.getY(), pos2.getY());
                int maxZ = Math.max(pos1.getZ(), pos2.getZ());
                event.renderer.box(minX, minY, minZ, maxX, maxY, maxZ, sideColorBox.get(), lineColorBox.get(), shapeModeBox.get(), 0);
            }
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Press) addTargetedBlockToList();
    }

    @EventHandler
    private void onKey(KeyInputEvent event) {
        if (event.action == KeyAction.Press) addTargetedBlockToList();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (timer > 0) timer--;

        // Calculate some stuff
        double pX = mc.player.getX(), pY = mc.player.getY(), pZ = mc.player.getZ();
        double rangeSq = Math.pow(range.get(), 2);
        BlockPos playerBlockPos = mc.player.blockPosition();

        double pX_ = pX;
        double pZ_ = pZ;
        // Uniform cube works on whole blocks, so round the range locally instead of writing it back to the setting
        int r = (int) Math.round(range.get());

        // How far the block iterator has to reach to cover the shape
        int horizontalRange, verticalRange;

        if (shape.get() == Shape.UniformCube) {
            pX_ += 1; // weird position stuff
            pos1.set(pX_ - r, pY - r + 1, pZ - r + 1); // down
            pos2.set(pX_ + r - 1, pY + r, pZ + r); // up
            maxh = 0;
            maxv = 0;

            // The chebyshev check below uses the rounded radius, so anything past it would only be thrown away
            horizontalRange = r + 1;
            verticalRange = r;
        } else {
            // Only change me if you want to mess with 3D rotations:
            // I messed with it
            Direction direction = mc.player.getDirection();
            switch (direction) {
                case Direction.SOUTH -> {
                    pZ_ += 1;
                    pX_ += 1;
                    pos1.set(pX_ - (range_right.get() + 1), Math.ceil(pY) - range_down.get(), pZ_ - (range_back.get() + 1)); // down
                    pos2.set(pX_ + range_left.get(), Math.ceil(pY + range_up.get() + 1), pZ_ + range_forward.get()); // up
                }
                case Direction.WEST -> {
                    pos1.set(pX_ - range_forward.get(), Math.ceil(pY) - range_down.get(), pZ_ - range_right.get()); // down
                    pos2.set(pX_ + range_back.get() + 1, Math.ceil(pY + range_up.get() + 1), pZ_ + range_left.get() + 1); // up
                }
                case Direction.NORTH -> {
                    pX_ += 1;
                    pZ_ += 1;
                    pos1.set(pX_ - (range_left.get() + 1), Math.ceil(pY) - range_down.get(), pZ_ - (range_forward.get() + 1)); // down
                    pos2.set(pX_ + range_right.get(), Math.ceil(pY + range_up.get() + 1), pZ_ + range_back.get()); // up
                }
                case Direction.EAST -> {
                    pX_ += 1;
                    pos1.set(pX_ - (range_back.get() + 1), Math.ceil(pY) - range_down.get(), pZ_ - range_left.get()); // down
                    pos2.set(pX_ + range_forward.get(), Math.ceil(pY + range_up.get() + 1), pZ_ + range_right.get() + 1); // up
                }
            }

            // get largest horizontal
            maxh = 1 + Math.max(Math.max(Math.max(range_back.get(), range_right.get()), range_forward.get()), range_left.get());
            maxv = 1 + Math.max(range_up.get(), range_down.get());

            // the range is from the eyes, which are above the feet block the iterator centres on
            horizontalRange = (int) Math.ceil(range.get() + 1);
            verticalRange = (int) Math.ceil(range.get() + 2);
        }

        // Flatten
        if (mode.get() == Mode.Flatten) pos1.setY((int) Math.floor(pY + 0.5));

        AABB box = new AABB(pos1.getCenter(), pos2.getCenter());

        Vec3 eye = mc.player.getEyePosition();
        // the server turns down digs past its reach
        double serverReach = mc.player.blockInteractionRange() + 1 - 0.1;
        double serverReachSq = serverReach * serverReach;
        double wallsRangeSq = wallsRange.get() * wallsRange.get();
        ServerDigSlots.Dig regular = ServerDigSlots.regular();
        BlockPos regularPos = regular != null ? regular.pos : null;

        // Find blocks to break
        BlockIterator.register(Math.max(horizontalRange, maxh), Math.max(verticalRange, maxv), (blockPos, blockState) -> {
            double reachSq = new AABB(blockPos).distanceToSqr(eye);
            if (reachSq >= serverReachSq) return;

            switch (shape.get()) {
                case Sphere -> {
                    if (reachSq > rangeSq) return;
                }
                case UniformCube -> {
                    if (chebyshevDist(playerBlockPos.getX(), playerBlockPos.getY(), playerBlockPos.getZ(), blockPos.getX(), blockPos.getY(), blockPos.getZ()) >= r)
                        return;
                }
                case Cube -> {
                    if (!box.contains(blockPos.getCenter())) return;
                }
            }

            // Flatten
            if (mode.get() == Mode.Flatten && blockPos.getY() + 0.5 < pY) return;

            // Smash
            if (mode.get() == Mode.Smash && blockState.getDestroySpeed(mc.level, blockPos) != 0) return;

            // Use only optimal tools
            if (suitableTools.get() && !interact.get() && !toolFor(blockState).isCorrectToolForDrops(blockState))
                return;

            // Block must be breakable
            if (!BlockUtils.canBreak(blockPos, blockState) && !interact.get()) return;

            // Out of sight
            if (wallsRangeSq < reachSq && !isVisible(blockPos, eye)) return;

            // Check whitelist or blacklist
            if (listMode.get() == ListMode.Whitelist && !whitelist.get().contains(blockState.getBlock())) return;
            if (listMode.get() == ListMode.Blacklist && blacklist.get().contains(blockState.getBlock())) return;

            if (interact.get() && interacted.contains(blockPos)) return;

            // Parked in the server's delayed slot, or a stop for it is unanswered: it breaks by itself
            if (ServerDigSlots.isDigging(blockPos) && !blockPos.equals(regularPos)) return;

            // Add block
            blocks.add(blockPos.immutable());
        });

        BlockIterator.after(() -> {
            try {
                // Sort blocks
                if (sortMode.get() == SortMode.TopDown)
                    blocks.sort(Comparator.comparingDouble(value -> -value.getY()));
                else if (sortMode.get() != SortMode.None)
                    blocks.sort(Comparator.comparingDouble(value -> Utils.squaredDistance(pX, pY, pZ, value.getX() + 0.5, value.getY() + 0.5, value.getZ() + 0.5) * (sortMode.get() == SortMode.Closest ? 1 : -1)));

                if (blocks.isEmpty()) interacted.clear();

                if (interact.get()) interactBlocks();
                else breakBlocks();
            } finally {
                blocks.clear();
            }
        });
    }

    /**
     * Breaks what it can this tick, before the movement packet: an anticheat flags digs that come after it. Blocks the
     * server breaks within a tick go first, as many as the limits allow. A block that takes longer is only started once
     * none of those are left, and while it digs nothing else is started, since any start restarts the server's timer on
     * it. With Speed Mine's double mine it's parked in the server's delayed slot instead, to break by itself, and the
     * next one dug alongside it.
     */
    private void breakBlocks() {
        SpeedMine speedMine = Modules.get().get(SpeedMine.class);
        boolean aim = rotate.get();
        float sentYaw = ((LocalPlayerAccessor) mc.player).meteor$getYRotLast();
        float sentPitch = ((LocalPlayerAccessor) mc.player).meteor$getXRotLast();

        int broken = 0;
        boolean sent = false;
        BlockPos aimAt = null;

        // The dig this started
        ServerDigSlots.Dig regular = ServerDigSlots.regular();
        if (digging != null && (regular == null || !regular.pos.equals(digging))) digging = null;
        if (digging != null && !blocks.contains(digging)) {
            // out of range now, or no longer wanted
            ServerDigSlots.abort();
            digging = null;
        }

        if (digging != null) {
            // the server times the dig with whatever is held when the stop arrives
            selectTool(regular.state);

            Block block = regular.state.getBlock();
            boolean finishes = ServerDigSlots.tick() >= ServerDigSlots.finishTick(regular, speedMine.finishesEarly(block));
            // parked, it finishes with whatever is held then, so the next one has to be dug with the same tool
            boolean park = !finishes && speedMine.doubleMines(block) && ServerDigSlots.canPark() && hasOtherBlockForTool();

            if (finishes || park) {
                Direction face = face(digging, aim, sentYaw, sentPitch);
                if (face != null && ActionPackets.fit(1 + swingCost(false) + syncCost(), packetsPerTick.get())) {
                    ServerDigSlots.stop(face, finishes);
                    sent = true;
                    if (finishes) broken++;
                    digging = null;
                }
            }

            if (digging != null) aimAt = digging;
        }

        if (digging == null && timer <= 0) {
            // One tool a tick, as a switch costs a packet. A block parked in the server's delayed slot finishes with
            // whatever is held when its time comes, so its tool stays in hand until it has, and only blocks for the
            // same tool are dug meanwhile. Otherwise it's the one for the first block that breaks within the tick with
            // its own tool, else for the first block
            AutoTool autoTool = autoTool();
            ServerDigSlots.Dig parked = ServerDigSlots.parked();
            if (parked != null) {
                selectTool(parked.state);
            } else {
                BlockState first = null, firstQuick = null;
                for (BlockPos pos : blocks) {
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir() || ServerDigSlots.isDigging(pos)) continue;

                    if (first == null) first = state;
                    if (autoTool == null) break;
                    if (breaksWithin(autoTool.pickSlot(state), state, pos, speedMine)) {
                        firstQuick = state;
                        break;
                    }
                }
                if (first != null) selectTool(firstQuick != null ? firstQuick : first);
            }

            // a block the server breaks within the tick that waits for a later tick, which comes before a slow one
            boolean quickLeft = autoToolRefuses();

            // Blocks the server breaks within the tick
            for (BlockPos pos : blocks) {
                if (quickLeft) break;

                BlockState state = mc.level.getBlockState(pos);
                // broken earlier this tick, or in one of the server's dig slots: a start on it would be a dig on air
                // for an anticheat that saw the stop
                if (state.isAir() || ServerDigSlots.isDigging(pos)) continue;

                // wants another tool, which a later tick holds
                if (!toolReady(state)) {
                    // while a block is parked it has to wait for that one anyway
                    if (parked == null && breaksWithin(autoTool.pickSlot(state), state, pos, speedMine)) quickLeft = true;
                    continue;
                }

                float progress = progress(state, pos);
                if (progress <= 0 && ServerDigSpeed.mustWait(state, pos)) {
                    quickLeft = true;
                    continue;
                }
                boolean instant = progress >= 1.0f;
                if (!instant && (progress < ServerDigSlots.FINISH_PROGRESS || !speedMine.instamines(state.getBlock()))) continue;

                if (broken >= maxBlocksPerTick.get()) {
                    quickLeft = true;
                    break;
                }

                Direction face = face(pos, aim, sentYaw, sentPitch);
                if (face == null) {
                    quickLeft = true;
                    if (aimAt == null) aimAt = pos;
                    continue;
                }

                int packets = (instant ? 1 : 2) + swingCost(sent) + syncCost() + (mc.gameMode.isDestroying() ? 1 : 0);
                if (!ActionPackets.fit(packets, packetsPerTick.get())) {
                    quickLeft = true;
                    break;
                }

                if (instant) ServerDigSlots.breakInstant(pos, face);
                else ServerDigSlots.breakFinishing(pos, face);
                sent = true;
                broken++;
                renderBreaking(pos);
            }

            // A block that takes longer, once nothing quicker is left
            if (!quickLeft) {
                BlockPos next = startDigging(speedMine, aim, sentYaw, sentPitch);
                if (aimAt == null) aimAt = next;
            }
            if (digging != null) {
                sent = true;
                aimAt = digging;
            }
        }

        // like a held attack, and once for every dig packet in the tick
        if (sent || digging != null) {
            if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            else mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        if (aim && aimAt == null) {
            // what's next once this tick's blocks are gone
            for (BlockPos pos : blocks) {
                if (!mc.level.getBlockState(pos).isAir() && !ServerDigSlots.isDigging(pos)) {
                    aimAt = pos;
                    break;
                }
            }
        }
        if (aim && aimAt != null) lookAt(aimAt);

        if (broken > 0 && delay.get() > 0) timer = delay.get();

        // done here, and nothing left in the server's dig slots to finish with the tool
        if (blocks.isEmpty() && digging == null && ServerDigSlots.parked() == null) switchBack();
    }

    /**
     * @return Auto Tool when it's on, which picks the tool for each block
     */
    private AutoTool autoTool() {
        AutoTool autoTool = Modules.get().get(AutoTool.class);
        return autoTool.isActive() && !mc.player.isCreative() ? autoTool : null;
    }

    /**
     * @return what the block would be broken with: Auto Tool's pick when it's on, else the held item
     */
    private ItemStack toolFor(BlockState state) {
        AutoTool autoTool = autoTool();
        return autoTool != null ? mc.player.getInventory().getItem(autoTool.pickSlot(state)) : mc.player.getMainHandItem();
    }

    /**
     * Selects Auto Tool's pick for the block. Only client-side: the slot goes out with the next dig, or with vanilla's
     * own sync later in the tick, before the movement packet either way.
     */
    private void selectTool(BlockState state) {
        AutoTool autoTool = autoTool();
        if (autoTool == null) return;

        int slot = autoTool.pickSlot(state);
        int selected = mc.player.getInventory().getSelectedSlot();
        if (slot == selected) return;

        if (autoTool.switchesBack() && previousSlot == -1) previousSlot = selected;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    /**
     * @return whether a block other than the one being dug is waiting for the tool in hand
     */
    private boolean hasOtherBlockForTool() {
        for (BlockPos pos : blocks) {
            if (pos.equals(digging) || ServerDigSlots.isDigging(pos)) continue;

            BlockState state = mc.level.getBlockState(pos);
            if (!state.isAir() && toolReady(state)) return true;
        }
        return false;
    }

    /**
     * @return whether the server breaks the block within a tick with the item in this hotbar slot
     */
    private boolean breaksWithin(int slot, BlockState state, BlockPos pos, SpeedMine speedMine) {
        Inventory inventory = mc.player.getInventory();
        int selected = inventory.getSelectedSlot();
        inventory.setSelectedSlot(slot);
        float progress = progress(state, pos);
        inventory.setSelectedSlot(selected);
        return progress >= 1.0f || progress >= ServerDigSlots.FINISH_PROGRESS && speedMine.instamines(state.getBlock());
    }

    /**
     * @return whether the held item is what Auto Tool breaks the block with, when it's on
     */
    private boolean toolReady(BlockState state) {
        AutoTool autoTool = autoTool();
        return autoTool == null || autoTool.pickSlot(state) == mc.player.getInventory().getSelectedSlot();
    }

    /**
     * @return whether Auto Tool's anti-break keeps the held tool from breaking anything
     */
    private boolean autoToolRefuses() {
        AutoTool autoTool = autoTool();
        return autoTool != null && autoTool.refuses(mc.player.getMainHandItem());
    }

    private void switchBack() {
        if (previousSlot == -1) return;

        if (mc.player != null && Modules.get().get(AutoTool.class).switchesBack()) mc.player.getInventory().setSelectedSlot(previousSlot);
        previousSlot = -1;
    }

    /**
     * Starts the first block that takes more than a tick. With double mine and the delayed slot free, it's parked at once
     * and the next one started too, so two dig from the start.
     *
     * @return a block to aim at before it can be started, or null
     */
    private BlockPos startDigging(SpeedMine speedMine, boolean aim, float sentYaw, float sentPitch) {
        for (BlockPos pos : blocks) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir() || ServerDigSlots.isDigging(pos) || !toolReady(state)) continue;

            float progress = progress(state, pos);
            if (progress <= 0 || progress >= 1.0f || progress >= ServerDigSlots.FINISH_PROGRESS && speedMine.instamines(state.getBlock())) continue;

            Direction face = face(pos, aim, sentYaw, sentPitch);
            if (face == null) return pos;

            boolean first = digging == null;
            if (first) {
                if (!ActionPackets.fit(2 + syncCost() + (mc.gameMode.isDestroying() ? 1 : 0), packetsPerTick.get())) return null;
            } else {
                // park the one just started, to break by itself, and dig this one alongside it
                Direction parkFace = face(digging, aim, sentYaw, sentPitch);
                if (parkFace == null || !ActionPackets.fit(2, packetsPerTick.get())) return null;
                ServerDigSlots.stop(parkFace, false);
            }

            ServerDigSlots.start(pos, face);
            digging = pos;
            renderBreaking(pos);

            // a stop right after the start breaks a block of 70% a tick or more instead of parking it
            if (!first || !speedMine.doubleMines(state.getBlock()) || !ServerDigSlots.canPark() || progress >= ServerDigSlots.FINISH_PROGRESS) return null;
        }

        return null;
    }

    private void interactBlocks() {
        boolean aim = rotate.get();
        float sentYaw = ((LocalPlayerAccessor) mc.player).meteor$getYRotLast();
        float sentPitch = ((LocalPlayerAccessor) mc.player).meteor$getXRotLast();

        int count = 0;
        BlockPos aimAt = null;
        for (BlockPos pos : blocks) {
            if (count >= maxBlocksPerTick.get() || timer > 0) break;

            BlockHitResult hit = aim ? hit(pos, sentYaw, sentPitch) : new BlockHitResult(pos.getCenter(), ServerDigSlots.face(pos), pos, false);
            if (hit == null) {
                if (aimAt == null) aimAt = pos;
                continue;
            }

            if (!ActionPackets.fit(2 + syncCost(), packetsPerTick.get())) break;

            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, swing.get());
            interacted.add(pos);
            renderBreaking(pos);
            count++;
        }

        if (aim && aimAt != null) lookAt(aimAt);
        if (count > 0 && delay.get() > 0) timer = delay.get();
    }

    /**
     * @return per-tick progress as the server judges a dig sent now, 0 while it's held for the anticheat's view of it
     */
    private float progress(BlockState state, BlockPos pos) {
        if (mc.player.isCreative()) return 1.0f;
        return ServerDigSpeed.progress(state, pos);
    }

    /**
     * @return the face to dig the block from: where the rotation the server has hits it when aiming, else null if it
     * misses, or the face towards the eye
     */
    private Direction face(BlockPos pos, boolean aim, float yaw, float pitch) {
        if (!aim) return ServerDigSlots.face(pos);

        BlockHitResult hit = hit(pos, yaw, pitch);
        return hit != null ? hit.getDirection() : null;
    }

    /**
     * @return where a look with this rotation hits the block's cell within reach, ignoring what's in front of it like
     * an anticheat's rotation checks do, or null if it misses
     */
    private BlockHitResult hit(BlockPos pos, float yaw, float pitch) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(mc.player.blockInteractionRange()));
        return AABB.clip(FULL_BLOCK, eye, end, pos);
    }

    /**
     * Asks for the next movement packet to look at the block, at its point closest to the eye, so the look reaches it
     * as early as it can. The movement fix may turn it while the look still hits the block.
     */
    private void lookAt(BlockPos pos) {
        Vec3 point = aimPoint(pos, mc.player.getEyePosition());
        Rotations.rotate(Rotations.getYaw(point), Rotations.getPitch(point), ROTATION_PRIORITY, null, (yaw, pitch) -> hit(pos, yaw, pitch) != null);
    }

    /**
     * @return the point of the block's cell closest to the eye, a little inside so a look at it hits the cell
     */
    private static Vec3 aimPoint(BlockPos pos, Vec3 eye) {
        double inset = 0.05;
        return new Vec3(
            Mth.clamp(eye.x, pos.getX() + inset, pos.getX() + 1 - inset),
            Mth.clamp(eye.y, pos.getY() + inset, pos.getY() + 1 - inset),
            Mth.clamp(eye.z, pos.getZ() + inset, pos.getZ() + 1 - inset)
        );
    }

    private boolean isVisible(BlockPos pos, Vec3 eye) {
        BlockHitResult result = mc.level.clip(new ClipContext(eye, aimPoint(pos, eye), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return result.getType() == HitResult.Type.MISS || result.getBlockPos().equals(pos);
    }

    private int swingCost(boolean swung) {
        return swung ? 0 : 1;
    }

    /**
     * @return 1 if the selected slot still has to be sent before a dig, which the server judges with the held item
     */
    private int syncCost() {
        return mc.player.getInventory().getSelectedSlot() != ((MultiPlayerGameModeAccessor) mc.gameMode).meteor$getCarriedIndex() ? 1 : 0;
    }

    private void renderBreaking(BlockPos pos) {
        if (enableRenderBreaking.get())
            RenderUtils.renderTickingBlock(pos, sideColor.get(), lineColor.get(), shapeModeBreak.get(), 0, 8, true, false);
    }

    private void addTargetedBlockToList() {
        if (!selectBlockBind.get().isPressed() || mc.screen != null) return;

        HitResult hitResult = mc.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Block targetBlock = mc.level.getBlockState(pos).getBlock();

        List<Block> list = listMode.get() == ListMode.Whitelist ? whitelist.get() : blacklist.get();
        String modeName = listMode.get().name();

        if (list.contains(targetBlock)) {
            list.remove(targetBlock);
            info("Removed " + Names.get(targetBlock) + " from " + modeName);
        } else {
            list.add(targetBlock);
            info("Added " + Names.get(targetBlock) + " to " + modeName);
        }
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    public enum Mode {
        All,
        Flatten,
        Smash
    }

    public enum SortMode {
        None,
        Closest,
        Furthest,
        TopDown
    }

    public enum Shape {
        Cube,
        UniformCube,
        Sphere
    }

    public static int chebyshevDist(int x1, int y1, int z1, int x2, int y2, int z2) {
        // Gets the largest X, Y or Z difference, chebyshev distance
        int dX = Math.abs(x2 - x1);
        int dY = Math.abs(y2 - y1);
        int dZ = Math.abs(z2 - z1);
        return Math.max(Math.max(dX, dY), dZ);
    }
}
