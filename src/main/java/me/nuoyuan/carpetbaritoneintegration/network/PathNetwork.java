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
        PayloadTypeRegistry.playC2S().register(
                ControlOptionsRequestPayload.TYPE,
                ControlOptionsRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(
                ControlOptionsPayload.TYPE,
                ControlOptionsPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(
                CommandSubmitPayload.TYPE,
                CommandSubmitPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(
                CommandResultPayload.TYPE,
                CommandResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(
                OverloadStateRequestPayload.TYPE,
                OverloadStateRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(
                OverloadTogglePayload.TYPE,
                OverloadTogglePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(
                OverloadStatePayload.TYPE,
                OverloadStatePayload.STREAM_CODEC);
        registered = true;
    }
}
