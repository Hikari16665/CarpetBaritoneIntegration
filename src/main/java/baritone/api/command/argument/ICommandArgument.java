package baritone.api.command.argument;

import baritone.api.command.exception.CommandInvalidTypeException;

public interface ICommandArgument {
    int getIndex();
    String getValue();
    String getRawRest();
    <E extends Enum<E>> E getEnum(Class<E> type) throws CommandInvalidTypeException;
    <T> T getAs(Class<T> type) throws CommandInvalidTypeException;
    <T> boolean is(Class<T> type);
    <T, S> T getAs(Class<T> type, Class<S> stateType, S state)
            throws CommandInvalidTypeException;
    <T, S> boolean is(Class<T> type, Class<S> stateType, S state);
}
