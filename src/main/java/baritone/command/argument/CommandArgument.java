package baritone.command.argument;

import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandInvalidTypeException;

import java.util.Locale;
import baritone.command.argparser.ArgParserManager;

public final class CommandArgument implements ICommandArgument {
    private final int index;
    private final String value;
    private final String rawRest;
    public CommandArgument(int index, String value, String rawRest) {
        this.index = index;
        this.value = value;
        this.rawRest = rawRest;
    }
    @Override public int getIndex() { return index; }
    @Override public String getValue() { return value; }
    @Override public String getRawRest() { return rawRest; }
    @Override public <E extends Enum<E>> E getEnum(Class<E> type) throws CommandInvalidTypeException {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException exception) {
            throw new CommandInvalidTypeException(value, type, exception);
        }
    }
    @Override @SuppressWarnings("unchecked")
    public <T> T getAs(Class<T> type) throws CommandInvalidTypeException {
        if (type.isEnum()) return (T) getEnum((Class<? extends Enum>) type);
        return ArgParserManager.INSTANCE.parseStateless(type, this);
    }
    @Override public <T> boolean is(Class<T> type) {
        try { getAs(type); return true; } catch (CommandInvalidTypeException ignored) { return false; }
    }
    @Override public <T, S> T getAs(Class<T> type, Class<S> stateType, S state)
            throws CommandInvalidTypeException {
        return ArgParserManager.INSTANCE.parseStated(type, stateType, this, state);
    }
    @Override public <T, S> boolean is(Class<T> type, Class<S> stateType, S state) {
        try { getAs(type, stateType, state); return true; }
        catch (CommandInvalidTypeException ignored) { return false; }
    }
}
