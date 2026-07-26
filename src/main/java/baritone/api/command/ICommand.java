package baritone.api.command;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.List;
import java.util.stream.Stream;

public interface ICommand {
    void execute(String label, IArgConsumer args) throws CommandException;
    Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException;
    String getShortDesc();
    List<String> getLongDesc();
    List<String> getNames();
    default boolean hiddenFromHelp() { return false; }
}
