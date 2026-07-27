package me.nuoyuan.carpetbaritoneintegration.network;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.server.ServerPathExecutor;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds and sends bounded path snapshots to nearby visible players. */
public final class ServerPathSync {
    private static final int SEND_INTERVAL_TICKS = 5;
    private static final int MAX_POINTS = 1024;

    private ServerPathSync() { }

    public static void tick(Baritone baritone) {
        ServerPlayer fake = baritone.getPlayerContext().player();
        long gameTime = fake.level().getGameTime();
        if (gameTime % SEND_INTERVAL_TICKS != 0) return;

        List<BlockPos> current = currentPath(baritone.getPathExecutor());
        List<BlockPos> next = path(baritone.getNextPathExecutor(), 0);
        CalculationPaths calculating = inProgressPaths(baritone);
        if (current.isEmpty()
                && baritone.getElytraProcess().isActive()) {
            current = simplify(baritone.getElytraProcess().getPath().stream()
                    .map(pos -> (BlockPos) pos).toList());
        }
        Goal goal = baritone.getActiveGoal();
        List<BlockPos> selections = selectionCorners(baritone);
        BlockPos goalPos = goal instanceof IGoalRenderPos positioned
                ? positioned.getGoalPos().immutable() : null;
        String process = baritone.getPathingControlManager()
                .mostRecentInControl()
                .map(value -> value.displayName())
                .orElse("");
        boolean active = !current.isEmpty() || !next.isEmpty()
                || !calculating.best().isEmpty()
                || !calculating.recent().isEmpty()
                || !selections.isEmpty()
                || goalPos != null
                || baritone.getPathingBehavior().getInProgress().isPresent()
                || !process.isEmpty();
        PathSnapshotPayload payload = new PathSnapshotPayload(
                fake.getUUID(),
                fake.getScoreboardName(),
                fake.level().dimension().location().toString(),
                process,
                active,
                current,
                next,
                calculating.best(),
                calculating.recent(),
                executorPositions(baritone.getPathExecutor(),
                        PositionKind.BREAK),
                executorPositions(baritone.getPathExecutor(),
                        PositionKind.PLACE),
                executorPositions(baritone.getPathExecutor(),
                        PositionKind.WALK_INTO),
                selections,
                goalPos,
                gameTime);
        int viewDistance = fake.getServer().getPlayerList()
                .getViewDistance();
        for (ServerPlayer viewer :
                fake.getServer().getPlayerList().getPlayers()) {
            if (isVisibleTo(fake, viewer, viewDistance)
                    && ServerPlayNetworking.canSend(
                    viewer, PathSnapshotPayload.TYPE)) {
                ServerPlayNetworking.send(viewer, payload);
            }
        }
    }

    static boolean isVisibleTo(
            ServerPlayer fake, ServerPlayer viewer,
            int viewDistanceChunks) {
        if (viewer == fake || viewer.level() != fake.level()) return false;
        double radius = Math.max(1, viewDistanceChunks) * 16.0D;
        double dx = viewer.getX() - fake.getX();
        double dz = viewer.getZ() - fake.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private static List<BlockPos> currentPath(
            ServerPathExecutor executor) {
        if (executor == null) return Collections.emptyList();
        return path(executor, Math.max(0, executor.getPosition() - 3));
    }

    private static CalculationPaths inProgressPaths(Baritone baritone) {
        try {
            return baritone.getPathingBehavior().getInProgress()
                    .map(finder -> new CalculationPaths(
                            finder.bestPathSoFar()
                                    .map(value -> simplify(value.positions()
                                            .stream()
                                            .map(pos -> (BlockPos) pos)
                                            .toList()))
                                    .orElse(Collections.emptyList()),
                            finder.pathToMostRecentNodeConsidered()
                                    .map(value -> simplify(value.positions()
                                            .stream()
                                            .map(pos -> (BlockPos) pos)
                                            .toList()))
                                    .orElse(Collections.emptyList())))
                    .orElseGet(CalculationPaths::empty);
        } catch (RuntimeException concurrentUpdate) {
            // The worker may publish a new best node while this bounded
            // visualization snapshot is being assembled. Skip one frame;
            // never interfere with path calculation.
            return CalculationPaths.empty();
        }
    }

    private static List<BlockPos> executorPositions(
            ServerPathExecutor executor, PositionKind kind) {
        if (executor == null) return Collections.emptyList();
        java.util.Set<BlockPos> positions = switch (kind) {
            case BREAK -> executor.toBreak();
            case PLACE -> executor.toPlace();
            case WALK_INTO -> executor.toWalkInto();
        };
        return positions.stream()
                .limit(MAX_POINTS)
                .map(BlockPos::immutable)
                .toList();
    }

    private static List<BlockPos> selectionCorners(Baritone baritone) {
        List<BlockPos> corners = new ArrayList<>();
        for (baritone.api.selection.ISelection selection
                : baritone.getSelectionManager().getSelections()) {
            if (corners.size() + 2 > MAX_POINTS) break;
            corners.add(selection.min().immutable());
            corners.add(selection.max().immutable());
        }
        return List.copyOf(corners);
    }

    private static List<BlockPos> path(
            ServerPathExecutor executor, int start) {
        if (executor == null || executor.getPath() == null) {
            return Collections.emptyList();
        }
        List<BetterBlockPos> positions =
                executor.getPath().positions();
        if (start >= positions.size()) return Collections.emptyList();
        return simplify(positions.subList(start, positions.size()).stream()
                .map(pos -> (BlockPos) pos).toList());
    }

    static List<BlockPos> simplify(List<BlockPos> input) {
        if (input.size() <= 2) return List.copyOf(input);
        List<BlockPos> result = new ArrayList<>();
        result.add(input.getFirst().immutable());
        int previousDx = Integer.MIN_VALUE;
        int previousDy = Integer.MIN_VALUE;
        int previousDz = Integer.MIN_VALUE;
        for (int index = 1; index < input.size(); index++) {
            BlockPos previous = input.get(index - 1);
            BlockPos current = input.get(index);
            int dx = Integer.compare(current.getX(), previous.getX());
            int dy = Integer.compare(current.getY(), previous.getY());
            int dz = Integer.compare(current.getZ(), previous.getZ());
            if (index > 1 && (dx != previousDx
                    || dy != previousDy || dz != previousDz)) {
                result.add(previous.immutable());
                if (result.size() >= MAX_POINTS - 1) break;
            }
            previousDx = dx;
            previousDy = dy;
            previousDz = dz;
        }
        BlockPos last = input.getLast().immutable();
        if (!result.getLast().equals(last)
                && result.size() < MAX_POINTS) result.add(last);
        return List.copyOf(result);
    }

    private enum PositionKind {
        BREAK, PLACE, WALK_INTO
    }

    private record CalculationPaths(
            List<BlockPos> best, List<BlockPos> recent) {
        private static CalculationPaths empty() {
            return new CalculationPaths(
                    Collections.emptyList(), Collections.emptyList());
        }
    }
}
