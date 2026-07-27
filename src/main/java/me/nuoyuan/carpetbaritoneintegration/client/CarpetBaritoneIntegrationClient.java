package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.PathNetwork;
import me.nuoyuan.carpetbaritoneintegration.network.PathSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public final class CarpetBaritoneIntegrationClient
        implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PathNetwork.registerCommon();
        ClientPlayNetworking.registerGlobalReceiver(
                PathSnapshotPayload.TYPE,
                (payload, context) -> ClientPathRenderer.accept(payload));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> ClientPathRenderer.clear());
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(
                ClientPathRenderer::render);
    }
}
