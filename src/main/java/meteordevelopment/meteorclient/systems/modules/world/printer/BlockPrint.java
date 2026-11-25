/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world.printer;

import meteordevelopment.meteorclient.mixin.BlockItemAccessor;
import meteordevelopment.meteorclient.utils.world.PendingPlaces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A schematic block the printer can place, and the click that places it in the state the schematic wants. Simulates
 * the placement from each candidate click and rotation rather than assuming how a block places, so it handles nearly
 * every block.
 * <p>
 * From dino-printer by dinosaurwizard (CC0). Every check here takes the rotation and sneak state the click will
 * actually be made with, so the printer can also judge a click it found by raytracing the rotation the server already
 * has.
 */
public class BlockPrint {
    private static final BooleanProperty[] MULTIFACE_PROPERTIES = {
        BlockStateProperties.UP, BlockStateProperties.DOWN, BlockStateProperties.EAST,
        BlockStateProperties.NORTH, BlockStateProperties.SOUTH, BlockStateProperties.WEST
    };

    // yaws to simulate for blocks with the 16 step rotation, signs and banners
    private static final List<Float> ROTATION_YAWS = List.of(
        0.0f, 22.5f, 45.0f, 67.5f, 90.0f, 112.5f, 135.0f, 157.5f,
        180.0f, 202.5f, 225.0f, 247.5f, 270.0f, 292.5f, 315.0f, 337.5f
    );
    private static final List<Float> CARDINAL_YAWS = List.of(0.0f, 90.0f, 180.0f, 270.0f);
    private static final List<Float> VERTICAL_PITCHES = List.of(0.0f, 90.0f, -90.0f);

    private final Printer printer;
    public final BlockPos blockPos;
    public final BlockState required;
    // what gets placed: the schematic's block, or the base an item use turns into it, like a cauldron for a filled one
    public final BlockState placeState;
    public final BlockState existing;
    public final boolean fluid;
    private final boolean rotatable;

    // the chosen click, null if there is none
    @Nullable
    public BlockHitResult hit;
    public float yaw, pitch;
    public boolean sneaking;

    public BlockPrint(BlockPos blockPos, BlockState required, BlockState existing, Printer printer) {
        this.printer = printer;
        this.blockPos = blockPos;
        this.required = required;
        this.placeState = printer.placeStateFor(required);
        this.existing = existing;

        this.fluid = required.getBlock() instanceof LiquidBlock;
        this.rotatable = isRotatable();
    }

    /**
     * Whether the schematic wants something placed here that the printer can place.
     */
    public static boolean isValid(BlockPos blockPos, BlockState required, BlockState existing, Printer printer) {
        if (required.isAir()) return false;

        boolean fluid = required.getBlock() instanceof LiquidBlock;
        if (fluid && (!required.getFluidState().isSource() || !printer.fluids.get())) return false;

        if (!printer.incrementalStates.get() || !isIncremental(required, existing)) {
            // spot must be air or some other replaceable block
            if (!existing.canBeReplaced()) return false;

            // spot is not already the required block
            if (required.getBlock() == existing.getBlock() && (!fluid || existing.getFluidState().isSource())) return false;
        }

        // don't place where a place is waiting on the server, or one failed a moment ago
        if (printer.isCached(blockPos)) return false;

        BlockState placeState = printer.placeStateFor(required);

        // torches and the like only go on another block
        if (!placeState.canSurvive(mc.level, blockPos)) return false;

        // no entities in the way
        return mc.level.isUnobstructed(placeState, blockPos, CollisionContext.empty());
    }

    /**
     * Whether placing this block depends on the exact rotation sent, so the printer has to send that rotation itself and
     * place with it the tick after.
     */
    public boolean needsExactRotation() {
        return (printer.rotationPlace.get() && rotatable) || fluid;
    }

    /**
     * Whether the printer has to rotate to place this block at all.
     */
    public boolean needsRotation() {
        return printer.isRotating() || needsExactRotation();
    }

    private boolean isRotatable() {
        return placeState.hasProperty(BlockStateProperties.FACING)
            || placeState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            || placeState.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
            || placeState.hasProperty(BlockStateProperties.AXIS)
            || placeState.hasProperty(BlockStateProperties.FACING_HOPPER)
            || placeState.hasProperty(BlockStateProperties.ORIENTATION)
            || placeState.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
            || placeState.hasProperty(BlockStateProperties.ROTATION_16);
    }

    /**
     * Picks the best click for this block from where the player stands, along with the rotation and sneak state to
     * make it with, and stores it in {@link #hit}, {@link #yaw}, {@link #pitch} and {@link #sneaking}.
     *
     * @param serverYaw   the rotation the server has now, which a click made without rotating uses
     * @param serverPitch see {@code serverYaw}
     * @return whether there is one
     */
    public boolean findHit(float serverYaw, float serverPitch) {
        hit = null;
        Vec3 eye = mc.player.getEyePosition();

        // A rotated click on a point the player can see is one the ray of that rotation finds by itself, which lets
        // the movement fix turn it and the blocks after it along the ray go out in the same tick. So those first, then
        // the ones through walls.
        if (needsRotation() && printer.wallPlace.get() && !fluid && findHit(eye, serverYaw, serverPitch, true)) return true;
        return findHit(eye, serverYaw, serverPitch, false);
    }

    private boolean findHit(Vec3 eye, float serverYaw, float serverPitch, boolean visibleOnly) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = blockPos.relative(direction);
            if (mc.level.getBlockState(adjacent).canBeReplaced()) continue;

            Direction face = direction.getOpposite();
            if (!PrinterUtils.reachesNowAndNext(adjacent, face, printer.placeRange.get(), printer.strictFaces.get())) continue;

            for (Vec3 point : PrinterUtils.getShapeFacePoints(adjacent, face)) {
                if (visibleOnly ? !PrinterUtils.isPointVisible(point, adjacent, face) : !isPointVisibleEnough(point, adjacent, face)) continue;

                if (tryHit(new BlockHitResult(point, face, adjacent, false), eye, serverYaw, serverPitch)) return true;
            }
        }

        // nothing to place against, so click our own position: air placing, or adding to an incremental block
        boolean incremental = printer.incrementalStates.get() && required.getBlock() == existing.getBlock();
        if ((printer.airPlace.get() || incremental) && !fluid) {
            for (Direction direction : Direction.values()) {
                if (!PrinterUtils.reachesNowAndNext(blockPos, direction, printer.placeRange.get(), printer.strictFaces.get())) continue;

                for (Vec3 point : PrinterUtils.getShapeFacePoints(blockPos, direction)) {
                    if (visibleOnly ? !PrinterUtils.isPointVisible(point, blockPos, direction) : !isPointVisibleEnough(point, blockPos, direction)) continue;

                    if (tryHit(new BlockHitResult(point, direction, blockPos, false), eye, serverYaw, serverPitch)) return true;
                }
            }
        }

        return false;
    }

    private boolean tryHit(BlockHitResult candidate, Vec3 eye, float serverYaw, float serverPitch) {
        float legitYaw = PrinterUtils.yawTo(eye, candidate.getLocation());
        float legitPitch = PrinterUtils.pitchTo(eye, candidate.getLocation());

        if (!needsRotation()) {
            // placed without turning, so with whatever rotation the server already has
            return trySneaks(candidate, serverYaw, serverPitch);
        }

        // place with a rotation that doesn't look at the block, only for its facing
        if (printer.rotationPlace.get() && rotatable && !printer.strictRotation.get()) {
            for (float rotateYaw : getSimulationYaws(legitYaw)) {
                for (float rotatePitch : getSimulationPitches(legitPitch)) {
                    if (trySneaks(candidate, rotateYaw, rotatePitch)) return true;
                }
            }
        }

        return trySneaks(candidate, legitYaw, legitPitch);
    }

    private boolean trySneaks(BlockHitResult candidate, float yaw, float pitch) {
        Boolean sneak = sneakFor(candidate, yaw, pitch);
        if (sneak == null) return false;

        this.hit = candidate;
        this.yaw = yaw;
        this.pitch = pitch;
        this.sneaking = sneak;
        return true;
    }

    /**
     * @return the sneak state that makes this click place the block the schematic wants, not sneaking when either
     * does, or null if neither does
     */
    @Nullable
    public Boolean sneakFor(BlockHitResult candidate, float yaw, float pitch) {
        boolean sneakingNow = printer.isPlayerSneaking();

        // not sneaking first: sneaking costs two more packets
        if (!sneakingNow && matches(candidate, yaw, pitch, false)) return false;
        if ((printer.sneakPlace.get() || sneakingNow) && matches(candidate, yaw, pitch, true)) return true;
        return null;
    }

    /**
     * Whether clicking {@code candidate} with this rotation and sneak state places the block the schematic wants.
     */
    public boolean matches(BlockHitResult candidate, float yaw, float pitch, boolean sneak) {
        if (!isMatchingProperties(candidate, yaw, pitch, sneak)) return false;
        return !printer.rotationPlace.get() || !rotatable || isMatchingDirection(candidate, yaw, pitch, sneak);
    }

    /**
     * Whether the click point is fine to use: always through walls when allowed, else it has to be visible.
     */
    public boolean isPointVisibleEnough(Vec3 point, BlockPos pos, Direction direction) {
        if (printer.wallPlace.get() && !fluid) return true;
        return PrinterUtils.isPointVisible(point, pos, direction);
    }

    /**
     * Whether {@link #hit} is still in reach and in sight.
     */
    public boolean isHitStillValid() {
        return hit != null
            && PrinterUtils.reachesNowAndNext(hit.getBlockPos(), hit.getDirection(), printer.placeRange.get(), printer.strictFaces.get())
            && isPointVisibleEnough(hit.getLocation(), hit.getBlockPos(), hit.getDirection());
    }

    /**
     * The block this click would place, which is what the server makes of it.
     */
    @Nullable
    private BlockState getSimulatedPlaceState(float yaw, float pitch, boolean sneak, BlockHitResult blockHit) {
        ItemStack stack = asItem().getDefaultInstance();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;

        PrinterPlaceContext context = new PrinterPlaceContext(mc.player, yaw, pitch, sneak, InteractionHand.MAIN_HAND, stack, blockHit);
        // the click has to land in our cell, not replace the clicked block or go next to it instead
        if (!context.getClickedPos().equals(blockPos)) return null;
        if (!existing.canBeReplaced(context)) return null;

        return ((BlockItemAccessor) blockItem).meteor$getPlacementState(context);
    }

    private boolean isMatchingProperties(BlockHitResult blockHit, float yaw, float pitch, boolean sneak) {
        Block block = placeState.getBlock();

        // a bucket is used on the air, not on the clicked block
        if (fluid) return true;

        // the clicked block would take the click itself
        if (!sneak && isInteractable(blockHit.getBlockPos())) return false;

        // no properties worth matching
        if (block instanceof TripWireBlock || block instanceof TripWireHookBlock) return true;
        if (block.getStateDefinition().getProperties().isEmpty()) return true;

        BlockState simulated = getSimulatedPlaceState(yaw, pitch, sneak, blockHit);
        if (simulated == null) return false;

        // a different block, e.g. a standing sign for a wall sign
        if (block != simulated.getBlock()) return false;

        if (printer.halfBlocks.get()) {
            if (placeState.hasProperty(BlockStateProperties.SLAB_TYPE) && simulated.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                SlabType requiredType = placeState.getValue(BlockStateProperties.SLAB_TYPE);
                SlabType simulatedType = simulated.getValue(BlockStateProperties.SLAB_TYPE);
                if (requiredType == SlabType.DOUBLE) {
                    if (existing.hasProperty(BlockStateProperties.SLAB_TYPE) && simulatedType != SlabType.DOUBLE) return false;
                } else if (requiredType != simulatedType) return false;
            } else if (placeState.hasProperty(BlockStateProperties.HALF) && simulated.hasProperty(BlockStateProperties.HALF)) {
                if (placeState.getValue(BlockStateProperties.HALF) != simulated.getValue(BlockStateProperties.HALF)) return false;
            }
        }

        if (printer.miscStates.get()) {
            if (placeState.hasProperty(BlockStateProperties.DOOR_HINGE) && simulated.hasProperty(BlockStateProperties.DOOR_HINGE)) {
                if (placeState.getValue(BlockStateProperties.DOOR_HINGE) != simulated.getValue(BlockStateProperties.DOOR_HINGE)) return false;
            } else if (placeState.hasProperty(BlockStateProperties.ATTACH_FACE) && simulated.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                if (placeState.getValue(BlockStateProperties.ATTACH_FACE) != simulated.getValue(BlockStateProperties.ATTACH_FACE)) return false;
            } else if (placeState.hasProperty(BlockStateProperties.BELL_ATTACHMENT) && simulated.hasProperty(BlockStateProperties.BELL_ATTACHMENT)) {
                if (placeState.getValue(BlockStateProperties.BELL_ATTACHMENT) != simulated.getValue(BlockStateProperties.BELL_ATTACHMENT)) return false;
            } else if (placeState.hasProperty(BlockStateProperties.ATTACHED) && simulated.hasProperty(BlockStateProperties.ATTACHED)) {
                if (placeState.getValue(BlockStateProperties.ATTACHED) != simulated.getValue(BlockStateProperties.ATTACHED)) return false;
            } else if (placeState.hasProperty(BlockStateProperties.HANGING) && simulated.hasProperty(BlockStateProperties.HANGING)) {
                if (placeState.getValue(BlockStateProperties.HANGING) != simulated.getValue(BlockStateProperties.HANGING)) return false;
            } else if (placeState.hasProperty(BlockStateProperties.BED_PART) && simulated.hasProperty(BlockStateProperties.BED_PART)) {
                if (placeState.getValue(BlockStateProperties.BED_PART) != simulated.getValue(BlockStateProperties.BED_PART)) return false;
            } else if (placeState.hasProperty(BlockStateProperties.CHEST_TYPE) && simulated.hasProperty(BlockStateProperties.CHEST_TYPE)) {
                if (!isMatchingChest(simulated)) return false;
            }
        }

        if (printer.incrementalStates.get()) {
            // snow layers, turtle eggs, candles, pickles, flowers, leaf litter: the click has to add one
            if (!isIncreasing(simulated, BlockStateProperties.LAYERS)) return false;
            if (!isIncreasing(simulated, BlockStateProperties.EGGS)) return false;
            if (!isIncreasing(simulated, BlockStateProperties.CANDLES)) return false;
            if (!isIncreasing(simulated, BlockStateProperties.PICKLES)) return false;
            if (!isIncreasing(simulated, BlockStateProperties.FLOWER_AMOUNT)) return false;
            if (!isIncreasing(simulated, BlockStateProperties.SEGMENT_AMOUNT)) return false;

            // vines, glow lichen, sculk veins: the click has to add a face the schematic wants
            if (block instanceof MultifaceBlock || block instanceof VineBlock) {
                for (BooleanProperty property : MULTIFACE_PROPERTIES) {
                    if (isMatchingPropertyMultiFace(simulated, property)) return true;
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Whether a chest pairs up the way the schematic's does. Half of a double chest goes down single while its partner
     * isn't there to join, turned so the partner can join it later; once the partner is there it has to join it. A
     * click that pairs it with another chest instead, or leaves it single next to its partner, makes two singles.
     */
    private boolean isMatchingChest(BlockState simulated) {
        ChestType requiredType = placeState.getValue(BlockStateProperties.CHEST_TYPE);
        ChestType simulatedType = simulated.getValue(BlockStateProperties.CHEST_TYPE);
        if (requiredType == ChestType.SINGLE) return simulatedType == ChestType.SINGLE;

        // the same neighbour, whichever way it faces when the facing isn't respected
        Direction toPartner = ChestBlock.getConnectedDirection(placeState);
        if (simulatedType != ChestType.SINGLE) return ChestBlock.getConnectedDirection(simulated) == toPartner;

        // facing along the pair, nothing can join it from the side
        if (simulated.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == toPartner.getAxis()) return false;

        BlockPos partnerPos = blockPos.relative(toPartner);
        BlockState partner = mc.level.getBlockState(partnerPos);
        if (!(placeState.getBlock() instanceof ChestBlock chest) || !chest.chestCanConnectTo(partner)) {
            // the partner's place is on its way and the client couldn't show it, so wait to see how it came out
            if (PendingPlaces.of(mc.level).isInFlight(partnerPos)) return false;

            // The partner may have to join by sneaking against this chest's side towards it, which with strict faces
            // has to face the player, so the half farther from the player goes down first.
            if (printer.strictFaces.get()) {
                Direction.Axis axis = toPartner.getAxis();
                int step = toPartner.getAxisDirection().getStep();
                double plane = blockPos.get(axis) + (step > 0 ? 1 : 0);
                return (mc.player.position().get(axis) - plane) * step >= 0;
            }
            return true;
        }

        // a single partner some click can join, by sneaking against its side, so not single next to it
        return partner.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
            || partner.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == toPartner.getAxis();
    }

    private boolean isIncreasing(BlockState simulated, net.minecraft.world.level.block.state.properties.IntegerProperty property) {
        if (!simulated.hasProperty(property) || !existing.hasProperty(property)) return true;
        return simulated.getValue(property) > existing.getValue(property);
    }

    private boolean isMatchingPropertyMultiFace(BlockState simulated, BooleanProperty property) {
        return simulated.hasProperty(property) && placeState.hasProperty(property)
            && simulated.getValue(property) && placeState.getValue(property)
            && (!existing.hasProperty(property) || !existing.getValue(property));
    }

    private boolean isMatchingDirection(BlockHitResult blockHit, float yaw, float pitch, boolean sneak) {
        BlockState simulated = getSimulatedPlaceState(yaw, pitch, sneak, blockHit);
        if (simulated == null) return false;

        if (placeState.hasProperty(BlockStateProperties.FACING) && simulated.hasProperty(BlockStateProperties.FACING)) {
            return placeState.getValue(BlockStateProperties.FACING) == simulated.getValue(BlockStateProperties.FACING);
        } else if (placeState.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && simulated.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return placeState.getValue(BlockStateProperties.HORIZONTAL_FACING) == simulated.getValue(BlockStateProperties.HORIZONTAL_FACING);
        } else if (placeState.hasProperty(BlockStateProperties.HORIZONTAL_AXIS) && simulated.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return placeState.getValue(BlockStateProperties.HORIZONTAL_AXIS) == simulated.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        } else if (placeState.hasProperty(BlockStateProperties.AXIS) && simulated.hasProperty(BlockStateProperties.AXIS)) {
            return placeState.getValue(BlockStateProperties.AXIS) == simulated.getValue(BlockStateProperties.AXIS);
        } else if (placeState.hasProperty(BlockStateProperties.FACING_HOPPER) && simulated.hasProperty(BlockStateProperties.FACING_HOPPER)) {
            return placeState.getValue(BlockStateProperties.FACING_HOPPER) == simulated.getValue(BlockStateProperties.FACING_HOPPER);
        } else if (placeState.hasProperty(BlockStateProperties.ORIENTATION) && simulated.hasProperty(BlockStateProperties.ORIENTATION)) {
            return placeState.getValue(BlockStateProperties.ORIENTATION) == simulated.getValue(BlockStateProperties.ORIENTATION);
        } else if (placeState.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) && simulated.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)) {
            return placeState.getValue(BlockStateProperties.VERTICAL_DIRECTION) == simulated.getValue(BlockStateProperties.VERTICAL_DIRECTION);
        } else if (placeState.hasProperty(BlockStateProperties.ROTATION_16) && simulated.hasProperty(BlockStateProperties.ROTATION_16)) {
            return placeState.getValue(BlockStateProperties.ROTATION_16).equals(simulated.getValue(BlockStateProperties.ROTATION_16));
        }

        return true;
    }

    /**
     * Whether another click can add to the existing block towards the schematic's: double slabs, snow layers and so on.
     */
    public static boolean isIncremental(BlockState required, BlockState existing) {
        Block block = required.getBlock();
        if (block.getStateDefinition().getProperties().isEmpty()) return false;
        if (block != existing.getBlock()) return false;

        if (required.hasProperty(BlockStateProperties.SLAB_TYPE) && existing.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return required.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE && existing.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
        } else if (required.hasProperty(BlockStateProperties.LAYERS) && existing.hasProperty(BlockStateProperties.LAYERS)) {
            return required.getValue(BlockStateProperties.LAYERS) > existing.getValue(BlockStateProperties.LAYERS);
        } else if (required.hasProperty(BlockStateProperties.EGGS) && existing.hasProperty(BlockStateProperties.EGGS)) {
            return required.getValue(BlockStateProperties.EGGS) > existing.getValue(BlockStateProperties.EGGS);
        } else if (required.hasProperty(BlockStateProperties.CANDLES) && existing.hasProperty(BlockStateProperties.CANDLES)) {
            return required.getValue(BlockStateProperties.CANDLES) > existing.getValue(BlockStateProperties.CANDLES);
        } else if (required.hasProperty(BlockStateProperties.PICKLES) && existing.hasProperty(BlockStateProperties.PICKLES)) {
            return required.getValue(BlockStateProperties.PICKLES) > existing.getValue(BlockStateProperties.PICKLES);
        } else if (required.hasProperty(BlockStateProperties.FLOWER_AMOUNT) && existing.hasProperty(BlockStateProperties.FLOWER_AMOUNT)) {
            return required.getValue(BlockStateProperties.FLOWER_AMOUNT) > existing.getValue(BlockStateProperties.FLOWER_AMOUNT);
        } else if (required.hasProperty(BlockStateProperties.SEGMENT_AMOUNT) && existing.hasProperty(BlockStateProperties.SEGMENT_AMOUNT)) {
            return required.getValue(BlockStateProperties.SEGMENT_AMOUNT) > existing.getValue(BlockStateProperties.SEGMENT_AMOUNT);
        }

        if (block instanceof MultifaceBlock || block instanceof VineBlock) {
            for (BooleanProperty property : MULTIFACE_PROPERTIES) {
                if (required.hasProperty(property) && required.getValue(property) && !existing.getValue(property)) return true;
            }
        }
        return false;
    }

    /**
     * Whether right clicking the block with a block in hand does something itself, rather than place against it, so
     * placing against it takes sneaking. Not perfect.
     */
    public static boolean isInteractable(BlockPos blockPos) {
        if (mc.level.getBlockEntity(blockPos) instanceof MenuProvider) return true;

        BlockState state = mc.level.getBlockState(blockPos);
        // blocks with a menu but no block entity, like crafting tables
        if (state.getMenuProvider(mc.level, blockPos) != null) return true;

        Block block = state.getBlock();
        return block instanceof DoorBlock
            || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof RepeaterBlock
            || block instanceof ComparatorBlock
            || block instanceof DaylightDetectorBlock
            || block instanceof NoteBlock
            || block instanceof BellBlock
            || block instanceof BedBlock
            || block instanceof DragonEggBlock
            || block instanceof LecternBlock
            // opens its editor
            || block instanceof SignBlock
            || block instanceof EnderChestBlock
            // takes the held item, or what's in it
            || block instanceof DecoratedPotBlock
            || block instanceof ShelfBlock
            || block instanceof ComposterBlock
            || block instanceof FlowerPotBlock
            || block instanceof JukeboxBlock
            || block instanceof ChiseledBookShelfBlock
            || block instanceof AbstractCauldronBlock
            || block instanceof VaultBlock
            // eaten, picked, toggled or blown up
            || block instanceof CakeBlock
            || block instanceof CandleCakeBlock
            || block instanceof CaveVines
            || block instanceof SweetBerryBushBlock
            || block instanceof RedStoneWireBlock
            || block instanceof CopperGolemStatueBlock
            || block instanceof RespawnAnchorBlock
            || block instanceof CommandBlock
            || block instanceof StructureBlock
            || block instanceof JigsawBlock;
    }

    private List<Float> getSimulationYaws(float legitYaw) {
        if (placeState.hasProperty(BlockStateProperties.ROTATION_16)) return ROTATION_YAWS;
        if (placeState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            || placeState.hasProperty(BlockStateProperties.FACING)
            || placeState.hasProperty(BlockStateProperties.ATTACH_FACE)
            || placeState.hasProperty(BlockStateProperties.ORIENTATION)) {
            return CARDINAL_YAWS;
        }
        return List.of(legitYaw);
    }

    private List<Float> getSimulationPitches(float legitPitch) {
        if (placeState.hasProperty(BlockStateProperties.FACING)
            || placeState.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
            || placeState.hasProperty(BlockStateProperties.ORIENTATION)
            || placeState.hasProperty(BlockStateProperties.ATTACH_FACE)
            || placeState.hasProperty(BlockStateProperties.ROTATION_16)) {
            return VERTICAL_PITCHES;
        }
        return List.of(legitPitch);
    }

    /**
     * The item that places this block, for the few that don't map to theirs.
     */
    public Item asItem() {
        return itemFor(placeState);
    }

    public static Item itemFor(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof FlowerPotBlock) return Items.FLOWER_POT;

        if (block == Blocks.WATER) return Items.WATER_BUCKET;
        if (block == Blocks.LAVA) return Items.LAVA_BUCKET;

        return block.asItem();
    }
}
