package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class TypedSettingsGuiTest {
    @Test
    public void serverSynchronizesTypedSettingsAndWaypoints()
            throws Exception {
        String payload = read("me/nuoyuan/carpetbaritoneintegration/"
                + "network/ControlOptionsPayload.java");
        String options = read("me/nuoyuan/carpetbaritoneintegration/"
                + "network/SettingOptions.java");
        String handler = read("baritone/server/"
                + "BasicGoalCommandHandler.java");
        assertTrue(payload.contains("SettingOption"));
        assertTrue(payload.contains("WaypointOption"));
        assertTrue(options.contains("\"BOOLEAN\""));
        assertTrue(options.contains("\"COLOR\""));
        assertTrue(options.contains("\"VECTOR\""));
        assertTrue(options.contains("\"BLOCK_LIST\""));
        assertTrue(options.contains("\"BLOCK_MAP\""));
        assertTrue(handler.contains("parseBlockMap"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src", "main", "java")
                .resolve(relative));
    }
}
