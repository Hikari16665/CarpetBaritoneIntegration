package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;

import java.util.Optional;

/**
 * HPA* coordinator retained under the old class name for API compatibility.
 * Long routes are refined 16-chunk region -> chunk -> bounded block segment.
 */
public final class HybridPathFinder implements IPathFinder {
    private final BetterBlockPos start;
    private final Goal goal;
    private final Favoring favoring;
    private final CalculationContext context;
    private volatile AStarPathFinder current;
    private volatile boolean cancelled;
    private volatile boolean finished;

    public HybridPathFinder(
            BetterBlockPos start, Goal goal,
            Favoring favoring, CalculationContext context) {
        this.start = start;
        this.goal = goal;
        this.favoring = favoring;
        this.context = context;
    }

    @Override
    public PathCalculationResult calculate(
            long primaryTimeout, long failureTimeout) {
        try {
            HierarchicalPathPlanner planner = new HierarchicalPathPlanner();
            HierarchicalPathPlanner.Plan plan =
                    planner.plan(start, goal, 1);
            if (plan != null && !cancelled) {
                if (Baritone.settings().diagnosticLogging.value) {
                    System.out.println("[CBI-DIAG] hpa-plan start=" + start
                            + " regions=" + plan.regions().size()
                            + " clusters16=" + plan.clusters().size()
                            + " chunks=" + plan.chunks().size()
                            + " blockGoal=" + plan.refinementGoal()
                            + " final=" + plan.finalSegment());
                }
                current = new AStarPathFinder(start, start.x, start.y,
                        start.z, plan.refinementGoal(), favoring, context,
                        plan.corridor());
                long started = System.currentTimeMillis();
                long corridorPrimary = Math.min(primaryTimeout, 1_500L);
                long corridorFailure = Math.min(failureTimeout, 3_000L);
                PathCalculationResult result = current.calculate(
                        corridorPrimary, corridorFailure);
                if (result.getType()
                        == PathCalculationResult.Type.CANCELLATION) return result;
                if (result.getPath().isPresent()) {
                    IPath path = result.getPath().get();
                    return new PathCalculationResult(
                            goal.isInGoal(path.getDest())
                                    ? PathCalculationResult.Type.SUCCESS_TO_GOAL
                                    : PathCalculationResult.Type.SUCCESS_SEGMENT,
                            path);
                }
                long elapsed = System.currentTimeMillis() - started;
                return fallback(Math.max(250L, primaryTimeout - elapsed),
                        Math.max(1_000L, failureTimeout - elapsed));
            }
            /*
             * Not every Goal has a meaningful single X/Z destination
             * (GoalRunAway, GoalYLevel, inverted/directional goals, and
             * process-specific wrappers). Failing before searching made those
             * commands deterministically unusable. Preserve original Baritone
             * semantics by using its unbounded block A* for such goals.
             */
            return fallback(primaryTimeout, failureTimeout);
        } finally {
            finished = true;
        }
    }

    private PathCalculationResult fallback(
            long primaryTimeout, long failureTimeout) {
        if (cancelled) {
            return new PathCalculationResult(
                    PathCalculationResult.Type.CANCELLATION);
        }
        if (Baritone.settings().diagnosticLogging.value) {
            System.out.println("[CBI-DIAG] hpa-fallback start=" + start
                    + " goal=" + goal + " primaryMS=" + primaryTimeout
                    + " failureMS=" + failureTimeout);
        }
        current = new AStarPathFinder(start, start.x, start.y, start.z,
                goal, favoring, context, PathCorridor.UNBOUNDED);
        return current.calculate(primaryTimeout, failureTimeout);
    }

    public void cancel() {
        cancelled = true;
        AStarPathFinder finder = current;
        if (finder != null) finder.cancel();
    }

    @Override public Goal getGoal() { return goal; }
    @Override public boolean isFinished() { return finished; }
    @Override public Optional<IPath> pathToMostRecentNodeConsidered() {
        AStarPathFinder finder = current;
        return finder == null ? Optional.empty()
                : finder.pathToMostRecentNodeConsidered();
    }
    @Override public Optional<IPath> bestPathSoFar() {
        AStarPathFinder finder = current;
        return finder == null ? Optional.empty() : finder.bestPathSoFar();
    }
}
