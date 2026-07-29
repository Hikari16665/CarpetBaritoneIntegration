package me.nuoyuan.carpetbaritoneintegration.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class PathNetwork {
    private static boolean registered;

    private PathNetwork() { }

    public static synchronized void registerCommon() {
        if (registered) return;
        PayloadTypeRegistry.clientboundPlay().register(
                PathSnapshotPayload.TYPE,
                PathSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ControlOptionsRequestPayload.TYPE,
                ControlOptionsRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                ControlOptionsPayload.TYPE,
                ControlOptionsPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                CommandSubmitPayload.TYPE,
                CommandSubmitPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                CommandResultPayload.TYPE,
                CommandResultPayload.STREAM_CODEC);
        registered = true;
    }
}
