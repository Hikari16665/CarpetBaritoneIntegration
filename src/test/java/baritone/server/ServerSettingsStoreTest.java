package baritone.server;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ServerSettingsStoreTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void settingCopiesMutableDefaults() {
        Settings.Setting<java.util.List<String>> setting =
                new Settings.Setting<>(new java.util.ArrayList<>(
                        java.util.List.of("stone")));
        setting.value.add("dirt");
        assertEquals(java.util.List.of("stone"), setting.defaultValue);
        setting.reset();
        assertEquals(java.util.List.of("stone"), setting.value);
        assertNotSame(setting.defaultValue, setting.value);
    }

    @Test
    public void persistentDefaultSurvivesReloadAndCanBeCleared()
            throws Exception {
        Path file = temporary.newFile("settings-defaults.json").toPath();
        java.nio.file.Files.delete(file);
        Field field = Settings.class.getField("allowSprint");
        Settings.Setting<Boolean> setting = BaritoneAPI.getSettings()
                .allowSprint;
        try {
            ServerSettingsStore.load(file);
            ServerSettingsStore.setDefault(field, setting, false);
            setting.value = true;
            ServerSettingsStore.load(file);
            assertFalse(setting.defaultValue);
            assertFalse(setting.value);

            ServerSettingsStore.restoreFactoryDefault(field, setting);
            assertTrue(setting.defaultValue);
            assertTrue(setting.value);
        } finally {
            setting.restoreFactoryDefault(true);
        }
    }
}
