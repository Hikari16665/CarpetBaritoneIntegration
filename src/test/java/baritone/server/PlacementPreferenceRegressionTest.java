package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PlacementPreferenceRegressionTest {
    @Test
    public void placementCanCompeteWithDetours() throws Exception {
        String settings = Files.readString(Path.of("src", "main", "java",
                "baritone", "api", "Settings.java"));
        String context = Files.readString(Path.of("src", "main", "java",
                "baritone", "pathing", "movement",
                "CalculationContext.java"));
        assertTrue(settings.contains(
                "blockPlacementPenalty = new Setting<>(1.0D)"));
        assertTrue(settings.contains("goalDirectedPlacementMultiplier"));
        assertTrue(settings.contains("goalDirectedPillarCostMultiplier"));
        assertTrue(context.contains("above < here || bestNeighbor < here"));
        assertTrue(context.contains("[CBI-DIAG] path-context"));
    }
}
