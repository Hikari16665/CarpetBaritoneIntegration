package baritone.api.command.argument;

import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.exception.CommandNotEnoughArgumentsException;

import java.util.Deque;
import java.util.LinkedList;
import java.util.stream.Stream;
import baritone.api.command.datatypes.IDatatype;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.command.datatypes.IDatatypePost;

public interface IArgConsumer {
    LinkedList<ICommandArgument> getArgs();
    Deque<ICommandArgument> getConsumed();
    boolean has(int count);
    default boolean hasAny() { return has(1); }
    boolean hasAtMost(int count);
    default boolean hasAtMostOne() { return hasAtMost(1); }
    boolean hasExactly(int count);
    default boolean hasExactlyOne() { return hasExactly(1); }
    ICommandArgument peek(int index) throws CommandNotEnoughArgumentsException;
    default ICommandArgument peek() throws CommandNotEnoughArgumentsException { return peek(0); }
    default String peekString() throws CommandNotEnoughArgumentsException { return peek().getValue(); }
    default String peekString(int index) throws CommandNotEnoughArgumentsException {
        return peek(index).getValue();
    }
    default <T> T peekAs(Class<T> type, int index)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return peek(index).getAs(type);
    }
    default <T> T peekAs(Class<T> type)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return peekAs(type, 0);
    }
    default <T> T peekAsOrNull(Class<T> type) throws CommandNotEnoughArgumentsException {
        try { return peekAs(type); } catch (CommandInvalidTypeException exception) { return null; }
    }
    default <T> T peekAsOrDefault(Class<T> type, T fallback)
            throws CommandNotEnoughArgumentsException {
        T value = peekAsOrNull(type); return value == null ? fallback : value;
    }
    default <E extends Enum<E>> E peekEnum(Class<E> type)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return peek().getEnum(type);
    }
    default <E extends Enum<E>> E peekEnumOrNull(Class<E> type)
            throws CommandNotEnoughArgumentsException {
        try { return peekEnum(type); } catch (CommandInvalidTypeException exception) { return null; }
    }
    ICommandArgument get() throws CommandNotEnoughArgumentsException;
    default String getString() throws CommandNotEnoughArgumentsException { return get().getValue(); }
    default <T> T getAs(Class<T> type)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return get().getAs(type);
    }
    default <E extends Enum<E>> E getEnum(Class<E> type)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return get().getEnum(type);
    }
    default <T> T getAsOrNull(Class<T> type) throws CommandNotEnoughArgumentsException {
        try {
            T value = peek().getAs(type);
            get();
            return value;
        } catch (CommandInvalidTypeException exception) {
            return null;
        }
    }
    default <T> T getAsOrDefault(Class<T> type, T fallback)
            throws CommandNotEnoughArgumentsException {
        T value = getAsOrNull(type); return value == null ? fallback : value;
    }
    default <E extends Enum<E>> E getEnumOrNull(Class<E> type)
            throws CommandNotEnoughArgumentsException {
        try {
            E value = peek().getEnum(type);
            get();
            return value;
        } catch (CommandInvalidTypeException exception) {
            return null;
        }
    }
    default <E extends Enum<E>> E getEnumOrDefault(Class<E> type, E fallback)
            throws CommandNotEnoughArgumentsException {
        E value = getEnumOrNull(type); return value == null ? fallback : value;
    }
    String rawRest();
    void requireMin(int min) throws CommandNotEnoughArgumentsException;
    void requireMax(int max) throws CommandException;
    default void requireExactly(int count) throws CommandException {
        requireMin(count); requireMax(count);
    }
    boolean hasConsumed();
    ICommandArgument consumed();
    String consumedString();
    IArgConsumer copy();
    <T> T getDatatypeFor(IDatatypeFor<T> datatype)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException;
    <T, O> T getDatatypePost(IDatatypePost<T, O> datatype, O original)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException;
    default <T> T peekDatatype(IDatatypeFor<T> datatype)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return copy().getDatatypeFor(datatype);
    }
    default <T, O> T peekDatatype(IDatatypePost<T, O> datatype, O original)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        return copy().getDatatypePost(datatype, original);
    }
    default <T> T getDatatypeForOrNull(IDatatypeFor<T> datatype) {
        try { return getDatatypeFor(datatype); } catch (CommandException exception) { return null; }
    }
    default <T, O> T getDatatypePostOrNull(IDatatypePost<T, O> datatype, O original) {
        try { return getDatatypePost(datatype, original); } catch (CommandException exception) { return null; }
    }
    default <T, O> T peekDatatypePostOrNull(IDatatypePost<T, O> datatype, O original) {
        return copy().getDatatypePostOrNull(datatype, original);
    }
    Stream<String> tabCompleteDatatype(IDatatype datatype);
}
