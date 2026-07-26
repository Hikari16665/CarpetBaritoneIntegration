package baritone.utils;

import baritone.Baritone;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Original process priority and control handoff semantics, server adapted. */
public final class PathingControlManager implements IPathingControlManager {
    private final Baritone baritone;
    private final Set<IBaritoneProcess> processes = new HashSet<>();
    private final List<IBaritoneProcess> active = new ArrayList<>();
    private IBaritoneProcess inControlLastTick;
    private IBaritoneProcess inControlThisTick;
    private PathingCommand command;

    public PathingControlManager(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override public void registerProcess(IBaritoneProcess process) {
        process.onLostControl();
        processes.add(process);
    }
    @Override public Optional<IBaritoneProcess> mostRecentInControl() {
        return Optional.ofNullable(inControlThisTick);
    }
    @Override public Optional<PathingCommand> mostRecentCommand() {
        return Optional.ofNullable(command);
    }

    public void tick(boolean calcFailed, boolean safeToCancel) {
        inControlLastTick = inControlThisTick;
        inControlThisTick = null;
        command = executeProcesses(calcFailed, safeToCancel);
        if (command != null) apply(command);
    }

    private PathingCommand executeProcesses(
            boolean calcFailed, boolean safeToCancel) {
        for (IBaritoneProcess process : processes) {
            if (process.isActive()) {
                if (!active.contains(process)) active.add(0, process);
            } else {
                active.remove(process);
            }
        }
        active.sort(Comparator.comparingDouble(
                IBaritoneProcess::priority).reversed());
        Iterator<IBaritoneProcess> iterator = active.iterator();
        while (iterator.hasNext()) {
            IBaritoneProcess process = iterator.next();
            PathingCommand next = process.onTick(
                    Objects.equals(process, inControlLastTick) && calcFailed,
                    safeToCancel);
            if (next == null) {
                if (process.isActive()) throw new IllegalStateException(
                        process.displayName() + " returned null while active");
                continue;
            }
            if (next.commandType == PathingCommandType.DEFER) continue;
            inControlThisTick = process;
            if (!process.isTemporary()) {
                iterator.forEachRemaining(IBaritoneProcess::onLostControl);
            }
            return next;
        }
        return null;
    }

    private void apply(PathingCommand next) {
        Goal goal = next.goal;
        switch (next.commandType) {
            case REQUEST_PAUSE -> baritone.pausePath();
            case SET_GOAL_AND_PAUSE -> {
                baritone.setActiveGoal(goal);
                baritone.pausePath();
            }
            case CANCEL_AND_SET_GOAL -> {
                baritone.pausePath();
                baritone.setActiveGoal(goal);
            }
            case SET_GOAL_AND_PATH -> {
                if (goal != null && (!baritone.isPathing()
                        || !baritone.goalMatches(goal))) {
                    baritone.pathToGoal(goal, 2_000L, 8_000L);
                }
            }
            case REVALIDATE_GOAL_AND_PATH -> {
                if (goal != null) {
                    boolean revalidate =
                            baritone.shouldRevalidate(goal, false);
                    baritone.setActiveGoal(goal);
                    if (revalidate) {
                        if (baritone.getPathExecutor() != null) {
                            baritone.deferRecalculationForProcess(goal);
                        } else {
                            baritone.recalculateForProcess(goal);
                        }
                    }
                }
            }
            case FORCE_REVALIDATE_GOAL_AND_PATH -> {
                if (goal != null) {
                    boolean revalidate =
                            baritone.shouldRevalidate(goal, true);
                    baritone.setActiveGoal(goal);
                    if (revalidate) {
                        if (baritone.getPathExecutor() != null) {
                            baritone.deferRecalculationForProcess(goal);
                        } else {
                            baritone.recalculateForProcess(goal);
                        }
                    }
                }
            }
            case DEFER -> { }
        }
    }

    public void cancelEverything() {
        inControlLastTick = null;
        inControlThisTick = null;
        command = null;
        active.clear();
        processes.forEach(IBaritoneProcess::onLostControl);
    }
}
