package baritone.command.argument;

import baritone.api.command.argument.ICommandArgument;
import java.util.ArrayList;
import java.util.List;

public final class CommandArguments {
    private CommandArguments() { }
    public static List<ICommandArgument> from(String input, boolean preserveEmptyLast) {
        List<ICommandArgument> result = new ArrayList<>();
        String source = input == null ? "" : input;
        int index = 0;
        int cursor = 0;
        while (cursor < source.length()) {
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
            if (cursor >= source.length()) break;
            int start = cursor;
            StringBuilder value = new StringBuilder();
            char quote = 0;
            if (source.charAt(cursor) == '"' || source.charAt(cursor) == '\'') {
                quote = source.charAt(cursor++);
            }
            boolean escaped = false;
            while (cursor < source.length()) {
                char character = source.charAt(cursor);
                if (escaped) {
                    value.append(character);
                    escaped = false;
                    cursor++;
                } else if (character == '\\' && quote != 0) {
                    escaped = true;
                    cursor++;
                } else if (quote != 0 && character == quote) {
                    cursor++;
                    quote = 0;
                    break;
                } else if (quote == 0 && Character.isWhitespace(character)) {
                    break;
                } else {
                    value.append(character);
                    cursor++;
                }
            }
            if (quote != 0) throw new IllegalArgumentException("Unclosed quoted argument");
            result.add(new CommandArgument(index++, value.toString(), source.substring(start)));
        }
        if (preserveEmptyLast && !source.isEmpty()
                && Character.isWhitespace(source.charAt(source.length() - 1))) {
            result.add(new CommandArgument(index, "", ""));
        }
        return result;
    }
}
