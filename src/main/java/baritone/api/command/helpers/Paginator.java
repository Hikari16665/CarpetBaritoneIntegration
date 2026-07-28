package baritone.api.command.helpers;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.command.manager.CommandExecutionContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public final class Paginator<E> {
    public final List<E> entries;
    public int pageSize = 8;
    public int page = 1;
    public Paginator(List<E> entries) { this.entries = List.copyOf(entries); }
    @SafeVarargs public Paginator(E... entries) { this(Arrays.asList(entries)); }
    public Paginator<E> setPageSize(int pageSize) {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        this.pageSize = pageSize;
        return this;
    }
    public int getMaxPage() {
        return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
    }
    public boolean validPage(int page) { return page >= 1 && page <= getMaxPage(); }
    public Paginator<E> skipPages(int pages) {
        page = Math.max(1, Math.min(getMaxPage(), page + pages));
        return this;
    }
    public void display(Function<E, Component> transform, String commandPrefix) {
        CommandExecutionContext execution = CommandExecutionContext.current();
        tell(execution, "第 " + page + "/" + getMaxPage() + " 页");
        int from = Math.min(entries.size(), (page - 1) * pageSize);
        int to = Math.min(entries.size(), from + pageSize);
        for (int index = from; index < to; index++) {
            tell(execution, transform.apply(entries.get(index)).getString());
        }
    }
    public void display(Function<E, Component> transform) { display(transform, ""); }

    public static <T> void paginate(
            IArgConsumer consumer, Paginator<T> paginator, Runnable pre,
            Function<T, Component> transform, String commandPrefix) throws CommandException {
        int requested = consumer.hasAny() ? consumer.getAs(Integer.class) : 1;
        consumer.requireMax(0);
        if (!paginator.validPage(requested)) {
            throw new IllegalArgumentException("页码超出范围: " + requested);
        }
        paginator.page = requested;
        pre.run();
        paginator.display(transform, commandPrefix);
    }
    public static <T> void paginate(
            IArgConsumer consumer, List<T> entries, Runnable pre,
            Function<T, Component> transform, String commandPrefix) throws CommandException {
        paginate(consumer, new Paginator<>(entries), pre, transform, commandPrefix);
    }
    public static <T> void paginate(
            IArgConsumer consumer, T[] entries, Runnable pre,
            Function<T, Component> transform, String commandPrefix) throws CommandException {
        paginate(consumer, Arrays.asList(entries), pre, transform, commandPrefix);
    }
    public static <T> void paginate(
            IArgConsumer consumer, Paginator<T> paginator,
            Function<T, Component> transform, String commandPrefix) throws CommandException {
        paginate(consumer, paginator, () -> { }, transform, commandPrefix);
    }
    public static <T> void paginate(
            IArgConsumer consumer, List<T> entries,
            Function<T, Component> transform, String commandPrefix) throws CommandException {
        paginate(consumer, entries, () -> { }, transform, commandPrefix);
    }
    public static <T> void paginate(
            IArgConsumer consumer, T[] entries,
            Function<T, Component> transform, String commandPrefix) throws CommandException {
        paginate(consumer, entries, () -> { }, transform, commandPrefix);
    }
    public static <T> void paginate(
            IArgConsumer consumer, Paginator<T> paginator, Runnable pre,
            Function<T, Component> transform) throws CommandException {
        paginate(consumer, paginator, pre, transform, "");
    }
    public static <T> void paginate(
            IArgConsumer consumer, List<T> entries, Runnable pre,
            Function<T, Component> transform) throws CommandException {
        paginate(consumer, entries, pre, transform, "");
    }
    public static <T> void paginate(
            IArgConsumer consumer, T[] entries, Runnable pre,
            Function<T, Component> transform) throws CommandException {
        paginate(consumer, entries, pre, transform, "");
    }
    public static <T> void paginate(
            IArgConsumer consumer, Paginator<T> paginator,
            Function<T, Component> transform) throws CommandException {
        paginate(consumer, paginator, transform, "");
    }
    public static <T> void paginate(
            IArgConsumer consumer, List<T> entries,
            Function<T, Component> transform) throws CommandException {
        paginate(consumer, entries, transform, "");
    }
    public static <T> void paginate(
            IArgConsumer consumer, T[] entries,
            Function<T, Component> transform) throws CommandException {
        paginate(consumer, entries, transform, "");
    }

    private static void tell(CommandExecutionContext execution, String text) {
        String command = "tell "
                + StringArgumentType.escapeIfRequired(
                        execution.sender().getScoreboardName())
                + " " + StringArgumentType.escapeIfRequired("[Baritone] " + text);
        execution.fakePlayer().level().getServer().getCommands().performPrefixedCommand(
                execution.fakePlayer().createCommandSourceStack(), command);
    }
}
