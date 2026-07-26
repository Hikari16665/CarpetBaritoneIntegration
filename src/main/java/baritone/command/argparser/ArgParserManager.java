package baritone.command.argparser;

import baritone.api.command.argparser.IArgParser;
import baritone.api.command.argparser.IArgParserManager;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.exception.CommandNoParserForTypeException;
import baritone.api.command.registry.Registry;

public enum ArgParserManager implements IArgParserManager {
    INSTANCE;
    private final Registry<IArgParser<?>> registry = new Registry<>();
    ArgParserManager() { DefaultArgParsers.ALL.forEach(registry::register); }
    @Override @SuppressWarnings("unchecked")
    public <T> IArgParser.Stateless<T> getParserStateless(Class<T> type) {
        return (IArgParser.Stateless<T>) registry.stream()
                .filter(IArgParser.Stateless.class::isInstance)
                .map(IArgParser.Stateless.class::cast)
                .filter(parser -> wrap(parser.getTarget()).isAssignableFrom(wrap(type)))
                .findFirst().orElse(null);
    }
    @Override @SuppressWarnings("unchecked")
    public <T, S> IArgParser.Stated<T, S> getParserStated(Class<T> type, Class<S> stateType) {
        return (IArgParser.Stated<T, S>) registry.stream()
                .filter(IArgParser.Stated.class::isInstance)
                .map(IArgParser.Stated.class::cast)
                .filter(parser -> wrap(parser.getTarget()).isAssignableFrom(wrap(type)))
                .filter(parser -> parser.getStateType().isAssignableFrom(stateType))
                .findFirst().orElse(null);
    }
    @Override public <T> T parseStateless(Class<T> type, ICommandArgument argument)
            throws CommandInvalidTypeException {
        IArgParser.Stateless<T> parser = getParserStateless(type);
        if (parser == null) throw new CommandNoParserForTypeException(type);
        try { return parser.parseArg(argument); }
        catch (Exception exception) {
            throw new CommandInvalidTypeException(argument.getValue(), type, exception);
        }
    }
    @Override public <T, S> T parseStated(
            Class<T> type, Class<S> stateType, ICommandArgument argument, S state)
            throws CommandInvalidTypeException {
        IArgParser.Stated<T, S> parser = getParserStated(type, stateType);
        if (parser == null) throw new CommandNoParserForTypeException(type);
        try { return parser.parseArg(argument, state); }
        catch (Exception exception) {
            throw new CommandInvalidTypeException(argument.getValue(), type, exception);
        }
    }
    @Override public Registry<IArgParser<?>> getRegistry() { return registry; }
    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }
}
