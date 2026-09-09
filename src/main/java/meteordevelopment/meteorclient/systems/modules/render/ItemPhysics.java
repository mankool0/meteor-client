/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import meteordevelopment.meteorclient.events.render.ApplyTransformationEvent;
import meteordevelopment.meteorclient.events.render.RenderItemEntityEvent;
import meteordevelopment.meteorclient.mixin.ItemStackRenderStateAccessor;
import meteordevelopment.meteorclient.mixin.LayerRenderStateAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.joml.Vector3f;

public class ItemPhysics extends Module {
    private static final Direction[] FACES = { null, Direction.UP, Direction.DOWN, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST };
    private static final float PIXEL_SIZE = 1f / 16f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> randomRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("random-rotation")
        .description("Adds a random rotation to every item.")
        .defaultValue(true)
        .build()
    );

    private final RandomSource random = RandomSource.createNewThreadLocalInstance();
    private boolean skipTransformation;

    public ItemPhysics() {
        super(Categories.Render, "item-physics", "Applies physics to items on the ground.");
    }

    @EventHandler
    private void onRenderItemEntity(RenderItemEntityEvent event) {
        event.cancel();

        if (event.renderState.item.isEmpty() || event.itemEntity == null)
            return;

        PoseStack matrices = event.matrixStack;

        random.setSeed(event.itemEntity.getId() * 89748956L);

        for (int i = 0; i < ((ItemStackRenderStateAccessor) event.renderState.item).meteor$getActiveLayerCount(); i++) {
            ItemStackRenderState.LayerRenderState layer = ((ItemStackRenderStateAccessor) event.renderState.item).meteor$getLayers()[i];
            // PORT(1.21.4): needs-mixin - LayerRenderStateAccessor must expose @Accessor("model") BakedModel meteor$getModel()
            // and change meteor$getTransform() to @Invoker("transform") (LayerRenderState has no "itemTransform" field on 1.21.4).
            ModelInfo info = getInfo(((LayerRenderStateAccessor) layer).meteor$getModel());

            matrices.pushPose();
            applyTransformation(matrices, ((LayerRenderStateAccessor) layer).meteor$getTransform());
            matrices.translate(0, info.offsetY, 0);
            offsetInWater(matrices, event.itemEntity);

            if (info.flat) {
                matrices.mulPose(Axis.XP.rotationDegrees(90));
                matrices.translate(0, 0, info.offsetZ);
            }

            if (randomRotation.get()) {
                var axis = Axis.YP;
                var x = 0.5f;
                var y = 0.0f;
                var z = 0.5f;

                if (info.flat) {
                    axis = Axis.ZP;
                    y = 0.5f;
                    z = 0.0f;
                }

                float degrees = (random.nextFloat() * 2 - 1) * 90;

                matrices.translate(x, y, z);
                matrices.mulPose(axis.rotationDegrees(degrees));
                matrices.translate(-x, -y, -z);
            }

            renderLayer(event, info);

            matrices.popPose();
        }
    }

    @EventHandler
    private void onApplyTransformation(ApplyTransformationEvent event) {
        if (skipTransformation)
            event.cancel();
    }

    private void renderLayer(RenderItemEntityEvent event, ModelInfo info) {
        PoseStack matrices = event.matrixStack;
        skipTransformation = true;

        for (int j = 0; j < event.renderState.count; j++) {
            matrices.pushPose();

            if (j > 0) {
                float x = (random.nextFloat() * 2 - 1) * 0.25f;
                float z = (random.nextFloat() * 2 - 1) * 0.25f;
                translate(matrices, info, x, 0, z);
            }

            event.renderState.item.render(matrices, event.vertexConsumerProvider, event.light, OverlayTexture.NO_OVERLAY);

            matrices.popPose();

            float y = Math.max(random.nextFloat() * PIXEL_SIZE, PIXEL_SIZE / 2f);
            translate(matrices, info, 0, y, 0);
        }

        skipTransformation = false;
    }

    private void translate(PoseStack matrices, ModelInfo info, float x, float y, float z) {
        if (info.flat) {
            float temp = y;
            y = z;
            z = -temp;
        }

        matrices.translate(x, y, z);
    }

    private void applyTransformation(PoseStack matrices, ItemTransform transform) {
        transform = new ItemTransform(
            transform.rotation,
            new Vector3f(transform.translation.x(), 0, transform.translation.z()),
            transform.scale
        );

        transform.apply(false, matrices);
    }

    private void offsetInWater(PoseStack matrices, ItemEntity entity) {
        if (entity.isInWater()) {
            matrices.translate(0, 0.333f, 0);
        }
    }

    private ModelInfo getInfo(BakedModel model) {
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;

        if (model != null) {
            for (Direction face : FACES) {
                for (BakedQuad quad : model.getQuads(null, face, random)) {
                    // PORT(1.21.4): BakedQuad has no position(i) - vertex data is packed ints, 8 per vertex, position floats at 0/1/2.
                    int[] vertices = quad.getVertices();

                    for (int i = 0; i < 4; i++) {
                        int j = i * 8;
                        float vecX = Float.intBitsToFloat(vertices[j]);
                        float vecY = Float.intBitsToFloat(vertices[j + 1]);
                        float vecZ = Float.intBitsToFloat(vertices[j + 2]);

                        minY = Math.min(minY, vecY);
                        maxY = Math.max(maxY, vecY);
                        minZ = Math.min(minZ, vecZ);
                        maxZ = Math.max(maxZ, vecZ);
                        minX = Math.min(minX, vecX);
                        maxX = Math.max(maxX, vecX);
                    }
                }
            }
        }

        if (minX == Float.MAX_VALUE) minX = 0;
        if (minY == Float.MAX_VALUE) minY = 0;
        if (minZ == Float.MAX_VALUE) minZ = 0;

        if (maxX == Float.MIN_VALUE) maxX = 1;
        if (maxY == Float.MIN_VALUE) maxY = 1;
        if (maxZ == Float.MIN_VALUE) maxZ = 1;

        float x = maxX - minX;
        float y = maxY - minY;
        float z = maxZ - minZ;

        boolean flat = (x > PIXEL_SIZE && y > PIXEL_SIZE && z <= PIXEL_SIZE);

        return new ModelInfo(flat, 0.5f - minY, -maxZ);
    }

    record ModelInfo(boolean flat, float offsetY, float offsetZ) {
    }
}
