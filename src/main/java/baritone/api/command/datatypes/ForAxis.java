package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import net.minecraft.core.Direction;
import java.util.Locale;
import java.util.stream.Stream;

public enum ForAxis implements IDatatypeFor<Direction.Axis> {
    INSTANCE;
    @Override public Direction.Axis get(IDatatypeContext context) throws CommandException {
        return Direction.Axis.valueOf(
                context.getConsumer().getString().toUpperCase(Locale.ROOT));
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        String prefix = context.getConsumer().getString().toLowerCase(Locale.ROOT);
        return Stream.of(Direction.Axis.values()).map(Direction.Axis::getName)
                .filter(name -> name.startsWith(prefix));
    }
}
