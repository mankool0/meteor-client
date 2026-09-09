/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL32C.*;

public class Texture {
    private final Format format;
    private final Filter filterMin, filterMag;
    private final boolean wrapClamp;

    private int id;
    private int width, height;
    private boolean valid;

    public Texture(int width, int height, Format format, Filter filterMin, Filter filterMag) {
        this(width, height, format, filterMin, filterMag, false);
    }

    public Texture(int width, int height, Format format, Filter filterMin, Filter filterMag, boolean wrapClamp) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.filterMin = filterMin;
        this.filterMag = filterMag;
        this.wrapClamp = wrapClamp;

        if (RenderSystem.isOnRenderThread()) allocate(null);
        else RenderSystem.recordRenderCall(() -> allocate(null));
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isValid() {
        return valid;
    }

    public int getGlId() {
        return id;
    }

    public void upload(byte[] bytes) {
        upload((ByteBuffer) BufferUtils.createByteBuffer(bytes.length).put(bytes).rewind());
    }

    public void upload(ByteBuffer buffer) {
        if (RenderSystem.isOnRenderThread()) allocate(buffer);
        else RenderSystem.recordRenderCall(() -> allocate(buffer));
    }

    private void allocate(ByteBuffer buffer) {
        if (!valid) {
            id = GL.genTexture();
            valid = true;
        }

        bind();
        GL.defaultPixelStore();
        if (format == Format.RED8) GL.pixelStore(GL_UNPACK_ALIGNMENT, 1);

        GL.textureParam(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapClamp ? GL_CLAMP_TO_EDGE : GL_REPEAT);
        GL.textureParam(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapClamp ? GL_CLAMP_TO_EDGE : GL_REPEAT);
        GL.textureParam(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filterMin.toOpenGL());
        GL.textureParam(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filterMag.toOpenGL());

        if (buffer != null) buffer.rewind();
        GL.textureImage2D(GL_TEXTURE_2D, 0, format.toOpenGL(), width, height, 0, format.toOpenGL(), GL_UNSIGNED_BYTE, buffer);

        if (filterMin == Filter.LINEAR_MIPMAP_LINEAR || filterMag == Filter.LINEAR_MIPMAP_LINEAR) {
            GL.generateMipmap(GL_TEXTURE_2D);
        }
    }

    public void bind(int slot) {
        GL.bindTexture(id, slot);
    }

    public void bind() {
        bind(0);
    }

    public void close() {
        if (!valid) return;

        GL.deleteTexture(id);
        valid = false;
    }

    public static Texture readResource(String path, boolean flipY, Filter filter) {
        try (var in = Texture.class.getResourceAsStream(path)) {
            if (in == null) return null;

            var data = TextureUtil.readResource(in).rewind();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);

                STBImage.stbi_set_flip_vertically_on_load(flipY);
                ByteBuffer image = STBImage.stbi_load_from_memory(data, width, height, comp, 4);

                var texture = new Texture(width.get(0), height.get(0), Format.RGBA8, filter, filter);
                texture.upload(image);

                STBImage.stbi_image_free(image);
                STBImage.stbi_set_flip_vertically_on_load(false);

                return texture;
            }
        } catch (IOException unused1) {
            return null;
        }
    }

    public enum Format {
        RGBA8,
        RED8;

        public int toOpenGL() {
            return switch (this) {
                case RGBA8 -> GL_RGBA;
                case RED8 -> GL_RED;
            };
        }
    }

    public enum Filter {
        NEAREST,
        LINEAR,
        LINEAR_MIPMAP_LINEAR;

        public int toOpenGL() {
            return switch (this) {
                case NEAREST -> GL_NEAREST;
                case LINEAR -> GL_LINEAR;
                case LINEAR_MIPMAP_LINEAR -> GL_LINEAR_MIPMAP_LINEAR;
            };
        }
    }
}
