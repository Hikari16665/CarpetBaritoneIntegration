/*
 * Server-side host for upstream PathExecutor.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.behavior;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;

/**
 * The server event bridge is rebuilt around this host. Its public surface is
 * the subset consumed by the unmodified upstream PathExecutor.
 */
public final class PathingBehavior implements IPathingBehavior {
    public final Baritone baritone;
    public final IPlayerContext ctx;
    private CalculationContext calculationContext;
    private AbstractNodeCostSearch inProgress;

    public PathingBehavior(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.calculationContext = new CalculationContext(baritone);
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return calculationContext;
    }

    public void refreshCalculationContext() {
        calculationContext = new CalculationContext(baritone);
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    public void setInProgress(AbstractNodeCostSearch inProgress) {
        this.inProgress = inProgress;
    }

    public BlockStateInterface blockStateInterface() {
        return calculationContext.bsi;
    }

    /**
     * Original PathingBehavior#pathStart. This is essential when flowing
     * liquids push the player partially off a block or while the player is
     * between two vertical block positions.
     */
    public BetterBlockPos pathStart() {
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.below())) {
            if (ctx.player().onGround()) {
                double playerX = ctx.player().position().x;
                double playerZ = ctx.player().position().z;
                ArrayList<BetterBlockPos> closest = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                    }
                }
                closest.sort(Comparator.comparingDouble(pos ->
                        ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX)
                                + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
                for (int i = 0; i < 4; i++) {
                    BetterBlockPos possibleSupport = closest.get(i);
                    double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                    double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
                    if (xDist > 0.8D && zDist > 0.8D) {
                        continue;
                    }
                    if (MovementHelper.canWalkOn(ctx, possibleSupport.below())
                            && MovementHelper.canWalkThrough(ctx, possibleSupport)
                            && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                        return possibleSupport;
                    }
                }
            } else if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
                return feet.below();
            }
        }
        return feet;
    }

    @Override public Optional<Double> estimatedTicksToGoal() {
        IPathExecutor current = getCurrent();
        if (current == null) return Optional.empty();
        return Optional.of(current.getPath().ticksRemainingFrom(current.getPosition()));
    }

    @Override public Goal getGoal() {
        return baritone.getActiveGoal();
    }

    @Override public boolean isPathing() {
        return baritone.getPathExecutor() != null;
    }

    @Override public boolean cancelEverything() {
        boolean active = isPathing() || getGoal() != null;
        baritone.cancelAll();
        return active;
    }

    @Override public void forceCancel() {
        baritone.cancelAll();
    }

    @Override public IPathExecutor getCurrent() {
        return baritone.getPathExecutor();
    }

    @Override public IPathExecutor getNext() {
        return baritone.getNextPathExecutor();
    }
}
