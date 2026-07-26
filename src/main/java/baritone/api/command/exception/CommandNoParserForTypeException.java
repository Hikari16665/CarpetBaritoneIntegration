package baritone.api.command.exception;

public final class CommandNoParserForTypeException extends CommandInvalidTypeException {
    public CommandNoParserForTypeException(Class<?> type) {
        super("", type, new IllegalStateException("No parser registered"));
    }
}
