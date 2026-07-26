package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import java.util.stream.Stream;
import net.minecraft.util.Mth;

public enum RelativeGoalXZ implements IDatatypePost<GoalXZ, BetterBlockPos> {
    INSTANCE;
    @Override public GoalXZ apply(IDatatypeContext context, BetterBlockPos origin)
            throws CommandException {
        BetterBlockPos base = origin == null ? BetterBlockPos.ORIGIN : origin;
        return new GoalXZ(
                Mth.floor(context.getConsumer().getDatatypePost(
                        RelativeCoordinate.INSTANCE, (double) base.x)),
                Mth.floor(context.getConsumer().getDatatypePost(
                        RelativeCoordinate.INSTANCE, (double) base.z)));
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) {
        return context.getConsumer().tabCompleteDatatype(RelativeCoordinate.INSTANCE);
    }
}
