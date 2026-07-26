package baritone.command.argument;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandNotEnoughArgumentsException;
import baritone.api.command.exception.CommandTooManyArgumentsException;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;
import baritone.api.IBaritone;
import baritone.api.command.datatypes.IDatatype;
import baritone.api.command.datatypes.IDatatypeContext;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.command.datatypes.IDatatypePost;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;

public final class ArgConsumer implements IArgConsumer {
    private final LinkedList<ICommandArgument> args;
    private final Deque<ICommandArgument> consumed;
    private final IBaritone baritone;
    public ArgConsumer(List<ICommandArgument> args) {
        this(null, new LinkedList<>(args), new LinkedList<>());
    }
    public ArgConsumer(IBaritone baritone, List<ICommandArgument> args) {
        this(baritone, new LinkedList<>(args), new LinkedList<>());
    }
    private ArgConsumer(IBaritone baritone, LinkedList<ICommandArgument> args, Deque<ICommandArgument> consumed) {
        this.baritone = baritone;
        this.args = new LinkedList<>(args);
        this.consumed = new LinkedList<>(consumed);
    }
    @Override public LinkedList<ICommandArgument> getArgs() { return args; }
    @Override public Deque<ICommandArgument> getConsumed() { return consumed; }
    @Override public boolean has(int count) { return args.size() >= count; }
    @Override public boolean hasAtMost(int count) { return args.size() <= count; }
    @Override public boolean hasExactly(int count) { return args.size() == count; }
    @Override public ICommandArgument peek(int index) throws CommandNotEnoughArgumentsException {
        requireMin(index + 1); return args.get(index);
    }
    @Override public ICommandArgument get() throws CommandNotEnoughArgumentsException {
        requireMin(1); ICommandArgument argument = args.removeFirst(); consumed.addLast(argument); return argument;
    }
    @Override public String rawRest() { return args.isEmpty() ? "" : args.getFirst().getRawRest(); }
    @Override public void requireMin(int min) throws CommandNotEnoughArgumentsException {
        if (args.size() < min) throw new CommandNotEnoughArgumentsException(min + consumed.size());
    }
    @Override public void requireMax(int max) throws CommandTooManyArgumentsException {
        if (args.size() > max) throw new CommandTooManyArgumentsException(max + consumed.size());
    }
    @Override public boolean hasConsumed() { return !consumed.isEmpty(); }
    @Override public ICommandArgument consumed() {
        return consumed.isEmpty() ? new CommandArgument(-1, "", "") : consumed.getLast();
    }
    @Override public String consumedString() { return consumed().getValue(); }
    @Override public IArgConsumer copy() { return new ArgConsumer(baritone, args, consumed); }
    private IDatatypeContext context() {
        return new IDatatypeContext() {
            @Override public IBaritone getBaritone() { return baritone; }
            @Override public IArgConsumer getConsumer() { return ArgConsumer.this; }
        };
    }
    @Override public <T> T getDatatypeFor(IDatatypeFor<T> datatype)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        try { return datatype.get(context()); }
        catch (CommandException exception) { throw new CommandInvalidTypeException(
                hasAny() ? peek().getValue() : consumedString(), datatype.getClass(), exception); }
    }
    @Override public <T, O> T getDatatypePost(IDatatypePost<T, O> datatype, O original)
            throws CommandInvalidTypeException, CommandNotEnoughArgumentsException {
        try { return datatype.apply(context(), original); }
        catch (CommandException exception) { throw new CommandInvalidTypeException(
                hasAny() ? peek().getValue() : consumedString(), datatype.getClass(), exception); }
    }
    @Override public Stream<String> tabCompleteDatatype(IDatatype datatype) {
        try { return datatype.tabComplete(context()); }
        catch (CommandException exception) { return Stream.empty(); }
    }
}
