package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

/** Pure-server adaptation of the original CustomGoalProcess state machine. */
public final class CustomGoalProcess implements ICustomGoalProcess {
    private final Baritone baritone;
    private Goal goal;
    private Goal mostRecentGoal;
    private State state = State.NONE;

    public CustomGoalProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void setGoal(Goal goal) {
        this.goal = goal;
        this.mostRecentGoal = goal;
        if (state == State.NONE) {
            state = State.GOAL_SET;
        } else if (state == State.EXECUTING) {
            state = State.PATH_REQUESTED;
        }
    }

    @Override
    public void path() {
        state = State.PATH_REQUESTED;
    }

    public void serverTick() {
        if (state == State.NONE || state == State.GOAL_SET) {
            return;
        }
        if (goal == null) {
            onLostControl();
            return;
        }
        if (goal.isInGoal(baritone.getPlayerContext().playerFeet())
                && goal.isInGoal(baritone.getPathingBehavior().pathStart())) {
            baritone.cancelPath();
            onLostControl();
            return;
        }
        if (state == State.PATH_REQUESTED) {
            state = State.EXECUTING;
            if (!baritone.pathToGoal(goal, 2_000L, 5_000L)) {
                onLostControl();
            }
        } else if (state == State.EXECUTING && baritone.getPathExecutor() == null) {
            if (!baritone.pathToGoal(goal, 5_000L, 15_000L)) {
                onLostControl();
            }
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (state == State.EXECUTING && calcFailed) {
            onLostControl();
            return new PathingCommand(
                    null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return switch (state) {
            case GOAL_SET -> new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            case PATH_REQUESTED -> new PathingCommand(
                    goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            case EXECUTING -> new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
            case NONE -> new PathingCommand(null, PathingCommandType.DEFER);
        };
    }

    @Override public Goal getGoal() { return goal; }
    @Override public Goal mostRecentGoal() { return mostRecentGoal; }
    @Override public boolean isActive() { return state != State.NONE; }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() { state = State.NONE; goal = null; }
    @Override public String displayName0() { return "Custom Goal " + goal; }

    private enum State {
        NONE,
        GOAL_SET,
        PATH_REQUESTED,
        EXECUTING
    }
}
