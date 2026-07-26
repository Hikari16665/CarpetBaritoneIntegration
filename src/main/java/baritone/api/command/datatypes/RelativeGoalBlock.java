package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import java.util.stream.Stream;

public enum RelativeGoalBlock implements IDatatypePost<GoalBlock, BetterBlockPos> {
    INSTANCE;
    @Override public GoalBlock apply(IDatatypeContext context, BetterBlockPos origin)
            throws CommandException {
        return new GoalBlock(RelativeBlockPos.INSTANCE.apply(context, origin));
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) {
        return context.getConsumer().tabCompleteDatatype(RelativeCoordinate.INSTANCE);
    }
}
