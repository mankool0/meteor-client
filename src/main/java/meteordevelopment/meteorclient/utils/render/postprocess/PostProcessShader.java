package meteordevelopment.meteorclient.utils.render.postprocess;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipeline;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public abstract class PostProcessShader {
    protected final MeteorRenderPipeline pipeline;
    public final RenderTarget framebuffer;

    protected PostProcessShader(MeteorRenderPipeline pipeline) {
        this.pipeline = pipeline;
        this.framebuffer = new TextureTarget(mc.getWindow().getWidth(), mc.getWindow().getHeight(), true);
        this.framebuffer.setClearColor(0, 0, 0, 0);
    }

    protected abstract boolean shouldDraw();

    protected void preDraw() {
    }

    protected void postDraw() {
    }

    protected abstract void setupPass(MeshRenderer renderer);

    public void clearTexture() {
        if (this.shouldDraw()) {
            framebuffer.clear();
            mc.getMainRenderTarget().bindWrite(false);
        }
    }

    public void submitVertices(Runnable draw) {
        if (!shouldDraw()) return;

        preDraw();
        draw.run();
        postDraw();
    }

    public void render() {
        if (!shouldDraw()) return;

        var renderer = MeshRenderer.begin()
            .attachments(mc.getMainRenderTarget())
            .pipeline(pipeline)
            .fullscreen()
            .uniform("u_Size", (double) mc.getWindow().getWidth(), (double) mc.getWindow().getHeight())
            .sampler("u_Texture", framebuffer.getColorTextureId());

        setupPass(renderer);

        renderer.end();
    }

    public void onResized(int width, int height) {
        if (framebuffer == null) return;
        framebuffer.resize(width, height);
    }
}
