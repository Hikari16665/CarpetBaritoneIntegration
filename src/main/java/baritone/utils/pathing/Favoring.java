/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.utils.pathing;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Helper;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.List;

/**
 * Movement cost multipliers. Entity avoidance will be added with the
 * avoidance module; previous-path favoring is already fully supported.
 */
public final class Favoring {
    private final Long2DoubleOpenHashMap favorings;
    private volatile List<Avoidance> pendingAvoidances;

    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        this(previous, context);
        // Entity/block positions are snapshotted on the server thread, while
        // the expensive spherical expansion is delayed until A* first reads
        // this object on its worker.
        pendingAvoidances = Avoidance.create(ctx);
    }

    public Favoring(IPath previous, CalculationContext context) {
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        double coefficient = context.backtrackCostFavoringCoefficient;
        if (coefficient != 1.0D && previous != null) {
            previous.positions().forEach(pos ->
                    favorings.put(BetterBlockPos.longHash(pos), coefficient));
        }
    }

    public boolean isEmpty() {
        applyPendingAvoidances();
        return favorings.isEmpty();
    }

    public double calculate(long hash) {
        applyPendingAvoidances();
        return favorings.get(hash);
    }

    private void applyPendingAvoidances() {
        List<Avoidance> pending = pendingAvoidances;
        if (pending == null) return;
        synchronized (this) {
            pending = pendingAvoidances;
            if (pending == null) return;
            pendingAvoidances = null;
            for (Avoidance avoidance : pending) {
                avoidance.applySpherical(favorings);
            }
            Helper.HELPER.logDebug(
                    "Favoring size: " + favorings.size());
        }
    }
}
