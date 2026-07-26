package baritone.api.command.exception;

public final class CommandTooManyArgumentsException extends CommandException {
    public CommandTooManyArgumentsException(int maximum) {
        super("参数过多，最多允许 " + maximum + " 个参数");
    }
}
