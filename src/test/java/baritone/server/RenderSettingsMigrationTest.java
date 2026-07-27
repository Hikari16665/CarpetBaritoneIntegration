package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class RenderSettingsMigrationTest {
    @Test
    public void originalRenderSettingsRemainServerConfigurable()
            throws Exception {
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        for (String field : new String[]{
                "renderPath", "renderGoal",
                "renderSelectionBoxes", "renderSelection",
                "fadePath", "colorCurrentPath",
                "colorNextPath", "colorGoalBox"}) {
            assertTrue(settings.contains(field));
        }
        assertTrue(handler.contains("defaultValue instanceof java.awt.Color"));
    }
}
