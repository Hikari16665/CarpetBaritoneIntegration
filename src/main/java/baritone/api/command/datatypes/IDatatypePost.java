package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;

public interface IDatatypePost<T, O> extends IDatatype {
    T apply(IDatatypeContext context, O original) throws CommandException;
}
