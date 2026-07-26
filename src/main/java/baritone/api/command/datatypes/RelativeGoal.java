package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import java.util.stream.Stream;

public enum RelativeGoal implements IDatatypePost<Goal, BetterBlockPos> {
    INSTANCE;
    @Override public Goal apply(IDatatypeContext context, BetterBlockPos origin)
            throws CommandException {
        BetterBlockPos base = origin == null ? BetterBlockPos.ORIGIN : origin;
        if (!context.getConsumer().hasAny()) return new GoalBlock(base);
        if (context.getConsumer().hasExactly(3)) {
            return context.getConsumer().getDatatypePost(RelativeGoalBlock.INSTANCE, base);
        }
        if (context.getConsumer().hasExactly(2)) {
            return context.getConsumer().getDatatypePost(RelativeGoalXZ.INSTANCE, base);
        }
        return context.getConsumer().getDatatypePost(RelativeGoalYLevel.INSTANCE, base);
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) {
        return context.getConsumer().tabCompleteDatatype(RelativeCoordinate.INSTANCE);
    }
}
