package baritone.utils;

import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.pathing.movement.CalculationContext;

public class PathingCommandContext extends PathingCommand {
    public final CalculationContext desiredCalcContext;

    public PathingCommandContext(
            Goal goal, PathingCommandType type, CalculationContext context) {
        super(goal, type);
        desiredCalcContext = context;
    }
}
