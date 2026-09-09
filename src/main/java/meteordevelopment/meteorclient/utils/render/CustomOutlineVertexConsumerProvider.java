/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

public class CustomOutlineVertexConsumerProvider implements MultiBufferSource {
    private final MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(new ByteBufferBuilder(1536));

    private int red = 255, green = 255, blue = 255, alpha = 255;

    // Like vanilla OutlineBufferSource, the configured color overrides whatever colors the model writes.
    public void setColor(int red, int green, int blue, int alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    @Override
    public VertexConsumer getBuffer(RenderType layer) {
        if (layer.isOutline()) {
            return new CustomVertexConsumer(this.immediate.getBuffer(layer), this);
        }

        var optional = layer.outline();
        if (optional.isPresent()) {
            return new CustomVertexConsumer(this.immediate.getBuffer(optional.get()), this);
        }

        return NoopVertexConsumer.INSTANCE;
    }

    public void draw() {
        immediate.endBatch();
    }

    private record CustomVertexConsumer(VertexConsumer consumer, CustomOutlineVertexConsumerProvider provider) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            consumer.addVertex(x, y, z);
            consumer.setColor(provider.red, provider.green, provider.blue, provider.alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            consumer.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
