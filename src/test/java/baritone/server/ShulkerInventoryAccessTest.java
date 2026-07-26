package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ShulkerInventoryAccessTest {
    @Test
    public void inventoryControllerTreatsCarriedShulkersAsStorage()
            throws IOException {
        String inventory = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerInventoryController.java"));
        assertTrue(inventory.contains("hasAccessibleItem"));
        assertTrue(inventory.contains("countAccessible"));
        assertTrue(inventory.contains("extractOneFromShulker"));
        assertTrue(inventory.contains("bestNestedTool"));
        assertTrue(inventory.contains("Never create"));
    }

    @Test
    public void rocketsToolsAndMineCountsUseNestedInventory()
            throws IOException {
        String elytra = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "ElytraProcess.java"));
        String mine = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        assertTrue(elytra.contains("hasAccessibleItem"));
        assertTrue(mine.contains("countAccessible"));
        assertTrue(mine.contains("ensureBestToolOnHotbar"));
    }
}
