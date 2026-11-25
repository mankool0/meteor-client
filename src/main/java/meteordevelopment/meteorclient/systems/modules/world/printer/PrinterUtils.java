/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashSet;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Raytracing and reach helpers for the printer. The face sampling is from dino-printer by dinosaurwizard (CC0), the
 * reach rules are the ones mankool0/baritone's printer matched to Grim.
 */
public final class PrinterUtils {
    // the shape air placing clicks, slightly smaller than a block
    private static final VoxelShape AIR_SHAPE = Shapes.box(0.01, 0.01, 0.01, 1.0 - 0.01, 1.0 - 0.01, 1.0 - 0.01);

    private static final double[] SAMPLES = {0.1666666667, 0.5, 0.8333333333};

    private PrinterUtils() {
    }

    /**
     * Whether the server takes a click on {@code face} of {@code clicked} from a player standing at {@code feet}. Grim's
     * FarPlace measures to the closest point of the clicked block from every eye height the player could have,
     * crouching included, whatever its pose. Its PositionPlace also wants the clicked face turned towards those eyes,
     * which {@code strictFaces} checks; measured to the block rather than the face, a face on its far side would pass
     * the reach check as easily as a near one.
     */
    public static boolean reaches(BlockPos clicked, Direction face, Vec3 feet, double range, boolean strictFaces) {
        double standingEye = feet.y + mc.player.getEyeHeight(Pose.STANDING);
        double crouchingEye = feet.y + mc.player.getEyeHeight(Pose.CROUCHING);

        if (strictFaces) {
            AABB box = faceBox(clicked);
            boolean facing = switch (face) {
                case UP -> standingEye >= box.maxY;
                case DOWN -> crouchingEye <= box.minY;
                case EAST -> feet.x >= box.maxX;
                case WEST -> feet.x <= box.minX;
                case SOUTH -> feet.z >= box.maxZ;
                case NORTH -> feet.z <= box.minZ;
            };
            if (!facing) return false;
        }

        AABB block = new AABB(clicked);
        double rangeSq = range * range;
        return block.distanceToSqr(new Vec3(feet.x, standingEye, feet.z)) <= rangeSq
            || block.distanceToSqr(new Vec3(feet.x, crouchingEye, feet.z)) <= rangeSq;
    }

    /**
     * Grim checks the clicked face against the block's collision box, or a full block when it has none.
     */
    private static AABB faceBox(BlockPos pos) {
        VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
        return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
    }

    /**
     * {@link #reaches} from where the player is now and from where it will be next tick, since the server checks the
     * click against the movement packet after it too.
     */
    public static boolean reachesNowAndNext(BlockPos clicked, Direction face, double range, boolean strictFaces) {
        Vec3 feet = mc.player.position();
        return reaches(clicked, face, feet, range, strictFaces)
            && reaches(clicked, face, feet.add(mc.player.getDeltaMovement()), range, strictFaces);
    }

    /**
     * Whether a player can see a point on a block's face.
     */
    public static boolean isPointVisible(Vec3 point, BlockPos blockPos, Direction direction) {
        ClipContext context = new ClipContext(mc.player.getEyePosition(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult ray = mc.level.clip(context);

        return ray.getType() == HitResult.Type.MISS || (ray.getBlockPos().equals(blockPos) && ray.getDirection() == direction);
    }

    /**
     * What a click with this rotation hits, the way vanilla picks the block under the crosshair.
     */
    public static BlockHitResult raycast(Vec3 eye, float yaw, float pitch, double range) {
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }

    /**
     * Whether the look ray with this rotation passes through the block, which is what Grim's RotationPlace checks.
     */
    public static boolean rayHitsBlock(Vec3 eye, float yaw, float pitch, BlockPos pos, double range) {
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
        return new AABB(pos).clip(eye, end).isPresent();
    }

    public static float yawTo(Vec3 eye, Vec3 point) {
        return mc.player.getYRot() + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(point.z - eye.z, point.x - eye.x)) - 90f - mc.player.getYRot());
    }

    public static float pitchTo(Vec3 eye, Vec3 point) {
        double diffX = point.x - eye.x;
        double diffY = point.y - eye.y;
        double diffZ = point.z - eye.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getXRot() + Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getXRot());
    }

    /**
     * Points spread over a face of a block's shape, 9 per box of the shape, so stairs or hoppers get more. Air and other
     * blocks that can be replaced give the points of a block slightly smaller than a full one.
     */
    public static Set<Vec3> getShapeFacePoints(BlockPos blockPos, Direction face) {
        Set<Vec3> points = new LinkedHashSet<>();

        BlockState state = mc.level.getBlockState(blockPos);
        VoxelShape shape = state.canBeReplaced() ? AIR_SHAPE : state.getShape(mc.level, blockPos);

        for (AABB box : shape.toAabbs()) {
            double minX = box.minX + blockPos.getX(), minY = box.minY + blockPos.getY(), minZ = box.minZ + blockPos.getZ();
            double maxX = box.maxX + blockPos.getX(), maxY = box.maxY + blockPos.getY(), maxZ = box.maxZ + blockPos.getZ();

            for (double u : SAMPLES) {
                for (double v : SAMPLES) {
                    switch (face) {
                        case DOWN -> points.add(new Vec3(Mth.lerp(u, minX, maxX), minY, Mth.lerp(v, minZ, maxZ)));
                        case UP -> points.add(new Vec3(Mth.lerp(u, minX, maxX), maxY, Mth.lerp(v, minZ, maxZ)));
                        case NORTH -> points.add(new Vec3(Mth.lerp(u, minX, maxX), Mth.lerp(v, minY, maxY), minZ));
                        case SOUTH -> points.add(new Vec3(Mth.lerp(u, minX, maxX), Mth.lerp(v, minY, maxY), maxZ));
                        case WEST -> points.add(new Vec3(minX, Mth.lerp(u, minY, maxY), Mth.lerp(v, minZ, maxZ)));
                        case EAST -> points.add(new Vec3(maxX, Mth.lerp(u, minY, maxY), Mth.lerp(v, minZ, maxZ)));
                    }
                }
            }
        }

        return points;
    }
}
