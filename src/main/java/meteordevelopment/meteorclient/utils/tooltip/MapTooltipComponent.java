/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.tooltip;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BetterTooltips;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class MapTooltipComponent implements ClientTooltipComponent, MeteorTooltipData {
    private static final ResourceLocation TEXTURE_MAP_BACKGROUND = ResourceLocation.parse("textures/map/map_background.png");
    private final int mapId;
    private final MapRenderState mapRenderState = new MapRenderState();

    public MapTooltipComponent(int mapId) {
        this.mapId = mapId;
    }

    @Override
    public int getHeight(Font textRenderer) {
        double scale = Modules.get().get(BetterTooltips.class).mapsScale.get();
        return (int) ((128 + 16) * scale) + 2;
    }

    @Override
    public int getWidth(Font textRenderer) {
        double scale = Modules.get().get(BetterTooltips.class).mapsScale.get();
        return (int) ((128 + 16) * scale);
    }

    @Override
    public ClientTooltipComponent getComponent() {
        return this;
    }

    @Override
    public void renderImage(Font font, int x, int y, int width, int height, GuiGraphics graphics) {
        var scale = Modules.get().get(BetterTooltips.class).mapsScale.get().floatValue();

        // Background
        int size = (int) ((128 + 16) * scale);
        graphics.blit(RenderType::guiTextured, TEXTURE_MAP_BACKGROUND, x, y, 0, 0, size, size, size, size);

        // Contents
        MapItemSavedData mapState = MapItem.getSavedData(new MapId(mapId), mc.level);
        if (mapState == null) return;

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.pose().translate(8, 8, 0);

        mc.getMapRenderer().extractRenderState(new MapId(mapId), mapState, mapRenderState);
        graphics.drawSpecial(bufferSource -> mc.getMapRenderer().render(mapRenderState, graphics.pose(), bufferSource, false, LightTexture.FULL_BRIGHT));

        graphics.pose().popPose();
    }
}
