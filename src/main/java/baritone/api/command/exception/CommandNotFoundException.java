package baritone.api.command.exception;

public class CommandNotFoundException extends CommandException {
    public CommandNotFoundException(String command) { super("未知命令: " + command); }
}
