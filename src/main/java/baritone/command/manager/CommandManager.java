package baritone.command.manager;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.manager.ICommandManager;
import baritone.api.command.registry.Registry;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import baritone.server.BasicGoalCommandHandler;
import baritone.command.defaults.DefaultCommands;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.util.Tuple;
import baritone.api.command.argument.ICommandArgument;

public final class CommandManager implements ICommandManager {
    private final Baritone baritone;
    private final Registry<ICommand> registry = new Registry<>();

    public CommandManager(Baritone baritone) {
        this.baritone = baritone;
        DefaultCommands.createAll(baritone).forEach(registry::register);
    }

    @Override public IBaritone getBaritone() { return baritone; }
    @Override public Registry<ICommand> getRegistry() { return registry; }
    @Override public ICommand getCommand(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return registry.stream().filter(command -> command.getNames().contains(normalized))
                .findFirst().orElse(null);
    }
    @Override public boolean execute(String input) {
        ServerPlayer player = baritone.getPlayerContext().player();
        return executeAs(player, player, input);
    }
    public boolean executeAs(ServerPlayer sender, ServerPlayer fakePlayer, String input) {
        CommandExecutionContext.install(new CommandExecutionContext(sender, fakePlayer));
        try {
            return executeInternal(input);
        } finally {
            CommandExecutionContext.clear();
        }
    }
    private boolean executeInternal(String input) {
        String trimmed = input == null ? "" : input.trim();
        String label = trimmed.isEmpty() ? "help" : trimmed.split("\\s+", 2)[0];
        ICommand command = getCommand(label);
        if (command == null) return false;
        String rest = trimmed.length() <= label.length() ? "" : trimmed.substring(label.length());
        try {
            command.execute(label, new ArgConsumer(baritone, CommandArguments.from(rest, false)));
        } catch (CommandException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        return true;
    }
    @Override public boolean execute(Tuple<String, List<ICommandArgument>> expanded) {
        ServerPlayer player = baritone.getPlayerContext().player();
        CommandExecutionContext.install(new CommandExecutionContext(player, player));
        try {
            ICommand command = getCommand(expanded.getA());
            if (command == null) return false;
            try {
                command.execute(expanded.getA(), new ArgConsumer(baritone, expanded.getB()));
                return true;
            } catch (CommandException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        } finally {
            CommandExecutionContext.clear();
        }
    }
    @Override public Stream<String> tabComplete(
            Tuple<String, List<ICommandArgument>> expanded) {
        ICommand command = getCommand(expanded.getA());
        if (command == null) return Stream.empty();
        try {
            return command.tabComplete(expanded.getA(),
                    new ArgConsumer(baritone, expanded.getB()));
        } catch (CommandException exception) {
            return Stream.empty();
        }
    }
    @Override public Stream<String> tabComplete(String prefix) {
        String input = prefix == null ? "" : prefix;
        int separator = input.indexOf(' ');
        if (separator < 0) {
            String normalized = input.toLowerCase(Locale.ROOT);
            return registry.stream().flatMap(command -> command.getNames().stream())
                    .distinct().filter(name -> name.startsWith(normalized));
        }
        String label = input.substring(0, separator);
        ICommand command = getCommand(label);
        if (command == null) return Stream.empty();
        try {
            return command.tabComplete(label,
                    new ArgConsumer(baritone,
                            CommandArguments.from(input.substring(separator + 1), true)));
        } catch (CommandException exception) {
            return Stream.empty();
        }
    }
}
