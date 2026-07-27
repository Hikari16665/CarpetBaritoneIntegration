package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class FakePathPlacementRegressionTest {
    @Test
    public void pathPlacementCanSelectInventoryAndHasLegalFallback()
            throws Exception {
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));
        String interaction = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerFakeInteractionController.java"));
        String movement = Files.readString(Path.of(
                "src", "main", "java", "baritone", "pathing",
                "movement", "Movement.java"));
        assertTrue(settings.contains(
                "allowInventory = new Setting<>(true)"));
        assertTrue(movement.contains("placeSelectedBlock(positionToPlace)"));
        assertTrue(interaction.contains("placeFullBlockDirect"));
        assertTrue(interaction.contains("placed.canSurvive"));
        assertTrue(interaction.contains("isUnobstructed"));
    }
}
