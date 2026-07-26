package baritone.api.process;

import baritone.api.pathing.goals.Goal;

public interface ICustomGoalProcess extends IBaritoneProcess {
    void setGoal(Goal goal);
    void path();
    Goal getGoal();
    Goal mostRecentGoal();

    default void setGoalAndPath(Goal goal) {
        setGoal(goal);
        path();
    }
}
