/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.api.utils.IPlayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Live server context for a real or Carpet-created {@link ServerPlayer}.
 */
public final class ServerPlayerContext implements IPlayerContext {

    private static final double ENTITY_QUERY_RADIUS = 64.0D;
    private static final double INTERACTION_REACH = 5.0D;

    private final MinecraftServer server;
    private final Supplier<? extends ServerPlayer> playerSupplier;

    public ServerPlayerContext(MinecraftServer server, ServerPlayer player) {
        this(server, () -> player);
    }

    public ServerPlayerContext(MinecraftServer server, Supplier<? extends ServerPlayer> playerSupplier) {
        this.server = Objects.requireNonNull(server, "server");
        this.playerSupplier = Objects.requireNonNull(playerSupplier, "playerSupplier");
    }

    @Override
    public MinecraftServer server() {
        return server;
    }

    @Override
    public ServerPlayer player() {
        return Objects.requireNonNull(playerSupplier.get(), "The controlled server player is no longer available");
    }

    @Override
    public ServerLevel world() {
        if (player().level() instanceof ServerLevel level) {
            return level;
        }
        throw new IllegalStateException("The controlled player is not in a server level");
    }

    @Override
    public Iterable<Entity> entities() {
        ServerPlayer player = player();
        List<Entity> nearby = world().getEntities(
                player,
                player.getBoundingBox().inflate(ENTITY_QUERY_RADIUS),
                entity -> entity.isAlive() && entity != player
        );
        return nearby;
    }

    @Override
    public HitResult objectMouseOver() {
        return player().pick(INTERACTION_REACH, 1.0F, false);
    }
}
