package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.PathNetwork;
import me.nuoyuan.carpetbaritoneintegration.network.PathSnapshotPayload;
import me.nuoyuan.carpetbaritoneintegration.network.CommandResultPayload;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CarpetBaritoneIntegrationClient
        implements ClientModInitializer {
    private static KeyMapping openControl;

    @Override
    public void onInitializeClient() {
        PathNetwork.registerCommon();
        ClientPathRenderer.initialize();
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> ClientPathRenderer.close());
        ClientPlayNetworking.registerGlobalReceiver(
                PathSnapshotPayload.TYPE,
                (payload, context) -> ClientPathRenderer.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(
                me.nuoyuan.carpetbaritoneintegration.network
                        .ControlOptionsPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> ClientControlOptions.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                CommandResultPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> {
                            if (!payload.success()
                                    && context.client().player != null) {
                                context.client().player.sendSystemMessage(
                                        Component.literal("[CBI] "
                                                + payload.message()));
                            }
                        }));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    ClientPathRenderer.clear();
                    ClientControlOptions.clear();
                });
        openControl = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.carpetbaritoneintegration.open_control",
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openControl.consumeClick()) {
                client.gui.setScreen(new BaritoneControlScreen());
            }
        });
    }
}
