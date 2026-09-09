/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class EntityTooltipComponent implements MeteorTooltipData, ClientTooltipComponent {
    protected final LivingEntity entity;
    private static double spin;

    public EntityTooltipComponent(LivingEntity entity) {
        this.entity = entity;
    }

    @Override
    public ClientTooltipComponent getComponent() {
        return this;
    }

    @Override
    public int getHeight(Font textRenderer) {
        return 48;
    }

    @Override
    public int getWidth(Font textRenderer) {
        return 64;
    }

    @Override
    public void renderImage(Font textRenderer, int x, int y, int width, int height, GuiGraphics graphics) {
        // PORT(1.21.4): the 26.1 render-state based GuiGraphics.entity(...) does not exist on 1.21.4 -
        // render the live entity through InventoryScreen.renderEntityInInventory instead.
        x += (width - getWidth(null)) / 2;
        y += 4;

        width = getWidth(null);
        height = getHeight(null);

        float yaw = (float) (spin % 360);
        entity.yBodyRot = yaw;
        entity.setYRot(yaw);
        entity.yHeadRot = yaw;
        entity.yHeadRotO = yaw;
        entity.setXRot(0);

        float scale = Math.max(width, height) / 2f * 1.25f / Math.max(entity.getBbWidth(), entity.getBbHeight());
        Vector3f translation = new Vector3f(0, entity.getBbHeight() / 2f + 0.1f, 0);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);

        InventoryScreen.renderEntityInInventory(graphics, x + width / 2f, y + height / 2f, scale, translation, rotation, null, entity);
        spin += 3 * mc.getDeltaTracker().getGameTimeDeltaTicks();
    }
}
