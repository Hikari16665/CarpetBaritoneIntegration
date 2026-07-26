package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public enum RelativeCoordinate implements IDatatypePost<Double, Double> {
    INSTANCE;
    private static final Pattern PATTERN = Pattern.compile(
            "^(~?)([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)?)([kKmM]?)$");
    @Override public Double apply(IDatatypeContext context, Double origin) throws CommandException {
        String value = context.getConsumer().getString();
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("invalid coordinate");
        double number = matcher.group(2).isEmpty() ? 0 : Double.parseDouble(matcher.group(2));
        String scale = matcher.group(3).toLowerCase(Locale.ROOT);
        if (scale.equals("k")) number *= 1_000;
        if (scale.equals("m")) number *= 1_000_000;
        return matcher.group(1).isEmpty() ? number : (origin == null ? 0 : origin) + number;
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        return context.getConsumer().hasAtMostOne()
                && context.getConsumer().getString().matches("^(~|)$")
                ? Stream.of("~") : Stream.empty();
    }
}
