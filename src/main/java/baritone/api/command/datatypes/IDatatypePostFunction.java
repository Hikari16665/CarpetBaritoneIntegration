package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;

@FunctionalInterface
public interface IDatatypePostFunction<T, O> {
    T apply(O original) throws CommandException;
}
