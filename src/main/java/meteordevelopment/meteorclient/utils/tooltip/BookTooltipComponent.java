/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.tooltip;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public class BookTooltipComponent implements ClientTooltipComponent, MeteorTooltipData {
    private static final ResourceLocation TEXTURE_BOOK_BACKGROUND = ResourceLocation.parse("textures/gui/book.png");

    private final Component page;

    public BookTooltipComponent(Component page) {
        this.page = page;
    }

    @Override
    public ClientTooltipComponent getComponent() {
        return this;
    }

    @Override
    public int getHeight(Font textRenderer) {
        return 134;
    }

    @Override
    public int getWidth(Font textRenderer) {
        return 112;
    }

    @Override
    public void renderImage(Font font, int x, int y, int width, int height, GuiGraphics graphics) {
        // Background
        graphics.blit(RenderType::guiTextured, TEXTURE_BOOK_BACKGROUND, x - 10, y, 0, 0, 128, 128, 179, 179);

        // Content
        PoseStack matrices = graphics.pose();
        matrices.pushPose();
        matrices.translate(x + 16, y + 12, 0);
        matrices.scale(0.7f, 0.7f, 1);
        int offset = 0;
        for (FormattedCharSequence line : font.split(page, 112)) {
            graphics.drawString(font, line, 0, offset, 0xFF000000, false);
            offset += 8;
        }
        matrices.popPose();
    }
}
