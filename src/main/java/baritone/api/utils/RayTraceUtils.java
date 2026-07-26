/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class RayTraceUtils {
    private RayTraceUtils() {
    }

    public static HitResult rayTraceTowards(Entity entity, Rotation rotation, double blockReachDistance) {
        return rayTraceTowards(entity, rotation, blockReachDistance, false);
    }

    public static HitResult rayTraceTowards(
            Entity entity,
            Rotation rotation,
            double blockReachDistance,
            boolean wouldSneak
    ) {
        Vec3 start = wouldSneak ? inferSneakingEyePosition(entity) : entity.getEyePosition(1.0F);
        Vec3 direction = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = start.add(
                direction.x * blockReachDistance,
                direction.y * blockReachDistance,
                direction.z * blockReachDistance
        );
        return entity.level().clip(new ClipContext(
                start,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                entity
        ));
    }

    public static Vec3 inferSneakingEyePosition(Entity entity) {
        return new Vec3(
                entity.getX(),
                entity.getY() + entity.getEyeHeight(Pose.CROUCHING),
                entity.getZ()
        );
    }
}
