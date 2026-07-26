package baritone.server;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.cache.IWorldScanner;
import baritone.api.command.ICommandSystem;
import baritone.api.schematic.ISchematicSystem;
import baritone.cache.WorldScanner;
import baritone.command.CommandSystem;
import baritone.utils.schematic.SchematicSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

public final class ServerBaritoneProvider implements IBaritoneProvider {
    private final ServerBaritoneRegistry registry;
    public ServerBaritoneProvider(ServerBaritoneRegistry registry) { this.registry = registry; }
    @Override public IBaritone getPrimaryBaritone() {
        return registry.snapshot().stream().findFirst().orElse(null);
    }
    @Override public List<IBaritone> getAllBaritones() {
        return List.copyOf(registry.snapshot());
    }
    @Override public IBaritone getBaritoneForPlayer(ServerPlayer player) {
        return registry.get(player);
    }
    @Override public IBaritone createBaritone(MinecraftServer server, ServerPlayer player) {
        return registry.getOrCreate(server, player);
    }
    @Override public boolean destroyBaritone(IBaritone baritone) {
        if (!(baritone instanceof Baritone implementation)) return false;
        return registry.remove(implementation);
    }
    @Override public IWorldScanner getWorldScanner() { return WorldScanner.INSTANCE; }
    @Override public ICommandSystem getCommandSystem() { return CommandSystem.INSTANCE; }
    @Override public ISchematicSystem getSchematicSystem() { return SchematicSystem.INSTANCE; }
}
