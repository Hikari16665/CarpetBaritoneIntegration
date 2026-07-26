/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.level.block.Blocks;
import baritone.cache.ServerWorldCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Upstream Baritone spherical mob avoidance, backed by server entities. */
public class Avoidance {
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    public double coefficient(int x, int y, int z) {
        int xDiff = x - centerX;
        int yDiff = y - centerY;
        int zDiff = z - centerZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= radiusSq
                ? coefficient
                : 1.0D;
    }

    public static List<Avoidance> create(IPlayerContext ctx) {
        if (!Baritone.settings().avoidance.value) {
            return Collections.emptyList();
        }
        List<Avoidance> result = new ArrayList<>();
        double spawnerCoefficient = Baritone.settings().mobSpawnerAvoidanceCoefficient.value;
        if (spawnerCoefficient != 1.0D) {
            BlockPos feet = ctx.playerFeet();
            int scanRadius = Baritone.settings().mobSpawnerAvoidanceRadius.value;
            ServerWorldCache.get(ctx.world()).locationsOf(Blocks.SPAWNER)
                    .stream()
                    .filter(pos -> pos.distSqr(feet)
                            <= (double) scanRadius * scanRadius)
                    .forEach(pos -> result.add(new Avoidance(
                            pos, spawnerCoefficient, scanRadius)));
        }
        double coefficient = Baritone.settings().mobAvoidanceCoefficient.value;
        if (coefficient != 1.0D) {
            ctx.entitiesStream()
                    .filter(entity -> entity instanceof Mob)
                    .filter(entity -> !(entity instanceof Spider)
                            || ctx.player().getLightLevelDependentMagicValue() < 0.5F)
                    .filter(entity -> !(entity instanceof ZombifiedPiglin piglin)
                            || piglin.getLastHurtByMob() != null)
                    .filter(entity -> !(entity instanceof EnderMan enderman)
                            || enderman.isCreepy())
                    .forEach(entity -> result.add(new Avoidance(
                            entity.blockPosition(),
                            coefficient,
                            Baritone.settings().mobAvoidanceRadius.value
                    )));
        }
        return result;
    }

    public void applySpherical(Long2DoubleOpenHashMap map) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        long hash = BetterBlockPos.longHash(centerX + x, centerY + y, centerZ + z);
                        map.put(hash, map.get(hash) * coefficient);
                    }
                }
            }
        }
    }
}
