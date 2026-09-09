/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public abstract class SimpleBlockRenderer {
    private static final PoseStack MATRICES = new PoseStack();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final RandomSource RANDOM = RandomSource.create();

    private SimpleBlockRenderer() {
    }

    public static void renderWithBlockEntity(BlockEntity blockEntity, float tickDelta, IVertexConsumerProvider vertexConsumerProvider) {
        vertexConsumerProvider.setOffset(blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(), blockEntity.getBlockPos().getZ());
        SimpleBlockRenderer.render(blockEntity.getBlockPos(), blockEntity.getBlockState(), vertexConsumerProvider);

        BlockEntityRenderer<BlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(blockEntity);

        if (renderer != null && blockEntity.hasLevel() && blockEntity.getType().isValid(blockEntity.getBlockState())) {
            renderer.render(blockEntity, tickDelta, MATRICES, vertexConsumerProvider, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }

        vertexConsumerProvider.setOffset(0, 0, 0);
    }

    public static void render(BlockPos pos, BlockState state, MultiBufferSource consumerProvider) {
        if (state.getRenderShape() != RenderShape.MODEL) return;

        VertexConsumer consumer = consumerProvider.getBuffer(RenderType.solid());

        BakedModel model = mc.getBlockRenderer().getBlockModel(state);

        Vec3 offset = state.getOffset(pos);
        float offsetX = (float) offset.x;
        float offsetY = (float) offset.y;
        float offsetZ = (float) offset.z;

        for (Direction direction : DIRECTIONS) {
            List<BakedQuad> quads = model.getQuads(state, direction, RANDOM);
            if (!quads.isEmpty()) renderQuads(quads, offsetX, offsetY, offsetZ, consumer);
        }

        List<BakedQuad> quads = model.getQuads(state, null, RANDOM);
        if (!quads.isEmpty()) renderQuads(quads, offsetX, offsetY, offsetZ, consumer);
    }

    private static void renderQuads(List<BakedQuad> quads, float offsetX, float offsetY, float offsetZ, VertexConsumer consumer) {
        for (BakedQuad quad : quads) {
            int[] vertices = quad.getVertices();

            for (int j = 0; j < 4; j++) {
                float x = Float.intBitsToFloat(vertices[j * 8]);
                float y = Float.intBitsToFloat(vertices[j * 8 + 1]);
                float z = Float.intBitsToFloat(vertices[j * 8 + 2]);

                consumer.addVertex(offsetX + x, offsetY + y, offsetZ + z);
            }
        }
    }
}
