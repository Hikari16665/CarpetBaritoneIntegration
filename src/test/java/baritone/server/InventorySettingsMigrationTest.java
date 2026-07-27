package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class InventorySettingsMigrationTest {
    @Test
    public void originalInventoryMoveSettingsControlServerSwaps()
            throws Exception {
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));
        String controller = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerInventoryController.java"));
        for (String field : new String[]{
                "allowInventory", "ticksBetweenInventoryMoves",
                "inventoryMoveOnlyIfStationary"}) {
            assertTrue(settings.contains(field));
            assertTrue(controller.contains(field));
        }
        assertTrue(controller.contains("canMoveInventoryNow"));
    }
}
