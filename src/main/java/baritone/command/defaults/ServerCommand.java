package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.command.manager.CommandExecutionContext;
import baritone.server.BasicGoalCommandHandler;

import java.util.List;
import java.util.stream.Stream;

abstract class ServerCommand extends Command {
    private final String description;
    protected ServerCommand(IBaritone baritone, String description, String... names) {
        super(baritone, names);
        this.description = description;
    }
    @Override public final void execute(String label, IArgConsumer args) {
        CommandExecutionContext execution = CommandExecutionContext.current();
        BasicGoalCommandHandler.executeCommand(
                execution.sender(), execution.fakePlayer(), label, args);
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }
    @Override public String getShortDesc() { return description; }
    @Override public List<String> getLongDesc() { return List.of(description); }
}
