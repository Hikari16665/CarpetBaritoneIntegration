package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.process.BuilderProcess;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Four-level HPA* planner: 32x32-chunk regions, 16x16-chunk clusters,
 * individual chunks, then a bounded block-level refinement.
 */
public final class HierarchicalPathPlanner {
    static final int REGION_CHUNKS = 32;
    static final int CLUSTER_CHUNKS = 16;
    private static final int MAX_REGION_NODES = 2048;
    private static final int MAX_CHUNK_NODES = 4096;
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    public record Plan(
            Goal refinementGoal, PathCorridor corridor,
            List<GridPos> regions, List<GridPos> clusters,
            List<GridPos> chunks,
            boolean finalSegment) { }
    public record GridPos(int x, int z) { }

    public Plan plan(BetterBlockPos start, Goal goal, int corridorRadius) {
        BlockPos target = target(goal, start);
        if (target == null) return null;
        int startChunkX = start.x >> 4;
        int startChunkZ = start.z >> 4;
        int targetChunkX = target.getX() >> 4;
        int targetChunkZ = target.getZ() >> 4;
        int chunkDistance = Math.max(Math.abs(targetChunkX - startChunkX),
                Math.abs(targetChunkZ - startChunkZ));
        if (chunkDistance == 0) {
            return new Plan(goal, PathCorridor.UNBOUNDED,
                    List.of(), List.of(), List.of(), true);
        }

        Cell regionStart = new Cell(
                Math.floorDiv(startChunkX, REGION_CHUNKS),
                Math.floorDiv(startChunkZ, REGION_CHUNKS));
        Cell regionGoal = new Cell(
                Math.floorDiv(targetChunkX, REGION_CHUNKS),
                Math.floorDiv(targetChunkZ, REGION_CHUNKS));
        List<Cell> regionPath = search(
                regionStart, regionGoal, MAX_REGION_NODES, null);
        if (regionPath.isEmpty()) return null;
        Set<Long> allowedRegions = dilate(regionPath, 1);
        Cell clusterStart = new Cell(
                Math.floorDiv(startChunkX, CLUSTER_CHUNKS),
                Math.floorDiv(startChunkZ, CLUSTER_CHUNKS));
        Cell clusterTarget = new Cell(
                Math.floorDiv(targetChunkX, CLUSTER_CHUNKS),
                Math.floorDiv(targetChunkZ, CLUSTER_CHUNKS));
        boolean finalRegion = regionPath.size() <= 2;
        Cell clusterGoal;
        if (finalRegion) {
            clusterGoal = clusterTarget;
        } else {
            Cell nextRegion = regionPath.get(1);
            int clustersPerRegion = REGION_CHUNKS / CLUSTER_CHUNKS;
            int minX = nextRegion.x * clustersPerRegion;
            int minZ = nextRegion.z * clustersPerRegion;
            clusterGoal = new Cell(
                    clamp(clusterStart.x, minX,
                            minX + clustersPerRegion - 1),
                    clamp(clusterStart.z, minZ,
                            minZ + clustersPerRegion - 1));
        }
        List<Cell> clusterPath = search(
                clusterStart, clusterGoal, MAX_REGION_NODES,
                cell -> allowedRegions.contains(key(
                        Math.floorDiv(cell.x * CLUSTER_CHUNKS,
                                REGION_CHUNKS),
                        Math.floorDiv(cell.z * CLUSTER_CHUNKS,
                                REGION_CHUNKS))));
        if (clusterPath.isEmpty()) return null;
        Set<Long> allowedClusters = dilate(clusterPath, 1);
        boolean finalCluster = finalRegion
                && clusterPath.size() <= 2
                && clusterGoal.equals(clusterTarget);
        Cell chunkGoal;
        if (finalCluster) {
            chunkGoal = new Cell(targetChunkX, targetChunkZ);
        } else {
            Cell nextCluster = clusterPath.get(1);
            int minX = nextCluster.x * CLUSTER_CHUNKS;
            int minZ = nextCluster.z * CLUSTER_CHUNKS;
            chunkGoal = new Cell(
                    clamp(startChunkX, minX,
                            minX + CLUSTER_CHUNKS - 1),
                    clamp(startChunkZ, minZ,
                            minZ + CLUSTER_CHUNKS - 1));
        }
        List<Cell> chunkPath = search(
                new Cell(startChunkX, startChunkZ),
                chunkGoal,
                MAX_CHUNK_NODES,
                cell -> allowedClusters.contains(key(
                        Math.floorDiv(cell.x, CLUSTER_CHUNKS),
                        Math.floorDiv(cell.z, CLUSTER_CHUNKS))));
        if (chunkPath.isEmpty()) return null;

        // Block A* refines exactly one abstract chunk edge at a time.
        int gatewayIndex = Math.min(1, chunkPath.size() - 1);
        boolean finalSegment = finalCluster
                && gatewayIndex == chunkPath.size() - 1
                && chunkGoal.x == targetChunkX
                && chunkGoal.z == targetChunkZ;
        Cell gateway = chunkPath.get(gatewayIndex);
        List<Cell> active = chunkPath.subList(0,
                Math.min(chunkPath.size(), 2));
        Set<Long> allowedChunks = dilate(active,
                Math.max(0, corridorRadius));
        PathCorridor corridor = (x, z) ->
                allowedChunks.contains(key(x >> 4, z >> 4));
        Goal refinementGoal = finalSegment
                ? goal : gatewayGoal(start, chunkPath.getFirst(), gateway);
        return new Plan(refinementGoal, corridor,
                regionPath.stream().map(cell ->
                        new GridPos(cell.x, cell.z)).toList(),
                clusterPath.stream().map(cell ->
                        new GridPos(cell.x, cell.z)).toList(),
                chunkPath.stream().map(cell ->
                        new GridPos(cell.x, cell.z)).toList(),
                finalSegment);
    }

    /**
     * Refine toward the nearest point just inside the next chunk instead of
     * its centre. A chunk centre is not an entrance: forcing every segment
     * through it makes an otherwise open shared border look unreachable when
     * the centre happens to be inside a wall, fluid column, or deep hole.
     */
    private static Goal gatewayGoal(
            BetterBlockPos start, Cell current, Cell gateway) {
        int minX = gateway.x << 4;
        int minZ = gateway.z << 4;
        int x = clamp(start.x, minX, minX + 15);
        int z = clamp(start.z, minZ, minZ + 15);
        if (gateway.x > current.x) x = minX;
        if (gateway.x < current.x) x = minX + 15;
        if (gateway.z > current.z) z = minZ;
        if (gateway.z < current.z) z = minZ + 15;
        return new GoalXZ(x, z);
    }

    private static BlockPos target(Goal goal, BetterBlockPos start) {
        if (goal instanceof BuilderProcess.JankyGoalComposite composite) {
            BlockPos primary = target(composite.primary(), start);
            return primary != null
                    ? primary : target(composite.fallback(), start);
        }
        if (goal instanceof IGoalRenderPos render) {
            return render.getGoalPos();
        }
        if (goal instanceof GoalXZ xz) {
            return new BlockPos(xz.getX(), start.y, xz.getZ());
        }
        if (goal instanceof GoalComposite composite) {
            return java.util.Arrays.stream(composite.goals())
                    .map(value -> target(value, start))
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.comparingDouble(goal::heuristic))
                    .orElse(null);
        }
        return null;
    }

    private static List<Cell> search(
            Cell start, Cell goal, int budget,
            java.util.function.Predicate<Cell> admitted) {
        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble(Node::f));
        Map<Long, Node> best = new HashMap<>();
        Node first = new Node(start, 0.0D, heuristic(start, goal), null);
        open.add(first);
        best.put(key(start.x, start.z), first);
        int considered = 0;
        while (!open.isEmpty() && considered++ < budget) {
            Node current = open.poll();
            if (current != best.get(key(current.cell.x, current.cell.z))) {
                continue;
            }
            if (current.cell.equals(goal)) return unwind(current);
            for (int[] direction : DIRS) {
                Cell next = new Cell(current.cell.x + direction[0],
                        current.cell.z + direction[1]);
                if (admitted != null && !admitted.test(next)) continue;
                double step = direction[0] == 0 || direction[1] == 0
                        ? 1.0D : Math.sqrt(2.0D);
                double cost = current.g + step;
                long key = key(next.x, next.z);
                Node old = best.get(key);
                if (old != null && old.g <= cost) continue;
                Node node = new Node(next, cost,
                        cost + heuristic(next, goal), current);
                best.put(key, node);
                open.add(node);
            }
        }
        return List.of();
    }

    private static List<Cell> unwind(Node end) {
        List<Cell> result = new ArrayList<>();
        for (Node node = end; node != null; node = node.previous) {
            result.add(node.cell);
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private static Set<Long> dilate(List<Cell> path, int radius) {
        Set<Long> result = new HashSet<>();
        for (Cell cell : path) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    result.add(key(cell.x + dx, cell.z + dz));
                }
            }
        }
        return result;
    }

    private static double heuristic(Cell from, Cell to) {
        int dx = Math.abs(from.x - to.x);
        int dz = Math.abs(from.z - to.z);
        return Math.max(dx, dz) + (Math.sqrt(2.0D) - 1.0D)
                * Math.min(dx, dz);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Cell(int x, int z) { }
    private record Node(Cell cell, double g, double f, Node previous) { }
}
