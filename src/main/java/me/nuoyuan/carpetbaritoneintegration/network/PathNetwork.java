package me.nuoyuan.carpetbaritoneintegration.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class PathNetwork {
    private static boolean registered;

    private PathNetwork() { }

    public static synchronized void registerCommon() {
        if (registered) return;
        PayloadTypeRegistry.playS2C().register(
                PathSnapshotPayload.TYPE,
                PathSnapshotPayload.STREAM_CODEC);
        registered = true;
    }
}
