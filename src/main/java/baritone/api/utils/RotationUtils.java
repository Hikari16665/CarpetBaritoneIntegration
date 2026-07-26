/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import baritone.api.BaritoneAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

public final class RotationUtils {
    public static final double DEG_TO_RAD = Math.PI / 180.0D;
    public static final float DEG_TO_RAD_F = (float) DEG_TO_RAD;
    public static final double RAD_TO_DEG = 180.0D / Math.PI;
    public static final float RAD_TO_DEG_F = (float) RAD_TO_DEG;
    public static final double DEFAULT_BLOCK_REACH_DISTANCE = 5.0D;

    private static final Vec3[] BLOCK_SIDE_MULTIPLIERS = {
            new Vec3(0.5, 0, 0.5),
            new Vec3(0.5, 1, 0.5),
            new Vec3(0.5, 0.5, 0),
            new Vec3(0.5, 0.5, 1),
            new Vec3(0, 0.5, 0.5),
            new Vec3(1, 0.5, 0.5)
    };

    private RotationUtils() {
    }

    public static Rotation calcRotationFromCoords(BlockPos orig, BlockPos dest) {
        return calcRotationFromVec3d(
                new Vec3(orig.getX(), orig.getY(), orig.getZ()),
                new Vec3(dest.getX(), dest.getY(), dest.getZ())
        );
    }

    public static Rotation wrapAnglesToRelative(Rotation current, Rotation target) {
        if (current.yawIsReallyClose(target)) {
            return new Rotation(current.getYaw(), target.getPitch());
        }
        return target.subtract(current).normalize().add(current);
    }

    public static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest, Rotation current) {
        return wrapAnglesToRelative(current, calcRotationFromVec3d(orig, dest));
    }

    public static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest) {
        double deltaX = orig.x - dest.x;
        double deltaY = orig.y - dest.y;
        double deltaZ = orig.z - dest.z;
        double yaw = Mth.atan2(deltaX, -deltaZ);
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double pitch = Mth.atan2(deltaY, distance);
        return new Rotation((float) (yaw * RAD_TO_DEG), (float) (pitch * RAD_TO_DEG));
    }

    public static Vec3 calcLookDirectionFromRotation(Rotation rotation) {
        float flatZ = Mth.cos(-rotation.getYaw() * DEG_TO_RAD_F - (float) Math.PI);
        float flatX = Mth.sin(-rotation.getYaw() * DEG_TO_RAD_F - (float) Math.PI);
        float pitchBase = -Mth.cos(-rotation.getPitch() * DEG_TO_RAD_F);
        float pitchHeight = Mth.sin(-rotation.getPitch() * DEG_TO_RAD_F);
        return new Vec3(flatX * pitchBase, pitchHeight, flatZ * pitchBase);
    }

    @Deprecated
    public static Vec3 calcVec3dFromRotation(Rotation rotation) {
        return calcLookDirectionFromRotation(rotation);
    }

    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos) {
        return reachable(ctx, pos, DEFAULT_BLOCK_REACH_DISTANCE, false);
    }

    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos, boolean wouldSneak) {
        return reachable(ctx, pos, DEFAULT_BLOCK_REACH_DISTANCE, wouldSneak);
    }

    public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos, double reach) {
        return reachable(ctx, pos, reach, false);
    }

    public static Optional<Rotation> reachable(
            IPlayerContext ctx,
            BlockPos pos,
            double reach,
            boolean wouldSneak
    ) {
        if (pos instanceof BetterBlockPos) {
            pos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        }
        if (BaritoneAPI.getSettings().remainWithExistingLookDirection.value && ctx.isLookingAt(pos)) {
            Rotation current = ctx.playerRotations().add(new Rotation(0, 0.0001F));
            if (!wouldSneak || hits(ctx, pos, current, reach, true)) {
                return Optional.of(current);
            }
        }
        Optional<Rotation> center = reachableCenter(ctx, pos, reach, wouldSneak);
        if (center.isPresent()) {
            return center;
        }
        BlockState state = ctx.world().getBlockState(pos);
        VoxelShape shape = state.getShape(ctx.world(), pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        for (Vec3 side : BLOCK_SIDE_MULTIPLIERS) {
            double x = shape.min(Direction.Axis.X) * side.x + shape.max(Direction.Axis.X) * (1 - side.x);
            double y = shape.min(Direction.Axis.Y) * side.y + shape.max(Direction.Axis.Y) * (1 - side.y);
            double z = shape.min(Direction.Axis.Z) * side.z + shape.max(Direction.Axis.Z) * (1 - side.z);
            Optional<Rotation> result = reachableOffset(
                    ctx,
                    pos,
                    new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z),
                    reach,
                    wouldSneak
            );
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    public static Optional<Rotation> reachableCenter(
            IPlayerContext ctx,
            BlockPos pos,
            double reach,
            boolean wouldSneak
    ) {
        return reachableOffset(ctx, pos, VecUtils.calculateBlockCenter(ctx.world(), pos), reach, wouldSneak);
    }

    public static Optional<Rotation> reachableOffset(
            IPlayerContext ctx,
            BlockPos pos,
            Vec3 offset,
            double reach,
            boolean wouldSneak
    ) {
        Vec3 eyes = wouldSneak
                ? RayTraceUtils.inferSneakingEyePosition(ctx.player())
                : ctx.player().getEyePosition(1.0F);
        Rotation rotation = calcRotationFromVec3d(eyes, offset, ctx.playerRotations());
        return hits(ctx, pos, rotation, reach, wouldSneak) ? Optional.of(rotation) : Optional.empty();
    }

    private static boolean hits(
            IPlayerContext ctx,
            BlockPos pos,
            Rotation rotation,
            double reach,
            boolean wouldSneak
    ) {
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), rotation, reach, wouldSneak);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();
        if (hitPos.equals(pos)) {
            return true;
        }
        return ctx.world().getBlockState(pos).getBlock() instanceof BaseFireBlock
                && hitPos.equals(pos.below());
    }
}
