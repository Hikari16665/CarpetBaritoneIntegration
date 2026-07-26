package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.utils.BetterBlockPos;
import java.util.stream.Stream;
import net.minecraft.util.Mth;

public enum RelativeGoalYLevel implements IDatatypePost<GoalYLevel, BetterBlockPos> {
    INSTANCE;
    @Override public GoalYLevel apply(IDatatypeContext context, BetterBlockPos origin)
            throws CommandException {
        BetterBlockPos base = origin == null ? BetterBlockPos.ORIGIN : origin;
        return new GoalYLevel(Mth.floor(context.getConsumer().getDatatypePost(
                RelativeCoordinate.INSTANCE, (double) base.y)));
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) {
        return context.getConsumer().tabCompleteDatatype(RelativeCoordinate.INSTANCE);
    }
}
