package baritone.api.command.exception;

public class CommandException extends Exception implements ICommandException {
    public CommandException(String message) { super(message); }
    public CommandException(String message, Throwable cause) { super(message, cause); }
}
