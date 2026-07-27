package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;

import java.util.Optional;

/** Low-budget JPS first, full upstream A* fallback. */
public final class HybridPathFinder implements IPathFinder {
    private final JumpPointPathFinder jps;
    private final AStarPathFinder aStar;
    private final Goal goal;
    private volatile AbstractNodeCostSearch current;
    private volatile boolean cancelled;
    private volatile boolean finished;

    public HybridPathFinder(
            BetterBlockPos start, Goal goal,
            Favoring favoring, CalculationContext context) {
        this.goal = goal;
        this.jps = new JumpPointPathFinder(start, goal, context);
        this.aStar = new AStarPathFinder(
                start, start.x, start.y, start.z,
                goal, favoring, context);
        this.current = jps;
    }

    @Override
    public PathCalculationResult calculate(
            long primaryTimeout, long failureTimeout) {
        try {
            if (jps.isApplicable() && !cancelled) {
                long jpsPrimary = Math.max(10L,
                        Math.min(100L, primaryTimeout / 4L));
                long jpsFailure = Math.max(jpsPrimary,
                        Math.min(250L, failureTimeout / 4L));
                PathCalculationResult quick =
                        jps.calculate(jpsPrimary, jpsFailure);
                if (quick.getType()
                        == PathCalculationResult.Type.SUCCESS_TO_GOAL) {
                    return quick;
                }
            }
            if (cancelled) {
                return new PathCalculationResult(
                        PathCalculationResult.Type.CANCELLATION);
            }
            current = aStar;
            return aStar.calculate(primaryTimeout, failureTimeout);
        } finally {
            finished = true;
        }
    }

    public void cancel() {
        cancelled = true;
        jps.cancel();
        aStar.cancel();
    }

    @Override public Goal getGoal() { return goal; }
    @Override public boolean isFinished() { return finished; }
    @Override public Optional<IPath> pathToMostRecentNodeConsidered() {
        return current.pathToMostRecentNodeConsidered();
    }
    @Override public Optional<IPath> bestPathSoFar() {
        return current.bestPathSoFar();
    }
}
