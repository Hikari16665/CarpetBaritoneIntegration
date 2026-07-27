package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.MutableMoveResult;

import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Low-budget 2D Jump Point Search fast path. It deliberately accepts only
 * same-Y movements that require no break/place side effects. Complex terrain
 * is reported as unsupported and is handled by the normal A* fallback.
 */
public final class JumpPointPathFinder extends AbstractNodeCostSearch {
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final CalculationContext context;
    private final BetterBlockPos explicitGoal;

    public JumpPointPathFinder(
            BetterBlockPos start, Goal goal,
            CalculationContext context) {
        super(start, start.x, start.y, start.z, goal, context);
        this.context = context;
        this.explicitGoal = goal instanceof IGoalRenderPos render
                ? BetterBlockPos.from(render.getGoalPos()) : null;
    }

    public boolean isApplicable() {
        return explicitGoal != null
                && explicitGoal.y == startY
                && context.isLoaded(explicitGoal.x, explicitGoal.z);
    }

    @Override
    protected Optional<IPath> calculate0(
            long primaryTimeout, long failureTimeout) {
        if (!isApplicable()) return Optional.empty();
        long deadline = System.nanoTime()
                + Math.max(1L, failureTimeout) * 1_000_000L;
        PriorityQueue<PathNode> open = new PriorityQueue<>(
                Comparator.comparingDouble(node -> node.combinedCost));
        startNode = getNodeAtPosition(startX, startY, startZ,
                BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0.0D;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        open.add(startNode);
        int considered = 0;
        while (!open.isEmpty() && !cancelRequested
                && System.nanoTime() < deadline) {
            PathNode current = open.poll();
            mostRecentConsidered = current;
            considered++;
            if (goal.isInGoal(current.x, current.y, current.z)) {
                return Optional.of(new Path(
                        realStart, startNode, current,
                        considered, goal, context));
            }
            for (int[] direction : DIRECTIONS) {
                Jump jump = jump(current, direction[0], direction[1],
                        deadline);
                if (jump == null) continue;
                PathNode node = getNodeAtPosition(
                        jump.end.x, jump.end.y, jump.end.z,
                        BetterBlockPos.longHash(
                                jump.end.x, jump.end.y, jump.end.z));
                double candidate = current.cost + jump.cost;
                if (candidate + MIN_IMPROVEMENT >= node.cost) continue;
                node.cost = candidate;
                node.combinedCost =
                        candidate + node.estimatedCostToGoal;
                node.previous = jump.previous;
                open.remove(node);
                open.add(node);
                for (int index = 0; index < bestSoFar.length; index++) {
                    if (bestSoFar[index] == null
                            || node.estimatedCostToGoal
                            < bestSoFar[index].estimatedCostToGoal) {
                        bestSoFar[index] = node;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Jump jump(
            PathNode origin, int dx, int dz, long deadline) {
        int x = origin.x;
        int z = origin.z;
        double total = 0.0D;
        PathNode previous = origin;
        for (int distance = 1; distance <= 256
                && System.nanoTime() < deadline; distance++) {
            Step step = step(x, z, dx, dz);
            if (step == null) return null;
            x += dx;
            z += dz;
            total += step.cost;
            PathNode intermediate = new PathNode(x, startY, z, goal);
            intermediate.cost = origin.cost + total;
            intermediate.combinedCost = intermediate.cost
                    + intermediate.estimatedCostToGoal;
            intermediate.previous = previous;
            previous = intermediate;
            if (goal.isInGoal(x, startY, z)
                    || hasForcedNeighbor(x, z, dx, dz)
                    || alignedWithGoal(x, z, dx, dz)) {
                return new Jump(intermediate, previous.previous, total);
            }
        }
        return null;
    }

    private boolean alignedWithGoal(
            int x, int z, int dx, int dz) {
        int goalDx = explicitGoal.x - x;
        int goalDz = explicitGoal.z - z;
        if (dx == 0) return goalDz == 0;
        if (dz == 0) return goalDx == 0;
        return goalDx == 0 || goalDz == 0
                || Math.abs(goalDx) == Math.abs(goalDz);
    }

    private boolean hasForcedNeighbor(
            int x, int z, int dx, int dz) {
        if (dx != 0 && dz != 0) {
            return !walkable(x - dx, z)
                    && walkable(x - dx, z + dz)
                    || !walkable(x, z - dz)
                    && walkable(x + dx, z - dz);
        }
        if (dx != 0) {
            return !walkable(x, z + 1)
                    && walkable(x + dx, z + 1)
                    || !walkable(x, z - 1)
                    && walkable(x + dx, z - 1);
        }
        return !walkable(x + 1, z)
                && walkable(x + 1, z + dz)
                || !walkable(x - 1, z)
                && walkable(x - 1, z + dz);
    }

    private boolean walkable(int x, int z) {
        return flatOpen(x, z);
    }

    private Step step(int x, int z, int dx, int dz) {
        if (!flatOpen(x + dx, z + dz)) return null;
        if (dx != 0 && dz != 0
                && (!flatOpen(x + dx, z)
                || !flatOpen(x, z + dz))) return null;
        Moves move = move(dx, dz);
        MutableMoveResult result = new MutableMoveResult();
        move.apply(context, x, startY, z, result);
        if (result.cost >= ActionCosts.COST_INF
                || result.x != x + dx || result.y != startY
                || result.z != z + dz) return null;
        return new Step(result.cost);
    }

    private boolean flatOpen(int x, int z) {
        var feet = context.get(x, startY, z);
        var head = context.get(x, startY + 1, z);
        var support = context.get(x, startY - 1, z);
        return feet.isAir() && head.isAir()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && !support.getCollisionShape(
                context.world, new BetterBlockPos(
                        x, startY - 1, z)).isEmpty();
    }

    private static Moves move(int dx, int dz) {
        if (dx == 1 && dz == 0) return Moves.TRAVERSE_EAST;
        if (dx == -1 && dz == 0) return Moves.TRAVERSE_WEST;
        if (dx == 0 && dz == 1) return Moves.TRAVERSE_SOUTH;
        if (dx == 0 && dz == -1) return Moves.TRAVERSE_NORTH;
        if (dx == 1 && dz == 1) return Moves.DIAGONAL_SOUTHEAST;
        if (dx == 1 && dz == -1) return Moves.DIAGONAL_NORTHEAST;
        if (dx == -1 && dz == 1) return Moves.DIAGONAL_SOUTHWEST;
        return Moves.DIAGONAL_NORTHWEST;
    }

    private record Step(double cost) { }
    private record Jump(PathNode end, PathNode previous, double cost) { }
}
