package me.nuoyuan.carpetbaritoneintegration.client;

import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsRequestPayload;
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

    private ClientControlOptions() { }

    static void request() {
        supported = ClientPlayNetworking.canSend(
                ControlOptionsRequestPayload.TYPE);
        if (supported) {
            ClientPlayNetworking.send(new ControlOptionsRequestPayload());
        }
    }

    static void accept(ControlOptionsPayload payload) {
        fakePlayers = payload.fakePlayers();
        onlinePlayers = payload.onlinePlayers();
        schematicFiles = payload.schematicFiles();
        syncmaticaSchematics = payload.syncmaticaSchematics();
        settings = payload.settings();
        waypoints = payload.waypoints();
        received = true;
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof CommandParameterScreen screen) {
            screen.optionsUpdated();
        } else if (client.gui.screen() instanceof SettingsListScreen screen) {
            screen.optionsUpdated();
        } else if (client.gui.screen() instanceof StructuredCommandScreen screen) {
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
        // Keep selectedFakePlayer across disconnects and screen reopenings.
    }
}
