/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.system.MemoryUtil.*;

public class MeshBuilder {
    private static final boolean DEBUG = FabricLoader.getInstance().isDevelopmentEnvironment() || Boolean.getBoolean("meteor.render.debug");

    public double alpha = 1;

    private final MeteorRenderPipeline pipeline;
    private final int primitiveVerticesSize;
    private final int primitiveIndicesCount;

    private ByteBuffer vertices = null;
    private long verticesPointerStart, verticesPointer;

    private ByteBuffer indices = null;
    private long indicesPointer;

    private int vertexI, indicesCount;

    private boolean building;
    private double cameraX, cameraZ;

    private int vao, vbo, ibo;
    private boolean gpuInitialized;

    public MeshBuilder(MeteorRenderPipeline pipeline) {
        this.pipeline = pipeline;
        primitiveVerticesSize = pipeline.vertexSize;
        primitiveIndicesCount = pipeline.drawMode.indicesCount;
    }

    public MeshBuilder(MeteorRenderPipeline pipeline, int vertexCount, int indexCount) {
        this(pipeline);
        allocateBuffers(vertexCount, indexCount);
    }

    public void begin() {
        if (building) throw new IllegalStateException("Mesh.begin() called while already building.");

        verticesPointer = verticesPointerStart;
        vertexI = 0;
        indicesCount = 0;

        building = true;

        if (Utils.rendering3D) {
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

            cameraX = camera.x;
            cameraZ = camera.z;
        } else {
            cameraX = 0;
            cameraZ = 0;
        }
    }

    public MeshBuilder vec3(double x, double y, double z) {
        debugVertexBufferCapacity();

        long p = verticesPointer;

        memPutFloat(p, (float) (x - cameraX));
        memPutFloat(p + 4, (float) y);
        memPutFloat(p + 8, (float) (z - cameraZ));

        verticesPointer += 12;
        return this;
    }

    public MeshBuilder vec2(double x, double y) {
        debugVertexBufferCapacity();

        long p = verticesPointer;

        memPutFloat(p, (float) x);
        memPutFloat(p + 4, (float) y);

        verticesPointer += 8;
        return this;
    }

    public MeshBuilder color(Color c) {
        debugVertexBufferCapacity();

        long p = verticesPointer;

        memPutByte(p, (byte) c.r);
        memPutByte(p + 1, (byte) c.g);
        memPutByte(p + 2, (byte) c.b);
        memPutByte(p + 3, (byte) (c.a * (float) alpha));

        verticesPointer += 4;
        return this;
    }

    public int next() {
        return vertexI++;
    }

    public void line(int i1, int i2) {
        debugIndexBufferCapacity();

        long p = indicesPointer + indicesCount * 4L;

        memPutInt(p, i1);
        memPutInt(p + 4, i2);

        indicesCount += 2;
    }

    public void quad(int i1, int i2, int i3, int i4) {
        debugIndexBufferCapacity();

        long p = indicesPointer + indicesCount * 4L;

        memPutInt(p, i1);
        memPutInt(p + 4, i2);
        memPutInt(p + 8, i3);

        memPutInt(p + 12, i3);
        memPutInt(p + 16, i4);
        memPutInt(p + 20, i1);

        indicesCount += 6;
    }

    public void triangle(int i1, int i2, int i3) {
        debugIndexBufferCapacity();

        long p = indicesPointer + indicesCount * 4L;

        memPutInt(p, i1);
        memPutInt(p + 4, i2);
        memPutInt(p + 8, i3);

        indicesCount += 3;
    }

    public void ensureQuadCapacity() {
        ensureCapacity(4, 6);
    }

    public void ensureTriCapacity() {
        ensureCapacity(3, 3);
    }

    public void ensureLineCapacity() {
        ensureCapacity(2, 2);
    }

    public void ensureCapacity(int vertexCount, int indexCount) {
        if (DEBUG && (indexCount % primitiveIndicesCount != 0)) {
            throw new IllegalArgumentException("Unexpected amount of indices written to MeshBuilder.");
        }

        if (vertices == null || indices == null) {
            allocateBuffers(256 * 4, 512 * 4);
            return;
        }

        if ((vertexI + vertexCount) * primitiveVerticesSize >= vertices.capacity()) {
            int offset = getVerticesOffset();
            int newSize = Math.max(vertices.capacity() * 2, vertices.capacity() + vertexCount * primitiveVerticesSize);
            ByteBuffer newVertices = BufferUtils.createByteBuffer(newSize);
            memCopy(memAddress0(vertices), memAddress0(newVertices), offset);

            vertices = newVertices;
            verticesPointerStart = memAddress0(vertices);
            verticesPointer = verticesPointerStart + offset;
        }

        if ((indicesCount + indexCount) * Integer.BYTES >= indices.capacity()) {
            int newSize = Math.max(indices.capacity() * 2, indices.capacity() + indexCount * Integer.BYTES);

            ByteBuffer newIndices = BufferUtils.createByteBuffer(newSize);
            memCopy(memAddress0(indices), memAddress0(newIndices), indicesCount * 4L);

            indices = newIndices;
            indicesPointer = memAddress0(indices);
        }
    }

    private void allocateBuffers(int vertexCount, int indexCount) {
        vertices = BufferUtils.createByteBuffer(primitiveVerticesSize * vertexCount);
        verticesPointer = verticesPointerStart = memAddress0(vertices);

        indices = BufferUtils.createByteBuffer(indexCount * Integer.BYTES);
        indicesPointer = memAddress0(indices);
    }

    public void end() {
        if (!building) throw new IllegalStateException("Mesh.end() called while not building.");

        building = false;
    }

    public boolean isBuilding() {
        return building;
    }

    public int getIndicesCount() {
        return indicesCount;
    }

    public MeteorRenderPipeline getPipeline() {
        return pipeline;
    }

    /** Uploads the current buffers and issues the draw call. GL state and shader must already be set up. */
    public void draw() {
        if (indicesCount <= 0) return;

        if (!gpuInitialized) initGpu();

        // Bind our vao before touching the index buffer. The GL_ELEMENT_ARRAY_BUFFER binding is part of
        // vertex array object state, so uploading indices while Minecraft's vao is still bound would
        // overwrite its element buffer and make its next draw call read indices from a null client pointer.
        GL.bindVertexArray(vao);

        GL.bindVertexBuffer(vbo);
        vertices.limit(getVerticesOffset());
        GL.bufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
        vertices.limit(vertices.capacity());

        GL.bindIndexBuffer(ibo);
        indices.limit(indicesCount * Integer.BYTES);
        GL.bufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_DYNAMIC_DRAW);
        indices.limit(indices.capacity());

        GL.drawElements(pipeline.drawMode.getGL(), indicesCount, GL_UNSIGNED_INT);

        GL.bindVertexBuffer(0);
        GL.bindVertexArray(0);
    }

    public void destroy() {
        if (!gpuInitialized) return;

        GL.deleteBuffer(ibo);
        GL.deleteBuffer(vbo);
        GL.deleteVertexArray(vao);

        gpuInitialized = false;
    }

    private void initGpu() {
        vao = GL.genVertexArray();
        GL.bindVertexArray(vao);

        vbo = GL.genBuffer();
        GL.bindVertexBuffer(vbo);

        ibo = GL.genBuffer();
        GL.bindIndexBuffer(ibo);

        int stride = pipeline.vertexSize;
        int offset = 0;
        for (int i = 0; i < pipeline.attribs.length; i++) {
            MeteorRenderPipeline.Attrib attrib = pipeline.attribs[i];

            GL.enableVertexAttribute(i);
            GL.vertexAttribute(i, attrib.count, attrib.getType(), attrib.normalized, stride, offset);

            offset += attrib.size;
        }

        GL.bindVertexBuffer(0);
        GL.bindVertexArray(0);

        gpuInitialized = true;
    }

    private int getVerticesOffset() {
        return (int) (verticesPointer - verticesPointerStart);
    }

    private void debugVertexBufferCapacity() {
        if (DEBUG && (vertices == null || vertexI * primitiveVerticesSize >= vertices.capacity())) {
            throw new IndexOutOfBoundsException("Vertices written to MeshBuilder without calling 'ensureCapacity()' first!");
        }
    }

    private void debugIndexBufferCapacity() {
        if (DEBUG && (indices == null || indicesCount * Integer.BYTES >= indices.capacity())) {
            throw new IndexOutOfBoundsException("Indices written to MeshBuilder without calling 'ensureCapacity()' first!");
        }
    }
}
