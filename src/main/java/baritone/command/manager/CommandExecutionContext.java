package baritone.command.manager;

import net.minecraft.server.level.ServerPlayer;

public record CommandExecutionContext(ServerPlayer sender, ServerPlayer fakePlayer) {
    private static final ThreadLocal<CommandExecutionContext> CURRENT = new ThreadLocal<>();
    public static CommandExecutionContext current() {
        CommandExecutionContext context = CURRENT.get();
        if (context == null) throw new IllegalStateException("No server command execution context");
        return context;
    }
    static void install(CommandExecutionContext context) { CURRENT.set(context); }
    static void clear() { CURRENT.remove(); }
}
