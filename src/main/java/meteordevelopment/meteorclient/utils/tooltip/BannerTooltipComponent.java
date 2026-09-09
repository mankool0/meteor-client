/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.tooltip;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BannerTooltipComponent implements MeteorTooltipData, ClientTooltipComponent {
    private final DyeColor color;
    private final BannerPatternLayers patterns;
    private final ModelPart bannerFlag;

    /**
     * Should only be used when the ItemStack is a banner
     */
    public BannerTooltipComponent(ItemStack banner) {
        this.color = ((BannerItem) banner.getItem()).getColor();
        this.patterns = banner.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        this.bannerFlag = mc.getEntityModels().bakeLayer(ModelLayers.STANDING_BANNER_FLAG).getChild("flag");
    }

    public BannerTooltipComponent(DyeColor color, BannerPatternLayers patterns) {
        this.color = color;
        this.patterns = patterns;
        this.bannerFlag = mc.getEntityModels().bakeLayer(ModelLayers.STANDING_BANNER_FLAG).getChild("flag");
    }

    @Override
    public ClientTooltipComponent getComponent() {
        return this;
    }

    @Override
    public int getHeight(Font textRenderer) {
        return 40 * 2;
    }

    @Override
    public int getWidth(Font textRenderer) {
        return 20 * 2;
    }

    @Override
    public void renderImage(Font textRenderer, int x, int y, int width, int height, GuiGraphics graphics) {
        // PORT(1.21.4): the 26.1 PictureInPicture gui render state does not exist on 1.21.4 -
        // the banner is drawn directly, mirroring the old 1.21.4 tree.
        var centerX = width / 2 - getWidth(null) / 2;

        Lighting.setupForFlatItems();
        PoseStack matrices = graphics.pose();
        matrices.pushPose();
        matrices.translate(centerX + x, y, 0);
        matrices.scale(0.5f, 0.5f, 1);

        matrices.translate(8, 8, 0);
        matrices.pushPose();
        matrices.translate(0.5, 16, 0);
        matrices.scale(6, -6, 1);
        matrices.scale(2, -2, -2);
        matrices.pushPose();
        matrices.translate(2.5, 8.5, 0);
        matrices.scale(5, 5, 5);

        bannerFlag.xRot = 0f;
        bannerFlag.y = -32f;
        graphics.drawSpecial(bufferSource -> BannerRenderer.renderPatterns(
            matrices,
            bufferSource,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            bannerFlag,
            ModelBakery.BANNER_BASE,
            true,
            color,
            patterns
        ));

        matrices.popPose();
        matrices.popPose();
        matrices.popPose();
        Lighting.setupFor3DItems();
    }
}
