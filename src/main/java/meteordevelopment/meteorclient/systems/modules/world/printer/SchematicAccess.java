/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world.printer;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the printer asks Litematica, kept in one class so nothing else loads a Litematica class when it isn't
 * installed. Verifying this class loads Litematica's, so only touch it once {@link Printer#LITEMATICA} says it's there.
 */
public final class SchematicAccess {
    private SchematicAccess() {
    }

    /**
     * @return the world holding the loaded schematic placements, or null if there is none
     */
    @Nullable
    public static Level world() {
        return SchematicWorldHandler.getSchematicWorld();
    }

    public static boolean isRenderingEnabled() {
        return Configs.Visuals.ENABLE_RENDERING.getBooleanValue() && Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue();
    }

    /**
     * @return whether the position is inside the layers Litematica currently renders
     */
    public static boolean isRendered(BlockPos pos) {
        return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
    }
}
