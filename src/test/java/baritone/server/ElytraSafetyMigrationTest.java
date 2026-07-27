package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ElytraSafetyMigrationTest {
    @Test
    public void swapsWornElytraAndFindsSafeLanding() throws Exception {
        String process = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "ElytraProcess.java"));
        String inventory = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerInventoryController.java"));

        assertTrue(process.contains("elytraAutoSwap"));
        assertTrue(process.contains("findSafeLandingSpot"));
        assertTrue(process.contains("safeLandingColumn"));
        assertTrue(process.contains(
                "|| state == State.CLIMB_BACK;"));
        assertTrue(inventory.contains("equipBestElytra"));
    }
}
