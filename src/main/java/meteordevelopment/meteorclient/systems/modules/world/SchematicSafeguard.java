package meteordevelopment.meteorclient.systems.modules.world;

// by Moon_dark 13.07.21, special for -/- corner top diggers list and art
// Adapted for Meteor/Litematica 21.11.22

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;

import java.util.Collections;

public class SchematicSafeguard extends Module {

    public SchematicSafeguard() {
        super(Categories.Litematica, "schematic-safeguard", "Prevents you from misplacing blocks within schematics");
    }

    private List<SchematicPlacement> getSchematicPlacements() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();

        if (manager == null) return Collections.emptyList();
            
        return manager.getAllSchematicsPlacements().stream().filter(placement -> placement.isEnabled()).collect(Collectors.toList());
    }

    @Override
    public String getInfoString() {
        return String.format("%d loaded", getSchematicPlacements().size());
    }

    private boolean betweenLimitsInclusive(int value, int limitOne, int limitTwo) {
        if (limitOne <= limitTwo) {
            return limitOne <= value && value <= limitTwo;
        }
 
        return limitOne >= value && value >= limitTwo;
    }

    private boolean withinSchematicBoundaries(BlockPos blockPos, List<SchematicPlacement> schematicPlacements) {
        for (SchematicPlacement schematic : schematicPlacements) {
            ImmutableMap<String, Box> subRegionBoxes = schematic.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED);

            for (Box box : subRegionBoxes.values()) {
                BlockPos limitOne = box.getPos1();
                BlockPos limitTwo = box.getPos2();

                if (limitOne == null || limitTwo == null) continue;

                if (
                    betweenLimitsInclusive(blockPos.getX(), limitOne.getX(), limitTwo.getX()) &&
                    betweenLimitsInclusive(blockPos.getY(), limitOne.getY(), limitTwo.getY()) &&
                    betweenLimitsInclusive(blockPos.getZ(), limitOne.getZ(), limitTwo.getZ())
                ) {
                    return true;
                }
            }
        }

        return false;
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        List<SchematicPlacement> schematicPlacements = getSchematicPlacements();

        if (schematicWorld == null || schematicPlacements.size() == 0) return;

        BlockHitResult hitResult = event.result;
        BlockPos newBlockPos = hitResult.getBlockPos().add(hitResult.getSide().getOffsetX(), hitResult.getSide().getOffsetY(), hitResult.getSide().getOffsetZ());

        if (!withinSchematicBoundaries(newBlockPos, schematicPlacements)) return;

        ItemStack stack = mc.player.getInventory().getMainHandStack();
        if (stack.getItem() instanceof BlockItem) {
            final BlockItem itmBlock = (BlockItem) stack.getItem();
            final Block block = itmBlock.getBlock();

            final BlockState schBlockState = schematicWorld.getBlockState(newBlockPos);
            final Block schBlock = schBlockState.getBlock();

            if (block != schBlock) {
                info(Text.literal("Tried to place ").append(block.getName().formatted(Formatting.RED)).append(" != ").append(schBlock.getName().formatted(Formatting.GREEN)).append(" contained in schematic!"));
                event.cancel();
            }
        }
    }
}
