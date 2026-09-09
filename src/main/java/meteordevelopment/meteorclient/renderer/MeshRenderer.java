/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class MeshRenderer {
    private static final MeshRenderer INSTANCE = new MeshRenderer();

    private static boolean taken;

    private RenderTarget renderTarget;
    private Framebuffer framebuffer;
    private Color clearColor;
    private MeteorRenderPipeline pipeline;
    private MeshBuilder mesh;
    private Matrix4f matrix;
    private final List<Consumer<Shader>> uniforms = new ArrayList<>();
    private final List<SamplerBinding> samplers = new ArrayList<>();

    private MeshRenderer() {
    }

    public static MeshRenderer begin() {
        if (taken)
            throw new IllegalStateException("Previous instance of MeshRenderer was not ended");

        taken = true;
        return INSTANCE;
    }

    public MeshRenderer attachments(RenderTarget renderTarget) {
        this.renderTarget = renderTarget;
        return this;
    }

    public MeshRenderer attachments(Framebuffer framebuffer) {
        this.framebuffer = framebuffer;
        return this;
    }

    public MeshRenderer clearColor(Color color) {
        clearColor = color;
        return this;
    }

    public MeshRenderer pipeline(MeteorRenderPipeline pipeline) {
        this.pipeline = pipeline;
        return this;
    }

    public MeshRenderer mesh(MeshBuilder mesh) {
        this.mesh = mesh;
        return this;
    }

    public MeshRenderer mesh(MeshBuilder mesh, Matrix4f matrix) {
        this.mesh = mesh;
        return this.transform(matrix);
    }

    public MeshRenderer mesh(MeshBuilder mesh, PoseStack matrices) {
        this.mesh = mesh;
        return this.transform(matrices);
    }

    public MeshRenderer transform(Matrix4f matrix) {
        this.matrix = matrix;
        return this;
    }

    public MeshRenderer transform(PoseStack matrices) {
        this.matrix = matrices.last().pose();
        return this;
    }

    public MeshRenderer fullscreen() {
        return this.mesh(FullScreenRenderer.mesh);
    }

    // Uniforms

    public MeshRenderer uniform(String name, int v) {
        uniforms.add(shader -> shader.set(name, v));
        return this;
    }

    public MeshRenderer uniform(String name, boolean v) {
        uniforms.add(shader -> shader.set(name, v));
        return this;
    }

    public MeshRenderer uniform(String name, double v) {
        uniforms.add(shader -> shader.set(name, v));
        return this;
    }

    public MeshRenderer uniform(String name, double v1, double v2) {
        uniforms.add(shader -> shader.set(name, v1, v2));
        return this;
    }

    public MeshRenderer uniform(String name, double v1, double v2, double v3, double v4) {
        uniforms.add(shader -> shader.set(name, v1, v2, v3, v4));
        return this;
    }

    public MeshRenderer uniform(String name, Color color) {
        uniforms.add(shader -> shader.set(name, color));
        return this;
    }

    public MeshRenderer uniform(String name, Matrix4f mat) {
        uniforms.add(shader -> shader.set(name, mat));
        return this;
    }

    public MeshRenderer uniform(Consumer<Shader> setter) {
        uniforms.add(setter);
        return this;
    }

    // Samplers

    public MeshRenderer sampler(String name, Texture texture) {
        if (name != null && texture != null) samplers.add(new SamplerBinding(name, () -> texture.bind(samplerSlot(name))));
        return this;
    }

    public MeshRenderer sampler(String name, AbstractTexture texture) {
        if (name != null && texture != null) samplers.add(new SamplerBinding(name, () -> GL.bindTexture(texture.getId(), samplerSlot(name))));
        return this;
    }

    public MeshRenderer sampler(String name, ResourceLocation textureId) {
        if (name != null && textureId != null) samplers.add(new SamplerBinding(name, () -> GL.bindTexture(mc.getTextureManager().getTexture(textureId).getId(), samplerSlot(name))));
        return this;
    }

    public MeshRenderer sampler(String name, int glId) {
        if (name != null) samplers.add(new SamplerBinding(name, () -> GL.bindTexture(glId, samplerSlot(name))));
        return this;
    }

    private int samplerSlot(String name) {
        for (int i = 0; i < samplers.size(); i++) {
            if (samplers.get(i).name.equals(name)) return i;
        }

        return 0;
    }

    public void end() {
        if (mesh != null && mesh.isBuilding()) {
            mesh.end();
        }

        if (mesh != null && mesh.getIndicesCount() > 0) {
            if (pipeline == null) pipeline = mesh.getPipeline();

            GL.saveState();

            if (pipeline.depthTest) GL.enableDepth();
            else GL.disableDepth();
            GL.enableBlend();
            if (pipeline.cull) GL.enableCull();
            else GL.disableCull();
            if (pipeline.lineSmooth) GL.enableLineSmooth();

            if (framebuffer != null) {
                framebuffer.bind();
                framebuffer.setViewport();
            } else if (renderTarget != null) renderTarget.bindWrite(true);

            if (clearColor != null) {
                GL.clearColor(clearColor.r / 255f, clearColor.g / 255f, clearColor.b / 255f, clearColor.a / 255f);
            }

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            boolean pushed = Utils.rendering3D || matrix != null;

            if (pushed) modelView.pushMatrix();
            if (matrix != null) modelView.mul(matrix);

            if (Utils.rendering3D) {
                Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
                modelView.translate(0, (float) -cameraPos.y, 0);
            }

            Shader shader = pipeline.shader();
            shader.bind();
            shader.setDefaults();

            for (Consumer<Shader> uniform : uniforms) uniform.accept(shader);

            for (int i = 0; i < samplers.size(); i++) {
                SamplerBinding sampler = samplers.get(i);
                sampler.bind.run();
                shader.set(sampler.name, i);
            }

            mesh.draw();

            GL.resetTextureSlot();

            if (pushed) modelView.popMatrix();

            if (framebuffer != null || renderTarget != null) mc.getMainRenderTarget().bindWrite(true);

            GL.restoreState();
        }

        renderTarget = null;
        framebuffer = null;
        clearColor = null;
        pipeline = null;
        mesh = null;
        matrix = null;
        uniforms.clear();
        samplers.clear();

        taken = false;
    }

    private record SamplerBinding(String name, Runnable bind) {
    }
}
