package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.CommandSubmitPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Sends GUI commands over the dedicated long-command transport. */
final class ClientCommandSender {
    private ClientCommandSender() { }

    static void send(String fakePlayer, String label, String arguments) {
        String command = label
                + (arguments == null || arguments.isBlank()
                ? "" : " " + arguments.trim());
        sendCommand(fakePlayer, command);
    }

    static void sendCommand(String fakePlayer, String command) {
        if (ClientPlayNetworking.canSend(CommandSubmitPayload.TYPE)) {
            ClientPlayNetworking.send(
                    new CommandSubmitPayload(fakePlayer, command));
            return;
        }
        // Compatibility fallback for an older server-side CBI.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("tell " + fakePlayer
                    + " baritone " + command);
        }
    }
}
