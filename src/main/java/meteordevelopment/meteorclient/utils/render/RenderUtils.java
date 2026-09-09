/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class RenderUtils {
    public static Vec3 center;
    public static final Matrix4f projection = new Matrix4f();

    private static final Pool<RenderBlock> renderBlockPool = new Pool<>(RenderBlock::new);
    private static final List<RenderBlock> renderBlocks = new ObjectArrayList<>();

    private RenderUtils() {
    }

    @PostInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(RenderUtils.class);
    }

    public static boolean isShaderPackInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    // Items
    public static void drawItem(GuiGraphics graphics, ItemStack itemStack, int x, int y, float scale, boolean overlay, String countOverride) {
        PoseStack matrices = graphics.pose();
        matrices.pushPose();

        matrices.scale(scale, scale, 1);
        matrices.translate(0, 0, 401); // Thanks Mojang

        int scaledX = (int) (x / scale);
        int scaledY = (int) (y / scale);

        graphics.renderItem(itemStack, scaledX, scaledY);
        if (overlay) graphics.renderItemDecorations(mc.font, itemStack, scaledX, scaledY, countOverride);

        matrices.popPose();
    }

    public static void drawItem(GuiGraphics graphics, ItemStack itemStack, int x, int y, float scale, boolean overlay) {
        drawItem(graphics, itemStack, x, y, scale, overlay, null);
    }

    public static void updateScreenCenter(Matrix4fc projection, Matrix4fc view) {
        RenderUtils.projection.set(projection);

        Matrix4f invProjection = new Matrix4f(projection).invert();
        Matrix4f invView = new Matrix4f(view).invert();

        Vector4f center4 = new Vector4f(0, 0, 0, 1).mul(invProjection).mul(invView);
        center4.div(center4.w);

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        center = new Vec3(camera.x + center4.x, camera.y + center4.y, camera.z + center4.z);
    }

    /**
     * How far off the view axis a clamped tracer endpoint is placed, as a multiple of the tracer origin's
     * distance from the camera. Anything above roughly {@code aspect * tan(fov / 2)} lands off screen; this
     * leaves a wide margin for ultrawide monitors and high fov values.
     */
    private static final double TRACER_CLAMP_SPREAD = 50;

    /**
     * Draws a tracer from the screen centre to the given position.
     *
     * <p>Drawing a plain line to a target behind the camera does not work: the near plane cuts it off a
     * fraction of a block from {@link #center}, so all that survives is a short stub at the crosshair,
     * which reads as if the target were in front of you. When the target is behind the tracer origin this
     * replaces the endpoint with a point on the origin's depth plane, offset along the same direction you
     * would have to turn to face the target and far enough out to leave the screen. The tracer then runs
     * from the crosshair off the correct screen edge instead of collapsing into the middle.
     */
    public static void drawTracer(Renderer3D renderer, double x, double y, double z, Color color) {
        Vec3 origin = center;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        // Read the view axis off the camera rotation rather than deriving it from origin - camera.
        // Vanilla multiplies view bob into the projection matrix, so #center is displaced by up to a
        // tenth of a block while sitting only that far in front of the camera; a direction taken from
        // the two would swing by tens of degrees on every step.
        Vector3f look = mc.gameRenderer.getMainCamera().getLookVector();
        double fx = look.x(), fy = look.y(), fz = look.z();

        double depth = (origin.x - camera.x) * fx + (origin.y - camera.y) * fy + (origin.z - camera.z) * fz;

        if (depth > 0) {
            double vx = x - camera.x, vy = y - camera.y, vz = z - camera.z;
            double forwardDist = vx * fx + vy * fy + vz * fz;

            if (forwardDist < depth) {
                // Component of the target direction perpendicular to the view axis - the way you'd turn.
                double lx = vx - fx * forwardDist, ly = vy - fy * forwardDist, lz = vz - fz * forwardDist;
                double lLen = Math.sqrt(lx * lx + ly * ly + lz * lz);

                if (lLen < 1e-6) {
                    // Target is directly behind the camera, so any perpendicular will do.
                    lx = -fz;
                    ly = 0;
                    lz = fx;
                    lLen = Math.sqrt(lx * lx + lz * lz);

                    if (lLen < 1e-6) {
                        lx = 1;
                        lz = 0;
                        lLen = 1;
                    }
                }

                double scale = depth * TRACER_CLAMP_SPREAD / lLen;

                x = origin.x + lx * scale;
                y = origin.y + ly * scale;
                z = origin.z + lz * scale;
            }
        }

        renderer.line(origin.x, origin.y, origin.z, x, y, z, color);
    }

    public static void renderTickingBlock(BlockPos blockPos, Color sideColor, Color lineColor, ShapeMode shapeMode, int excludeDir, int duration, boolean fade, boolean shrink) {
        // Ensure there aren't multiple fading blocks in one pos
        renderBlocks.removeIf(next -> {
            if (next.pos.equals(blockPos)) {
                renderBlockPool.free(next);
                return true;
            } else {
                return false;
            }
        });

        renderBlocks.add(renderBlockPool.get().set(blockPos, sideColor, lineColor, shapeMode, excludeDir, duration, fade, shrink));
    }

    @EventHandler
    private static void onTick(TickEvent.Pre event) {
        if (renderBlocks.isEmpty()) return;

        renderBlocks.removeIf(next -> {
            next.tick();

            if (next.ticks <= 0) {
                renderBlockPool.free(next);
                return true;
            } else {
                return false;
            }
        });
    }

    @EventHandler
    private static void onRender(Render3DEvent event) {
        renderBlocks.forEach(block -> block.render(event));
    }

    public static class RenderBlock {
        public BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        public Color sideColor, lineColor;
        public ShapeMode shapeMode;
        public int excludeDir;

        public int ticks, duration;
        public boolean fade, shrink;

        public RenderBlock set(BlockPos blockPos, Color sideColor, Color lineColor, ShapeMode shapeMode, int excludeDir, int duration, boolean fade, boolean shrink) {
            pos.set(blockPos);
            this.sideColor = sideColor;
            this.lineColor = lineColor;
            this.shapeMode = shapeMode;
            this.excludeDir = excludeDir;
            this.fade = fade;
            this.shrink = shrink;
            this.ticks = duration;
            this.duration = duration;

            return this;
        }

        public void tick() {
            ticks--;
        }

        public void render(Render3DEvent event) {
            int preSideA = sideColor.a;
            int preLineA = lineColor.a;
            double x1 = pos.getX(), y1 = pos.getY(), z1 = pos.getZ(),
                x2 = pos.getX() + 1, y2 = pos.getY() + 1, z2 = pos.getZ() + 1;

            double d = (double) (ticks - event.tickDelta) / duration;

            if (fade) {
                sideColor.a = (int) (sideColor.a * d);
                lineColor.a = (int) (lineColor.a * d);
            }
            if (shrink) {
                x1 += d;
                y1 += d;
                z1 += d;
                x2 -= d;
                y2 -= d;
                z2 -= d;
            }

            event.renderer.box(x1, y1, z1, x2, y2, z2, sideColor, lineColor, shapeMode, excludeDir);

            sideColor.a = preSideA;
            lineColor.a = preLineA;
        }
    }
}
