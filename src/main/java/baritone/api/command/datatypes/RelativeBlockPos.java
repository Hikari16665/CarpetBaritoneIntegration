package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.utils.BetterBlockPos;
import java.util.stream.Stream;
import net.minecraft.util.Mth;

public enum RelativeBlockPos implements IDatatypePost<BetterBlockPos, BetterBlockPos> {
    INSTANCE;
    @Override public BetterBlockPos apply(IDatatypeContext context, BetterBlockPos origin)
            throws CommandException {
        BetterBlockPos base = origin == null ? BetterBlockPos.ORIGIN : origin;
        return new BetterBlockPos(
                Mth.floor(context.getConsumer().getDatatypePost(
                        RelativeCoordinate.INSTANCE, (double) base.x)),
                Mth.floor(context.getConsumer().getDatatypePost(
                        RelativeCoordinate.INSTANCE, (double) base.y)),
                Mth.floor(context.getConsumer().getDatatypePost(
                        RelativeCoordinate.INSTANCE, (double) base.z)));
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) {
        return context.getConsumer().tabCompleteDatatype(RelativeCoordinate.INSTANCE);
    }
}
