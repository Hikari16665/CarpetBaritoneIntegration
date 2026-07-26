/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class VecUtils {
    private VecUtils() {
    }

    public static Vec3 calculateBlockCenter(Level world, BlockPos pos) {
        if (pos instanceof BetterBlockPos) {
            pos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        }
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return getBlockPosCenter(pos);
        }
        double xDiff = (shape.min(Direction.Axis.X) + shape.max(Direction.Axis.X)) / 2;
        double yDiff = (shape.min(Direction.Axis.Y) + shape.max(Direction.Axis.Y)) / 2;
        double zDiff = (shape.min(Direction.Axis.Z) + shape.max(Direction.Axis.Z)) / 2;
        if (Double.isNaN(xDiff) || Double.isNaN(yDiff) || Double.isNaN(zDiff)) {
            throw new IllegalStateException(state + " " + pos + " " + shape);
        }
        if (state.getBlock() instanceof BaseFireBlock) {
            yDiff = 0;
        }
        return new Vec3(pos.getX() + xDiff, pos.getY() + yDiff, pos.getZ() + zDiff);
    }

    public static Vec3 getBlockPosCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    public static double distanceToCenter(BlockPos pos, double x, double y, double z) {
        double xdiff = pos.getX() + 0.5 - x;
        double ydiff = pos.getY() + 0.5 - y;
        double zdiff = pos.getZ() + 0.5 - z;
        return Math.sqrt(xdiff * xdiff + ydiff * ydiff + zdiff * zdiff);
    }

    public static double entityDistanceToCenter(Entity entity, BlockPos pos) {
        return distanceToCenter(pos, entity.position().x, entity.position().y, entity.position().z);
    }

    public static double entityFlatDistanceToCenter(Entity entity, BlockPos pos) {
        return distanceToCenter(pos, entity.position().x, pos.getY() + 0.5, entity.position().z);
    }
}
