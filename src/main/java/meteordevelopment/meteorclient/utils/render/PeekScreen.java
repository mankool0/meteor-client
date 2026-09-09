/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BetterTooltips;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PeekScreen extends ShulkerBoxScreen {
    private final ResourceLocation TEXTURE = ResourceLocation.parse("textures/gui/container/shulker_box.png");
    private final ItemStack storageBlock;

    public PeekScreen(ItemStack storageBlock, ItemStack[] contents) {
        super(new ShulkerBoxMenu(0, mc.player.getInventory(), new SimpleContainer(contents)), mc.player.getInventory(), storageBlock.getHoverName());
        this.storageBlock = storageBlock;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        if (tooltips.shouldOpenContents(false, button, 0) && hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && mc.player.containerMenu.getCarried().isEmpty()) {
            ItemStack itemStack = hoveredSlot.getItem();
            return tooltips.openContent(itemStack);
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        if (tooltips.shouldOpenContents(true, keyCode, modifiers) && hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && mc.player.containerMenu.getCarried().isEmpty()) {
            ItemStack itemStack = hoveredSlot.getItem();
            if (tooltips.openContent(itemStack)) {
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || mc.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        return false;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        Color color = Utils.getShulkerColor(storageBlock);

        int i = (width - imageWidth) / 2;
        int j = (height - imageHeight) / 2;
        graphics.blit(RenderType::guiTextured, TEXTURE, i, j, 0f, 0f, imageWidth, imageHeight, imageWidth, imageHeight, 256, 256, ARGB.colorFromFloat(color.a / 255f, color.r / 255f, color.g / 255f, color.b / 255f));
    }
}
