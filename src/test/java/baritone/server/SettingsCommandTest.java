package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class SettingsCommandTest {
    @Test
    public void settingsCommandIsRegisteredAndServerSafe()
            throws IOException {
        String defaults = Files.readString(Path.of(
                "src", "main", "java", "baritone", "command",
                "defaults", "DefaultCommands.java"));
        String command = Files.readString(Path.of(
                "src", "main", "java", "baritone", "command",
                "defaults", "SettingsCommand.java"));
        assertTrue(defaults.contains("new SettingsCommand(baritone)"));
        assertTrue(command.contains(
                "\"set\", \"setting\", \"settings\""));
        assertTrue(command.contains("extends ServerCommand"));
    }

    @Test
    public void settingsSupportListQueryMutationToggleAndReset()
            throws IOException {
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        assertTrue(handler.contains("manageSettings("));
        assertTrue(handler.contains("operation.equals(\"toggle\")"));
        assertTrue(handler.contains("operation.equals(\"reset\")"));
        assertTrue(handler.contains("parseSettingList"));
        assertTrue(handler.contains("recalculateAfterSettingChange"));
        assertTrue(handler.contains("设置为全服共享"));
    }
}
