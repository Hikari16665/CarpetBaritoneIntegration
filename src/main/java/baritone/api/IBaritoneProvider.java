package baritone.api;

import baritone.api.cache.IWorldScanner;
import baritone.api.command.ICommandSystem;
import baritone.api.schematic.ISchematicSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

/** Dedicated-server form of the provider API. */
public interface IBaritoneProvider {
    IBaritone getPrimaryBaritone();
    List<IBaritone> getAllBaritones();
    IBaritone getBaritoneForPlayer(ServerPlayer player);
    IBaritone createBaritone(MinecraftServer server, ServerPlayer player);
    boolean destroyBaritone(IBaritone baritone);
    IWorldScanner getWorldScanner();
    ICommandSystem getCommandSystem();
    ISchematicSystem getSchematicSystem();
}
