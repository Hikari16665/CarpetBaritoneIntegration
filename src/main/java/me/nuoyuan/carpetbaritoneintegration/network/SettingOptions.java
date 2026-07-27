package me.nuoyuan.carpetbaritoneintegration.network;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds type metadata for the client settings editor. */
public final class SettingOptions {
    private SettingOptions() { }

    public static List<ControlOptionsPayload.SettingOption> snapshot() {
        Settings settings = BaritoneAPI.getSettings();
        return Arrays.stream(Settings.class.getFields())
                .filter(field -> field.getType() == Settings.Setting.class)
                .sorted(Comparator.comparing(Field::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(field -> option(settings, field))
                .toList();
    }

    private static ControlOptionsPayload.SettingOption option(
            Settings settings, Field field) {
        try {
            Settings.Setting<?> setting =
                    (Settings.Setting<?>) field.get(settings);
            Object sample = setting.defaultValue;
            String type = type(field, sample);
            List<String> choices = sample instanceof Enum<?> value
                    ? Arrays.stream(value.getDeclaringClass()
                            .getEnumConstants()).map(Enum::name).toList()
                    : List.of();
            return new ControlOptionsPayload.SettingOption(
                    field.getName(), type, encode(setting.value),
                    encode(setting.defaultValue), choices);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String type(Field field, Object sample) {
        if (sample instanceof Boolean) return "BOOLEAN";
        if (sample instanceof Integer) return "INTEGER";
        if (sample instanceof Long) return "LONG";
        if (sample instanceof Float) return "FLOAT";
        if (sample instanceof Double) return "DOUBLE";
        if (sample instanceof Color) return "COLOR";
        if (sample instanceof Vec3i) return "VECTOR";
        if (sample instanceof Enum<?>) return "ENUM";
        if (sample instanceof Map<?, ?>) return "BLOCK_MAP";
        if (sample instanceof List<?>) {
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType setting
                    && setting.getActualTypeArguments()[0]
                    instanceof ParameterizedType list) {
                Type element = list.getActualTypeArguments()[0];
                if (element == Block.class) return "BLOCK_LIST";
                if (element == Item.class) return "ITEM_LIST";
            }
            return "STRING_LIST";
        }
        return "STRING";
    }

    public static String encode(Object value) {
        if (value instanceof Color color) {
            return String.format("#%08X", color.getRGB());
        }
        if (value instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (value instanceof Item item) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
        if (value instanceof Vec3i vector) {
            return vector.getX() + "," + vector.getY()
                    + "," + vector.getZ();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "none" : list.stream()
                    .map(SettingOptions::encode)
                    .collect(Collectors.joining(","));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry ->
                    encode(entry.getKey()) + "="
                            + ((List<?>) entry.getValue()).stream()
                            .map(SettingOptions::encode)
                            .collect(Collectors.joining("|")))
                    .collect(Collectors.joining(";"));
        }
        return String.valueOf(value);
    }
}
