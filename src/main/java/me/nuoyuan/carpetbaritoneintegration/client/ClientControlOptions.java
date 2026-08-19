package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsRequestPayload;
import me.nuoyuan.carpetbaritoneintegration.network.OverloadStatePayload;
import me.nuoyuan.carpetbaritoneintegration.network.OverloadStateRequestPayload;
import me.nuoyuan.carpetbaritoneintegration.network.OverloadTogglePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.List;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload.SettingOption;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload.WaypointOption;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload.SyncmaticaOption;

final class ClientControlOptions {
    private static List<String> fakePlayers = List.of();
    private static List<String> onlinePlayers = List.of();
    private static List<String> schematicFiles = List.of();
    private static List<SyncmaticaOption> syncmaticaSchematics = List.of();
    private static List<SettingOption> settings = List.of();
    private static List<WaypointOption> waypoints = List.of();
    private static boolean received;
    private static boolean supported = true;
    private static String selectedFakePlayer = "";
    private static boolean overloadEnabled;
    private static boolean canManageOverload;
    private static String overloadMessage = "";

    private ClientControlOptions() { }

    static void request() {
        supported = ClientPlayNetworking.canSend(
                ControlOptionsRequestPayload.TYPE);
        if (supported) {
            ClientPlayNetworking.send(new ControlOptionsRequestPayload());
            if (ClientPlayNetworking.canSend(
                    OverloadStateRequestPayload.TYPE)) {
                ClientPlayNetworking.send(
                        new OverloadStateRequestPayload());
            }
        }
    }

    static void acceptOverload(OverloadStatePayload payload) {
        overloadEnabled = payload.enabled();
        canManageOverload = payload.canManage();
        overloadMessage = payload.message();
        Minecraft client = Minecraft.getInstance();
        if (!overloadMessage.isBlank() && client.player != null) {
            client.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[CBI] " + overloadMessage));
        }
        if (client.screen instanceof BaritoneControlScreen screen) {
            screen.overloadStateUpdated();
        } else if (client.screen instanceof OverloadConfirmScreen screen) {
            screen.stateUpdated();
        }
    }

    static void setOverload(boolean enabled) {
        if (canManageOverload && ClientPlayNetworking.canSend(
                OverloadTogglePayload.TYPE)) {
            ClientPlayNetworking.send(new OverloadTogglePayload(enabled));
        }
    }

    static boolean overloadEnabled() { return overloadEnabled; }
    static boolean canManageOverload() { return canManageOverload; }

    static void accept(ControlOptionsPayload payload) {
        fakePlayers = payload.fakePlayers();
        onlinePlayers = payload.onlinePlayers();
        schematicFiles = payload.schematicFiles();
        syncmaticaSchematics = payload.syncmaticaSchematics();
        settings = payload.settings();
        waypoints = payload.waypoints();
        received = true;
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof BaritoneControlScreen screen) {
            screen.optionsUpdated();
        } else if (client.screen instanceof CommandParameterScreen screen) {
            screen.optionsUpdated();
        } else if (client.screen instanceof SettingsListScreen screen) {
            screen.optionsUpdated();
        } else if (client.screen instanceof StructuredCommandScreen screen) {
            screen.optionsUpdated();
        }
    }

    static List<String> fakePlayers() {
        return fakePlayers;
    }

    static List<String> onlinePlayers() {
        return onlinePlayers;
    }

    static List<String> schematicFiles() {
        return schematicFiles;
    }

    static List<SyncmaticaOption> syncmaticaSchematics() {
        return syncmaticaSchematics;
    }

    static List<SettingOption> settings() {
        return settings;
    }

    static List<WaypointOption> waypoints(String fake) {
        return waypoints.stream().filter(value ->
                value.fake().equals(fake)).toList();
    }

    static int selectedFakeIndex() {
        int index = fakePlayers.indexOf(selectedFakePlayer);
        return index < 0 ? 0 : index;
    }

    static void rememberFake(String name) {
        if (name != null && !name.isBlank()) selectedFakePlayer = name;
    }

    static boolean received() {
        return received;
    }

    static boolean supported() {
        return supported;
    }

    static void clear() {
        fakePlayers = List.of();
        onlinePlayers = List.of();
        schematicFiles = List.of();
        syncmaticaSchematics = List.of();
        settings = List.of();
        waypoints = List.of();
        received = false;
        supported = true;
        overloadEnabled = false;
        canManageOverload = false;
        overloadMessage = "";
        // Keep selectedFakePlayer across disconnects and screen reopenings.
    }
}
