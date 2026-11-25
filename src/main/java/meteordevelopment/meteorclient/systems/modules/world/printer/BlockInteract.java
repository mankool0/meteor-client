/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world.printer;

import meteordevelopment.meteorclient.mixin.RedStoneWireBlockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A schematic block that is in place but in the wrong state, which right clicking it fixes: note blocks, trapdoors,
 * repeaters, levers and so on, or using an item on it, like a water bottle on a cauldron ({@link ItemUses}).
 * <p>
 * From dino-printer by dinosaurwizard (CC0).
 */
public class BlockInteract {
    private final Printer printer;
    public final BlockPos blockPos;
    // the item to click with, or null to click with whatever is held
    @Nullable
    public final ItemUses.ItemUse use;
    // an item used through a block that would take the click itself, like honeycomb on a copper door, needs sneaking
    public final boolean sneak;
    @Nullable
    public final BlockHitResult hit;

    private BlockInteract(BlockPos blockPos, Printer printer, @Nullable ItemUses.ItemUse use) {
        this.printer = printer;
        this.blockPos = blockPos;
        this.use = use;
        this.sneak = use != null && use.itemFirst() && BlockPrint.isInteractable(blockPos);
        this.hit = calculateBestInteractHit();
    }

    /**
     * @return what clicking the existing block does towards the schematic's, or null if nothing does
     */
    @Nullable
    public static BlockInteract create(BlockPos blockPos, BlockState required, BlockState existing, Printer printer) {
        if (printer.useItems.get()) {
            ItemUses.ItemUse use = ItemUses.find(required, existing, blockPos);
            if (use != null) {
                BlockInteract blockInteract = new BlockInteract(blockPos, printer, use);
                return !blockInteract.sneak || printer.sneakPlace.get() ? blockInteract : null;
            }
        }

        if (printer.interact.get() && isValid(required, existing)) return new BlockInteract(blockPos, printer, null);
        return null;
    }

    /**
     * Whether a click on this face does it: hoes and shovels do nothing on a bottom face.
     */
    public boolean worksOn(Direction face) {
        return use == null || use.faces().test(face);
    }

    /**
     * Whether right clicking the existing block moves it towards the schematic's state.
     */
    public static boolean isValid(BlockState required, BlockState existing) {
        if (required.isAir()) return false;

        Block block = required.getBlock();
        if (block != existing.getBlock()) return false;
        if (block.getStateDefinition().getProperties().isEmpty()) return false;

        // these only open with redstone
        if (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR) return false;

        if (required.hasProperty(BlockStateProperties.NOTE) && existing.hasProperty(BlockStateProperties.NOTE)) {
            return !required.getValue(BlockStateProperties.NOTE).equals(existing.getValue(BlockStateProperties.NOTE));
        } else if (required.hasProperty(BlockStateProperties.OPEN) && existing.hasProperty(BlockStateProperties.OPEN)) {
            return required.getValue(BlockStateProperties.OPEN) != existing.getValue(BlockStateProperties.OPEN);
        } else if (required.hasProperty(BlockStateProperties.INVERTED) && existing.hasProperty(BlockStateProperties.INVERTED)) {
            return required.getValue(BlockStateProperties.INVERTED) != existing.getValue(BlockStateProperties.INVERTED);
        } else if (required.hasProperty(BlockStateProperties.DELAY) && existing.hasProperty(BlockStateProperties.DELAY)) {
            return !required.getValue(BlockStateProperties.DELAY).equals(existing.getValue(BlockStateProperties.DELAY));
        } else if (required.hasProperty(BlockStateProperties.MODE_COMPARATOR) && existing.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
            return required.getValue(BlockStateProperties.MODE_COMPARATOR) != existing.getValue(BlockStateProperties.MODE_COMPARATOR);
        } else if (block instanceof LeverBlock && required.hasProperty(BlockStateProperties.POWERED) && existing.hasProperty(BlockStateProperties.POWERED)) {
            return required.getValue(BlockStateProperties.POWERED) != existing.getValue(BlockStateProperties.POWERED);
        } else if (block instanceof RedStoneWireBlock) {
            return (RedStoneWireBlockAccessor.meteor$isCross(existing) && RedStoneWireBlockAccessor.meteor$isDot(required))
                || (RedStoneWireBlockAccessor.meteor$isCross(required) && RedStoneWireBlockAccessor.meteor$isDot(existing));
        }

        return false;
    }

    public boolean canInteract() {
        return hit != null;
    }

    public float getYaw() {
        return PrinterUtils.yawTo(mc.player.getEyePosition(), hit.getLocation());
    }

    public float getPitch() {
        return PrinterUtils.pitchTo(mc.player.getEyePosition(), hit.getLocation());
    }

    @Nullable
    private BlockHitResult calculateBestInteractHit() {
        // a point the player can see first, which the ray of its rotation finds by itself
        BlockHitResult visible = calculateBestInteractHit(true);
        if (visible != null || !printer.wallPlace.get()) return visible;
        return calculateBestInteractHit(false);
    }

    @Nullable
    private BlockHitResult calculateBestInteractHit(boolean visibleOnly) {
        for (Direction direction : Direction.values()) {
            if (!worksOn(direction)) continue;
            if (!PrinterUtils.reachesNowAndNext(blockPos, direction, printer.placeRange.get(), printer.strictFaces.get())) continue;

            for (Vec3 point : PrinterUtils.getShapeFacePoints(blockPos, direction)) {
                if (visibleOnly ? !PrinterUtils.isPointVisible(point, blockPos, direction) : !isPointValid(point, direction)) continue;
                return new BlockHitResult(point, direction, blockPos, false);
            }
        }

        return null;
    }

    public boolean isHitStillValid() {
        return hit != null
            && PrinterUtils.reachesNowAndNext(blockPos, hit.getDirection(), printer.placeRange.get(), printer.strictFaces.get())
            && isPointValid(hit.getLocation(), hit.getDirection());
    }

    private boolean isPointValid(Vec3 point, Direction direction) {
        return printer.wallPlace.get() || PrinterUtils.isPointVisible(point, blockPos, direction);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof BlockInteract other && blockPos.equals(other.blockPos);
    }

    @Override
    public int hashCode() {
        return blockPos.hashCode();
    }
}
