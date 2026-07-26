package baritone.api.command.exception;

public class CommandUnhandledException extends CommandException {
    public CommandUnhandledException(Throwable cause) {
        super("执行命令时发生未处理异常: " + cause.getClass().getSimpleName(), cause);
    }
}
