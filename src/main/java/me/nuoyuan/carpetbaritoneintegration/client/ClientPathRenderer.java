package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.PathSnapshotPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps server path snapshots available on 26.2. Fabric's former world-render
 * callback was removed in this version; extraction-based rendering will be
 * connected separately without affecting command or path synchronization.
 */
public final class ClientPathRenderer {
    private static final Map<UUID, PathSnapshotPayload> SNAPSHOTS =
            new ConcurrentHashMap<>();

    private ClientPathRenderer() {
    }

    public static void accept(PathSnapshotPayload payload) {
        if (!payload.active()) {
            SNAPSHOTS.remove(payload.fakePlayerId());
        } else {
            SNAPSHOTS.compute(payload.fakePlayerId(), (id, previous) ->
                    previous == null
                            || payload.sequence() >= previous.sequence()
                            ? payload : previous);
        }
    }

    public static void clear() {
        SNAPSHOTS.clear();
    }
}
