package baritone.api.command.exception;

public class CommandInvalidTypeException extends CommandException {
    public CommandInvalidTypeException(String value, Class<?> type, Throwable cause) {
        super("无法把参数 '" + value + "' 解析为 " + type.getSimpleName(), cause);
    }
}
