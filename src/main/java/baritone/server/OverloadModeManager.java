package baritone.server;

import baritone.Baritone;
import carpet.patches.EntityPlayerMPFake;
import me.nuoyuan.carpetbaritoneintegration.mixin.FoodDataAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative, deliberately non-persistent global OVERLOAD MODE.
 * A restart always returns the server to the fair, ordinary execution mode.
 */
public final class OverloadModeManager {
    public static final OverloadModeManager INSTANCE =
            new OverloadModeManager();

    private final Map<UUID, ProtectionSnapshot> protectedPlayers =
            new HashMap<>();
    private MinecraftServer server;
    private boolean enabled;

    private OverloadModeManager() { }

    public boolean isEnabled(MinecraftServer candidate) {
        return enabled && server == candidate;
    }

    public boolean isEnabled(ServerPlayer player) {
        return player != null && isEnabled(player.level().getServer());
    }

    public boolean isEnabled(Baritone baritone) {
        return baritone != null
                && isEnabled(baritone.getPlayerContext().player());
    }

    public boolean canManage(ServerPlayer player) {
        return player != null && player.permissions()
                .hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public boolean setEnabled(
            MinecraftServer candidate, ServerPlayer actor,
            boolean requested) {
        if (!canManage(actor)) return false;
        server = candidate;
        if (enabled == requested) return true;
        enabled = requested;
        if (enabled) tick(candidate);
        else restoreAll(candidate);
        System.out.println("[CBI-OVERLOAD] actor="
                + actor.getScoreboardName() + " enabled=" + enabled
                + " fakePlayers=" + fakePlayerCount(candidate));
        return true;
    }

    public void tick(MinecraftServer candidate) {
        if (!isEnabled(candidate)) return;
        for (ServerPlayer player
                : candidate.getPlayerList().getPlayers()) {
            if (!(player instanceof EntityPlayerMPFake)) continue;
            protect(player);
        }
        protectedPlayers.keySet().removeIf(uuid -> {
            ServerPlayer player = candidate.getPlayerList().getPlayer(uuid);
            return player == null || player.hasDisconnected();
        });
    }

    public void clear(MinecraftServer candidate) {
        restoreAll(candidate);
        enabled = false;
        server = null;
    }

    private void protect(ServerPlayer player) {
        ProtectionSnapshot snapshot = protectedPlayers.computeIfAbsent(
                player.getUUID(), ignored -> new ProtectionSnapshot(
                        player.isInvulnerable(), player.getHealth(),
                        player.getFoodData().getFoodLevel(),
                        player.getFoodData().getSaturationLevel()));
        player.setInvulnerable(true);
        if (player.getHealth() < snapshot.healthFloor) {
            player.setHealth(snapshot.healthFloor);
        } else {
            snapshot.healthFloor = player.getHealth();
        }
        int food = player.getFoodData().getFoodLevel();
        if (food < snapshot.foodFloor) {
            player.getFoodData().setFoodLevel(snapshot.foodFloor);
        } else {
            snapshot.foodFloor = food;
        }
        float saturation = player.getFoodData().getSaturationLevel();
        if (saturation < snapshot.saturationFloor) {
            player.getFoodData().setSaturation(snapshot.saturationFloor);
        } else {
            snapshot.saturationFloor = saturation;
        }
        // Do not leave a hidden exhaustion debt to be charged immediately
        // after OVERLOAD MODE is disabled.
        ((FoodDataAccessor) player.getFoodData())
                .cbi$setExhaustionLevel(0.0F);
    }

    private void restoreAll(MinecraftServer candidate) {
        if (candidate != null) {
            protectedPlayers.forEach((uuid, snapshot) -> {
                ServerPlayer player = candidate.getPlayerList()
                        .getPlayer(uuid);
                if (player != null) {
                    player.setInvulnerable(snapshot.originalInvulnerable);
                }
            });
        }
        protectedPlayers.clear();
    }

    private static long fakePlayerCount(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(EntityPlayerMPFake.class::isInstance).count();
    }

    private static final class ProtectionSnapshot {
        private final boolean originalInvulnerable;
        private float healthFloor;
        private int foodFloor;
        private float saturationFloor;

        private ProtectionSnapshot(
                boolean originalInvulnerable, float healthFloor,
                int foodFloor, float saturationFloor) {
            this.originalInvulnerable = originalInvulnerable;
            this.healthFloor = healthFloor;
            this.foodFloor = foodFloor;
            this.saturationFloor = saturationFloor;
        }
    }
}
