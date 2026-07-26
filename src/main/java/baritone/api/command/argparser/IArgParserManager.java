package baritone.api.command.argparser;

import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.registry.Registry;

public interface IArgParserManager {
    <T> IArgParser.Stateless<T> getParserStateless(Class<T> type);
    <T, S> IArgParser.Stated<T, S> getParserStated(Class<T> type, Class<S> stateType);
    <T> T parseStateless(Class<T> type, ICommandArgument argument)
            throws CommandInvalidTypeException;
    <T, S> T parseStated(Class<T> type, Class<S> stateType,
                         ICommandArgument argument, S state)
            throws CommandInvalidTypeException;
    Registry<IArgParser<?>> getRegistry();
}
