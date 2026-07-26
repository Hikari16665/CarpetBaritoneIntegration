package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import net.minecraft.core.Direction;
import java.util.Locale;
import java.util.stream.Stream;

public enum ForDirection implements IDatatypeFor<Direction> {
    INSTANCE;
    @Override public Direction get(IDatatypeContext context) throws CommandException {
        return Direction.valueOf(context.getConsumer().getString().toUpperCase(Locale.ROOT));
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        String prefix = context.getConsumer().getString().toLowerCase(Locale.ROOT);
        return Stream.of(Direction.values()).map(Direction::getName)
                .filter(name -> name.startsWith(prefix));
    }
}
