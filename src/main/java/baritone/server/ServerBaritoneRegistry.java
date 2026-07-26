/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.Baritone;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns the server-side Baritone instance associated with each controlled player. */
public final class ServerBaritoneRegistry {
    private final Map<UUID, Baritone> instances = new HashMap<>();
    private int tickCount;
    private int cacheMaintenanceCursor;
    private volatile long lastTickNanos;
    private volatile long maxTickNanos;
    private long overBudgetTicks;

    public Baritone getOrCreate(MinecraftServer server, ServerPlayer player) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        return instances.computeIfAbsent(
                player.getUUID(),
                ignored -> new Baritone(new ServerPlayerContext(server, player))
        );
    }

    public Baritone get(ServerPlayer player) {
        return instances.get(player.getUUID());
    }

    public void remove(ServerPlayer player) {
        Baritone removed = instances.remove(player.getUUID());
        if (removed != null) {
            removed.getGameEventHandler().onWorldEvent(new WorldEvent(
                    removed.getPlayerContext().world(), EventState.PRE));
            removed.cancelAll();
        }
    }

    public boolean remove(Baritone instance) {
        UUID key = instances.entrySet().stream()
                .filter(entry -> entry.getValue() == instance)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (key == null) return false;
        Baritone removed = instances.remove(key);
        removed.cancelAll();
        return true;
    }

    public java.util.List<Baritone> snapshot() {
        return java.util.List.copyOf(instances.values());
    }

    public void tick(MinecraftServer server) {
        long started = System.nanoTime();
        if (!instances.isEmpty()) {
            java.util.List<Baritone> maintenance =
                    java.util.List.copyOf(instances.values());
            maintenance.get(Math.floorMod(cacheMaintenanceCursor++,
                    maintenance.size())).tickCacheMaintenance();
        }
        Iterator<Map.Entry<UUID, Baritone>> iterator = instances.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Baritone> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected()) {
                entry.getValue().getGameEventHandler().onWorldEvent(new WorldEvent(
                        entry.getValue().getPlayerContext().world(), EventState.PRE));
                entry.getValue().cancelAll();
                iterator.remove();
                continue;
            }
            entry.getValue().tick(tickCount);
        }
        tickCount++;
        lastTickNanos = System.nanoTime() - started;
        maxTickNanos = Math.max(maxTickNanos, lastTickNanos);
        if (lastTickNanos > 50_000_000L) overBudgetTicks++;
    }

    public double lastTickMillis() {
        return lastTickNanos / 1_000_000.0D;
    }

    public double maxTickMillis() {
        return maxTickNanos / 1_000_000.0D;
    }

    public long overBudgetTicks() {
        return overBudgetTicks;
    }

    public void forEach(java.util.function.Consumer<Baritone> action) {
        instances.values().forEach(action);
    }

    public void clear() {
        baritone.cache.ServerWorldCache.saveAll();
        instances.values().forEach(Baritone::cancelAll);
        instances.clear();
    }
}
