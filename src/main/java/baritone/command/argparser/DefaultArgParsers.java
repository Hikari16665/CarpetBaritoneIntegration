package baritone.command.argparser;

import baritone.api.command.argparser.IArgParser;
import baritone.api.command.argument.ICommandArgument;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DefaultArgParsers {
    private DefaultArgParsers() { }
    private record Parser<T>(Class<T> getTarget, ThrowingParser<T> parser)
            implements IArgParser.Stateless<T> {
        @Override public T parseArg(ICommandArgument argument) throws Exception {
            return parser.parse(argument.getValue());
        }
    }
    @FunctionalInterface private interface ThrowingParser<T> { T parse(String value) throws Exception; }
    private static final Set<String> TRUE = Set.of("1", "true", "yes", "t", "y", "on", "enable");
    private static final Set<String> FALSE = Set.of("0", "false", "no", "f", "n", "off", "disable");
    public static final List<IArgParser<?>> ALL = List.of(
            new Parser<>(Integer.class, Integer::valueOf),
            new Parser<>(Long.class, Long::valueOf),
            new Parser<>(Float.class, Float::valueOf),
            new Parser<>(Double.class, Double::valueOf),
            new Parser<>(Boolean.class, value -> {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (TRUE.contains(normalized)) return true;
                if (FALSE.contains(normalized)) return false;
                throw new IllegalArgumentException("invalid boolean");
            }),
            new Parser<>(String.class, value -> value)
    );
}
