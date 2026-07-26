/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.process;

import baritone.api.pathing.goals.Goal;

import java.util.Objects;

public class PathingCommand {
    public final Goal goal;
    public final PathingCommandType commandType;

    public PathingCommand(Goal goal, PathingCommandType commandType) {
        this.goal = goal;
        this.commandType = Objects.requireNonNull(commandType, "commandType");
    }

    @Override
    public String toString() {
        return commandType + " " + goal;
    }
}
