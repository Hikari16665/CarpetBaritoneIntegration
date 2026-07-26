package baritone.api.command.helpers;

import baritone.api.command.manager.ICommandManager;
import net.minecraft.resources.ResourceLocation;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class TabCompleteHelper {
    private Stream<String> values;
    public TabCompleteHelper() { values = Stream.empty(); }
    public TabCompleteHelper(String[] base) { values = Stream.of(base); }
    public TabCompleteHelper(List<String> base) { values = base.stream(); }
    public TabCompleteHelper append(Stream<String> source) {
        values = Stream.concat(values, source); return this;
    }
    public TabCompleteHelper append(String... source) { return append(Stream.of(source)); }
    public TabCompleteHelper append(Class<? extends Enum<?>> type) {
        return append(Stream.of(type.getEnumConstants())
                .map(value -> value.name().toLowerCase(Locale.ROOT)));
    }
    public TabCompleteHelper prepend(Stream<String> source) {
        values = Stream.concat(source, values); return this;
    }
    public TabCompleteHelper prepend(String... source) { return prepend(Stream.of(source)); }
    public TabCompleteHelper prepend(Class<? extends Enum<?>> type) {
        return prepend(Stream.of(type.getEnumConstants())
                .map(value -> value.name().toLowerCase(Locale.ROOT)));
    }
    public TabCompleteHelper map(Function<String, String> mapper) {
        values = values.map(mapper); return this;
    }
    public TabCompleteHelper filter(Predicate<String> filter) {
        values = values.filter(filter); return this;
    }
    public TabCompleteHelper sort(Comparator<String> comparator) {
        values = values.sorted(comparator); return this;
    }
    public TabCompleteHelper sortAlphabetically() { return sort(String.CASE_INSENSITIVE_ORDER); }
    public TabCompleteHelper filterPrefix(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized));
    }
    public TabCompleteHelper filterPrefixNamespaced(String prefix) {
        ResourceLocation location = ResourceLocation.tryParse(prefix);
        return location == null ? filter(ignored -> false) : filterPrefix(location.toString());
    }
    public TabCompleteHelper addCommands(ICommandManager manager) {
        return append(manager.getRegistry().descendingStream()
                .flatMap(command -> command.getNames().stream()).distinct());
    }
    public String[] build() { return values.toArray(String[]::new); }
    public Stream<String> stream() { return values; }
}
