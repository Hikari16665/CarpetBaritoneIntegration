package baritone.server;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Persists server-wide defaults separately from temporary setting values. */
public final class ServerSettingsStore {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().create();
    private static Path file;

    private ServerSettingsStore() { }

    public static synchronized void load() {
        load(FabricLoader.getInstance().getConfigDir()
                .resolve("carpetbaritoneintegration")
                .resolve("settings-defaults.json"));
    }

    static synchronized void load(Path target) {
        file = target;
        Settings settings = BaritoneAPI.getSettings();
        settingFields().forEach(field ->
                readSetting(settings, field).restoreFactoryDefault(true));
        if (!Files.isRegularFile(target)) return;
        try {
            JsonObject root = GSON.fromJson(
                    Files.readString(target, StandardCharsets.UTF_8),
                    JsonObject.class);
            if (root == null || !root.has("defaults")
                    || !root.get("defaults").isJsonObject()) return;
            JsonObject defaults = root.getAsJsonObject("defaults");
            for (Map.Entry<String, JsonElement> entry
                    : defaults.entrySet()) {
                Field field = findSetting(entry.getKey());
                if (field == null || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                Settings.Setting<?> setting = readSetting(settings, field);
                Object parsed = BasicGoalCommandHandler.parseSettingValue(
                        field, setting.factoryDefaultValue,
                        entry.getValue().getAsString());
                setDefault(setting, parsed, true);
            }
        } catch (Exception exception) {
            System.err.println("[CBI] Failed to load persistent settings from "
                    + target + ": " + exception.getMessage());
        }
    }

    public static synchronized void setDefault(
            Field field, Settings.Setting<?> setting, Object value) {
        setDefault(setting, value, true);
        save();
    }

    public static synchronized void restoreFactoryDefault(
            Field field, Settings.Setting<?> setting) {
        setting.restoreFactoryDefault(true);
        save();
    }

    public static synchronized void restoreAllFactoryDefaults() {
        Settings settings = BaritoneAPI.getSettings();
        settingFields().forEach(field ->
                readSetting(settings, field).restoreFactoryDefault(true));
        save();
    }

    public static synchronized Path file() {
        return file;
    }

    private static void save() {
        Path target = file;
        if (target == null) {
            target = FabricLoader.getInstance().getConfigDir()
                    .resolve("carpetbaritoneintegration")
                    .resolve("settings-defaults.json");
            file = target;
        }
        JsonObject defaults = new JsonObject();
        Settings settings = BaritoneAPI.getSettings();
        for (Field field : settingFields()) {
            Settings.Setting<?> setting = readSetting(settings, field);
            if (setting.hasCustomDefault()) {
                defaults.addProperty(field.getName(),
                        BasicGoalCommandHandler.settingValue(
                                setting.defaultValue));
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.add("defaults", defaults);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(
                    target.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法保存默认设置到 " + target, exception);
        }
    }

    private static List<Field> settingFields() {
        return java.util.Arrays.stream(Settings.class.getFields())
                .filter(field -> field.getType() == Settings.Setting.class)
                .sorted(Comparator.comparing(Field::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static Field findSetting(String name) {
        return settingFields().stream()
                .filter(field -> field.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private static Settings.Setting<?> readSetting(
            Settings settings, Field field) {
        try {
            return (Settings.Setting<?>) field.get(settings);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setDefault(
            Settings.Setting setting, Object value, boolean applyNow) {
        setting.setDefault(value, applyNow);
    }
}
