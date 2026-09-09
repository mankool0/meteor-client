/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.renderer.MeteorRenderPipeline.Attrib;

import java.util.ArrayList;
import java.util.List;

public abstract class MeteorRenderPipelines {
    private static final List<MeteorRenderPipeline> PIPELINES = new ArrayList<>();

    // World

    public static final MeteorRenderPipeline WORLD_COLORED = add(MeteorRenderPipeline.builder("world_colored")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec3, Attrib.Color)
        .shader("pos_color.vert", "pos_color.frag")
        .build()
    );

    public static final MeteorRenderPipeline WORLD_COLORED_LINES = add(MeteorRenderPipeline.builder("world_colored_lines")
        .drawMode(DrawMode.Lines)
        .attribs(Attrib.Vec3, Attrib.Color)
        .shader("pos_color.vert", "pos_color.frag")
        .lineSmooth()
        .build()
    );

    public static final MeteorRenderPipeline WORLD_COLORED_DEPTH = add(MeteorRenderPipeline.builder("world_colored_depth")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec3, Attrib.Color)
        .shader("pos_color.vert", "pos_color.frag")
        .depthTest()
        .build()
    );

    public static final MeteorRenderPipeline WORLD_COLORED_LINES_DEPTH = add(MeteorRenderPipeline.builder("world_colored_lines_depth")
        .drawMode(DrawMode.Lines)
        .attribs(Attrib.Vec3, Attrib.Color)
        .shader("pos_color.vert", "pos_color.frag")
        .depthTest()
        .lineSmooth()
        .build()
    );

    // UI

    public static final MeteorRenderPipeline UI_COLORED = add(MeteorRenderPipeline.builder("ui_colored")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2, Attrib.Color)
        .shader("pos_color.vert", "pos_color.frag")
        .build()
    );

    public static final MeteorRenderPipeline UI_COLORED_LINES = add(MeteorRenderPipeline.builder("ui_colored_lines")
        .drawMode(DrawMode.Lines)
        .attribs(Attrib.Vec2, Attrib.Color)
        .shader("pos_color.vert", "pos_color.frag")
        .build()
    );

    public static final MeteorRenderPipeline UI_TEXTURED = add(MeteorRenderPipeline.builder("ui_textured")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2, Attrib.Vec2, Attrib.Color)
        .shader("pos_tex_color.vert", "pos_tex_color.frag")
        .build()
    );

    public static final MeteorRenderPipeline UI_TEXT = add(MeteorRenderPipeline.builder("ui_text")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2, Attrib.Vec2, Attrib.Color)
        .shader("text.vert", "text.frag")
        .build()
    );

    // Post Process

    public static final MeteorRenderPipeline POST_OUTLINE = add(MeteorRenderPipeline.builder("post_outline")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2)
        .shader("post-process/base.vert", "post-process/outline.frag")
        .build()
    );

    public static final MeteorRenderPipeline POST_IMAGE = add(MeteorRenderPipeline.builder("post_image")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2)
        .shader("post-process/base.vert", "post-process/image.frag")
        .build()
    );

    // Blur

    public static final MeteorRenderPipeline BLUR_DOWN = add(MeteorRenderPipeline.builder("blur_down")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2)
        .shader("blur.vert", "blur_down.frag")
        .build()
    );

    public static final MeteorRenderPipeline BLUR_UP = add(MeteorRenderPipeline.builder("blur_up")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2)
        .shader("blur.vert", "blur_up.frag")
        .build()
    );

    public static final MeteorRenderPipeline BLUR_PASSTHROUGH = add(MeteorRenderPipeline.builder("blur_passthrough")
        .drawMode(DrawMode.Triangles)
        .attribs(Attrib.Vec2)
        .shader("passthrough.vert", "passthrough.frag")
        .build()
    );

    private static MeteorRenderPipeline add(MeteorRenderPipeline pipeline) {
        PIPELINES.add(pipeline);
        return pipeline;
    }

    /** Eagerly compiles all shaders. Safe to call whenever a GL context and resources are available. */
    public static void precompile() {
        for (MeteorRenderPipeline pipeline : PIPELINES) {
            pipeline.shader();
        }
    }

    private MeteorRenderPipelines() {
    }
}
