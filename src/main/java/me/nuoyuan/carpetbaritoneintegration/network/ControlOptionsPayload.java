package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record ControlOptionsPayload(
        List<String> fakePlayers,
        List<String> onlinePlayers,
        List<SettingOption> settings,
        List<WaypointOption> waypoints
) implements CustomPacketPayload {
    public static final Type<ControlOptionsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "control_options"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            ControlOptionsPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    ControlOptionsPayload::write,
                    ControlOptionsPayload::new);

    public ControlOptionsPayload {
        fakePlayers = List.copyOf(fakePlayers);
        onlinePlayers = List.copyOf(onlinePlayers);
        settings = List.copyOf(settings);
        waypoints = List.copyOf(waypoints);
    }

    private ControlOptionsPayload(RegistryFriendlyByteBuf buffer) {
        this(readNames(buffer), readNames(buffer), readSettings(buffer),
                readWaypoints(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        writeNames(buffer, fakePlayers);
        writeNames(buffer, onlinePlayers);
        buffer.writeVarInt(settings.size());
        for (SettingOption setting : settings) setting.write(buffer);
        buffer.writeVarInt(waypoints.size());
        for (WaypointOption waypoint : waypoints) waypoint.write(buffer);
    }

    private static List<String> readNames(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(512, buffer.readVarInt());
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) names.add(buffer.readUtf(64));
        return names;
    }

    private static void writeNames(
            RegistryFriendlyByteBuf buffer, List<String> names) {
        int count = Math.min(512, names.size());
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buffer.writeUtf(names.get(i), 64);
        }
    }

    private static List<SettingOption> readSettings(
            RegistryFriendlyByteBuf buffer) {
        int count = Math.min(1024, buffer.readVarInt());
        List<SettingOption> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(SettingOption.read(buffer));
        }
        return result;
    }

    public record SettingOption(
            String name, String type, String value,
            String defaultValue, List<String> choices) {
        public SettingOption {
            choices = List.copyOf(choices);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(name, 128);
            buffer.writeUtf(type, 32);
            buffer.writeUtf(value, 32767);
            buffer.writeUtf(defaultValue, 32767);
            writeNames(buffer, choices);
        }

        private static SettingOption read(RegistryFriendlyByteBuf buffer) {
            return new SettingOption(
                    buffer.readUtf(128), buffer.readUtf(32),
                    buffer.readUtf(32767), buffer.readUtf(32767),
                    readNames(buffer));
        }
    }

    private static List<WaypointOption> readWaypoints(
            RegistryFriendlyByteBuf buffer) {
        int count = Math.min(4096, buffer.readVarInt());
        List<WaypointOption> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(WaypointOption.read(buffer));
        }
        return result;
    }

    public record WaypointOption(
            String fake, String name, String tag, int x, int y, int z) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(fake, 64);
            buffer.writeUtf(name, 256);
            buffer.writeUtf(tag, 32);
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
        }

        private static WaypointOption read(RegistryFriendlyByteBuf buffer) {
            return new WaypointOption(
                    buffer.readUtf(64), buffer.readUtf(256),
                    buffer.readUtf(32), buffer.readInt(),
                    buffer.readInt(), buffer.readInt());
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
