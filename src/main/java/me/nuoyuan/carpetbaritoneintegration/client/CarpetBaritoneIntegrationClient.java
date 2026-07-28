package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.PathNetwork;
import me.nuoyuan.carpetbaritoneintegration.network.PathSnapshotPayload;
import me.nuoyuan.carpetbaritoneintegration.network.CommandResultPayload;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CarpetBaritoneIntegrationClient
        implements ClientModInitializer {
    private static KeyMapping openControl;

    @Override
    public void onInitializeClient() {
        PathNetwork.registerCommon();
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
                                context.client().player.displayClientMessage(
                                        Component.literal("[CBI] "
                                                + payload.message()), false);
                            }
                        }));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    ClientPathRenderer.clear();
                    ClientControlOptions.clear();
                });
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(
                ClientPathRenderer::render);
        openControl = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.carpetbaritoneintegration.open_control",
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openControl.consumeClick()) {
                client.setScreen(new BaritoneControlScreen());
            }
        });
    }
}
