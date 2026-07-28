package me.nuoyuan.carpetbaritoneintegration.network;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalInverted;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.server.ServerPathExecutor;
import baritone.process.BuilderProcess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;

/** Builds and sends bounded path snapshots to nearby visible players. */
public final class ServerPathSync {
    private static final int SEND_INTERVAL_TICKS = 5;
    private static final int HEARTBEAT_INTERVAL_TICKS = 40;
    private static final int MAX_POINTS = 1024;
    private static final Map<Baritone, SyncState> SYNC_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        List<PathSnapshotPayload.GoalRender> goalRenders =
                goalRenders(goal, fake);
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
                || !goalRenders.isEmpty()
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
                mergePositions(
                        executorPositions(baritone.getPathExecutor(),
                                PositionKind.BREAK),
                        baritone.getBuilderProcess()
                                .renderTargets(false, MAX_POINTS)),
                mergePositions(
                        executorPositions(baritone.getPathExecutor(),
                                PositionKind.PLACE),
                        baritone.getBuilderProcess()
                                .renderTargets(true, MAX_POINTS)),
                executorPositions(baritone.getPathExecutor(),
                        PositionKind.WALK_INTO),
                selections,
                renderSettings(),
                goalRenders,
                goalPos,
                gameTime);
        int contentHash = contentHash(payload);
        SyncState syncState;
        synchronized (SYNC_STATES) {
            syncState = SYNC_STATES.computeIfAbsent(
                    baritone, ignored -> new SyncState());
        }
        int viewDistance = fake.getServer().getPlayerList()
                .getViewDistance();
        Set<UUID> currentlyVisible = new HashSet<>();
        for (ServerPlayer viewer :
                fake.getServer().getPlayerList().getPlayers()) {
            if (isVisibleTo(fake, viewer, viewDistance)
                    && ServerPlayNetworking.canSend(
                    viewer, PathSnapshotPayload.TYPE)) {
                UUID viewerId = viewer.getUUID();
                currentlyVisible.add(viewerId);
                if (syncState.shouldSend(
                        viewerId, contentHash, gameTime)) {
                    ServerPlayNetworking.send(viewer, payload);
                }
            }
        }
        syncState.retain(currentlyVisible);
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

    private static List<BlockPos> mergePositions(
            List<BlockPos> first, List<BlockPos> second) {
        if (first.isEmpty()) {
            return second.size() <= MAX_POINTS
                    ? second : second.subList(0, MAX_POINTS);
        }
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        merged.addAll(first);
        merged.addAll(second);
        return merged.stream().limit(MAX_POINTS).toList();
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

    private static PathSnapshotPayload.RenderSettings renderSettings() {
        Settings settings = Baritone.settings();
        return new PathSnapshotPayload.RenderSettings(
                settings.renderPath.value,
                settings.renderPathAsLine.value,
                settings.renderGoal.value,
                settings.renderSelectionBoxes.value,
                settings.renderSelection.value,
                settings.fadePath.value,
                settings.yLevelBoxSize.value,
                settings.colorCurrentPath.value.getRGB(),
                settings.colorNextPath.value.getRGB(),
                settings.colorBestPathSoFar.value.getRGB(),
                settings.colorMostRecentConsidered.value.getRGB(),
                settings.colorBlocksToBreak.value.getRGB(),
                settings.colorBlocksToPlace.value.getRGB(),
                settings.colorBlocksToWalkInto.value.getRGB(),
                settings.colorGoalBox.value.getRGB(),
                settings.colorInvertedGoalBox.value.getRGB(),
                settings.colorSelection.value.getRGB());
    }

    private static List<PathSnapshotPayload.GoalRender> goalRenders(
            Goal goal, ServerPlayer fake) {
        List<PathSnapshotPayload.GoalRender> result = new ArrayList<>();
        appendGoal(goal, false, fake, result);
        return List.copyOf(result);
    }

    private static void appendGoal(
            Goal goal, boolean inverted, ServerPlayer fake,
            List<PathSnapshotPayload.GoalRender> result) {
        if (goal == null || result.size() >= 256) return;
        if (goal instanceof GoalInverted value) {
            appendGoal(value.origin, !inverted, fake, result);
            return;
        }
        if (goal instanceof GoalComposite value) {
            for (Goal child : value.goals()) {
                appendGoal(child, inverted, fake, result);
                if (result.size() >= 256) break;
            }
            return;
        }
        if (goal instanceof BuilderProcess.JankyGoalComposite value) {
            appendGoal(value.primary(), inverted, fake, result);
            appendGoal(value.fallback(), inverted, fake, result);
            return;
        }
        if (goal instanceof GoalXZ value) {
            result.add(new PathSnapshotPayload.GoalRender(
                    PathSnapshotPayload.GoalKind.XZ_COLUMN,
                    new BlockPos(value.getX(),
                            fake.level().getMinY(), value.getZ()),
                    inverted));
            return;
        }
        if (goal instanceof GoalYLevel value) {
            result.add(new PathSnapshotPayload.GoalRender(
                    PathSnapshotPayload.GoalKind.Y_LEVEL,
                    new BlockPos(fake.getBlockX(), value.level,
                            fake.getBlockZ()),
                    inverted));
            return;
        }
        if (goal instanceof IGoalRenderPos value) {
            PathSnapshotPayload.GoalKind kind =
                    goal instanceof GoalGetToBlock
                            || goal instanceof GoalTwoBlocks
                            ? PathSnapshotPayload.GoalKind.BLOCK_ONE_HIGH
                            : PathSnapshotPayload.GoalKind.BLOCK_TWO_HIGH;
            result.add(new PathSnapshotPayload.GoalRender(
                    kind, value.getGoalPos().immutable(), inverted));
        }
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

    private static int contentHash(PathSnapshotPayload payload) {
        return java.util.Objects.hash(
                payload.fakePlayerId(), payload.dimension(),
                payload.process(), payload.active(),
                payload.currentPath(), payload.nextPath(),
                payload.bestPathSoFar(),
                payload.mostRecentConsidered(),
                payload.blocksToBreak(), payload.blocksToPlace(),
                payload.blocksToWalkInto(),
                payload.selectionCorners(), payload.renderSettings(),
                payload.goals(),
                payload.goal());
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

    private static final class SyncState {
        private final Map<UUID, Integer> hashes = new HashMap<>();
        private final Map<UUID, Long> lastSent = new HashMap<>();

        private synchronized boolean shouldSend(
                UUID viewer, int hash, long gameTime) {
            Integer previous = hashes.get(viewer);
            long last = lastSent.getOrDefault(viewer, Long.MIN_VALUE / 2);
            if (previous != null && previous == hash
                    && gameTime - last < HEARTBEAT_INTERVAL_TICKS) {
                return false;
            }
            hashes.put(viewer, hash);
            lastSent.put(viewer, gameTime);
            return true;
        }

        private synchronized void retain(Set<UUID> viewers) {
            hashes.keySet().retainAll(viewers);
            lastSent.keySet().retainAll(viewers);
        }
    }
}
