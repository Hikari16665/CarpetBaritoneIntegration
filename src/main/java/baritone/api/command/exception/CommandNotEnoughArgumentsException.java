package baritone.api.command.exception;

public final class CommandNotEnoughArgumentsException extends CommandException {
    public CommandNotEnoughArgumentsException(int minimum) {
        super("参数不足，至少需要 " + minimum + " 个参数");
    }
}
