package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;

public interface IDatatypeFor<T> extends IDatatype {
    T get(IDatatypeContext context) throws CommandException;
}
