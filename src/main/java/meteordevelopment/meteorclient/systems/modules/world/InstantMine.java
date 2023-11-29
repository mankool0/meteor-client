/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

public class InstantMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> visualize = sgRender.add(new BoolSetting.Builder()
        .name("Visualize")
        .description("Visualizes which block is being mined")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("AutoBreak")
        .description("Automatically mines selected block")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pickOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("PickOnly")
        .description("Only mines when holding a pickaxe")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("Delay")
        .description("Delay of the mining in ms")
        .defaultValue(20)
        .sliderMax(500)
        .range(0, 500)
        .build()
    );

    private final Setting<Boolean> offhandEChest = sgGeneral.add(new BoolSetting.Builder()
        .name("OffhandEChest")
        .description("Automatically put EChests into empty offhand")
        .defaultValue(false)
        .build()
    );

    private BlockPos renderBlock;
    private final Color renderColor = new Color(0, 255, 255, 144);
    private BlockPos lastBlock;
    private boolean packetCancel = false;
    private long breakTimer = 0;
    private Direction direction;

    public InstantMine() {
        super(Categories.World, "instant-mine", "Instantly Mines Blocks");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (visualize.get() && renderBlock != null && mc.world != null) {
            VoxelShape shape = mc.world.getBlockState(renderBlock).getOutlineShape(mc.world, renderBlock);

            double x1 = renderBlock.getX();
            double y1 = renderBlock.getY();
            double z1 = renderBlock.getZ();
            double x2 = renderBlock.getX() + 1;
            double y2 = renderBlock.getY() + 1;
            double z2 = renderBlock.getZ() + 1;

            if (!shape.isEmpty()) {
                x1 = renderBlock.getX() + shape.getMin(Direction.Axis.X);
                y1 = renderBlock.getY() + shape.getMin(Direction.Axis.Y);
                z1 = renderBlock.getZ() + shape.getMin(Direction.Axis.Z);
                x2 = renderBlock.getX() + shape.getMax(Direction.Axis.X);
                y2 = renderBlock.getY() + shape.getMax(Direction.Axis.Y);
                z2 = renderBlock.getZ() + shape.getMax(Direction.Axis.Z);
            }

            event.renderer.box(x1, y1, z1, x2, y2, z2, renderColor, renderColor, ShapeMode.Both, 0);
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (renderBlock == null) {
            return;
        }

        if (offhandEChest.get() && mc.player.getOffHandStack().isEmpty()) {
            FindItemResult result = InvUtils.find(Items.ENDER_CHEST);
            if (result.count() > 0)
                InvUtils.move().from(result.slot()).toOffhand();
        }

        if (autoBreak.get() && (System.currentTimeMillis() - breakTimer > delay.get())) {
            if (pickOnly.get() && !(mc.player.getMainHandStack().getItem() instanceof PickaxeItem)) {
                return;
            }
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, renderBlock, direction));
            breakTimer = System.currentTimeMillis();

            ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).setBlockBreakingCooldown(0);
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        Packet packet = event.packet;
        if (packet instanceof PlayerActionC2SPacket) {
            if (((PlayerActionC2SPacket) packet).getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK && packetCancel) {
                event.cancel();
            }
        }
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (canBreak(event.blockPos)) {
            if (lastBlock == null || event.blockPos.getX() != lastBlock.getX() || event.blockPos.getY() != lastBlock.getY() || event.blockPos.getZ() != lastBlock.getZ()) {
                packetCancel = false;
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, event.blockPos, event.direction));

                renderBlock = event.blockPos;
                lastBlock = event.blockPos;
                direction = event.direction;

                event.cancel();
            }
        }
    }

    private boolean canBreak(BlockPos pos) {
        final BlockState blockState = mc.world.getBlockState(pos);
        return blockState.getHardness(mc.world, pos) != -1;
    }
}
