/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import static org.lwjgl.opengl.GL32C.GL_FLOAT;
import static org.lwjgl.opengl.GL32C.GL_UNSIGNED_BYTE;

/**
 * 1.21.4 replacement for the RenderPipeline abstraction the 26.x renderer was built on.
 * Bundles a vertex layout, draw mode, GL state flags and a lazily compiled {@link Shader}.
 */
public class MeteorRenderPipeline {
    public final String name;
    public final DrawMode drawMode;
    public final Attrib[] attribs;
    public final int vertexSize;
    public final boolean depthTest;
    public final boolean cull;
    public final boolean lineSmooth;

    private final String vertPath, fragPath;
    private Shader shader;

    private MeteorRenderPipeline(String name, DrawMode drawMode, Attrib[] attribs, String vertPath, String fragPath, boolean depthTest, boolean cull, boolean lineSmooth) {
        this.name = name;
        this.drawMode = drawMode;
        this.attribs = attribs;
        this.vertPath = vertPath;
        this.fragPath = fragPath;
        this.depthTest = depthTest;
        this.cull = cull;
        this.lineSmooth = lineSmooth;

        int stride = 0;
        for (Attrib attrib : attribs) stride += attrib.size;
        this.vertexSize = stride;
    }

    public Shader shader() {
        if (shader == null) shader = new Shader(vertPath, fragPath);
        return shader;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public enum Attrib {
        Float(1, 4, false),
        Vec2(2, 4, false),
        Vec3(3, 4, false),
        Color(4, 1, true);

        public final int count, size;
        public final boolean normalized;

        Attrib(int count, int componentSize, boolean normalized) {
            this.count = count;
            this.size = count * componentSize;
            this.normalized = normalized;
        }

        public int getType() {
            return this == Color ? GL_UNSIGNED_BYTE : GL_FLOAT;
        }
    }

    public static class Builder {
        private final String name;
        private DrawMode drawMode = DrawMode.Triangles;
        private Attrib[] attribs = {};
        private String vertPath, fragPath;
        private boolean depthTest = false;
        private boolean cull = false;
        private boolean lineSmooth = false;

        private Builder(String name) {
            this.name = name;
        }

        public Builder drawMode(DrawMode drawMode) {
            this.drawMode = drawMode;
            return this;
        }

        public Builder attribs(Attrib... attribs) {
            this.attribs = attribs;
            return this;
        }

        public Builder shader(String vertPath, String fragPath) {
            this.vertPath = vertPath;
            this.fragPath = fragPath;
            return this;
        }

        public Builder depthTest() {
            this.depthTest = true;
            return this;
        }

        public Builder cull() {
            this.cull = true;
            return this;
        }

        public Builder lineSmooth() {
            this.lineSmooth = true;
            return this;
        }

        public MeteorRenderPipeline build() {
            return new MeteorRenderPipeline(name, drawMode, attribs, vertPath, fragPath, depthTest, cull, lineSmooth);
        }
    }
}
