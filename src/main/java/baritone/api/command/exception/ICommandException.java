package baritone.api.command.exception;

import baritone.api.command.ICommand;
import baritone.api.command.argument.ICommandArgument;
import java.util.List;

public interface ICommandException {
    String getMessage();
    default void handle(ICommand command, List<ICommandArgument> args) { }
}
