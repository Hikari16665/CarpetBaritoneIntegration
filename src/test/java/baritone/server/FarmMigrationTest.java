package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class FarmMigrationTest {
    @Test
    public void portsUpstreamCultivationThroughFakeInteractions()
            throws Exception {
        String farm = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "FarmProcess.java"));
        String interactions = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerFakeInteractionController.java"));

        assertTrue(farm.contains("FARMLAND, NETHER_WART, COCOA, BONE_MEAL"));
        assertTrue(farm.contains("cultivationAt"));
        assertTrue(farm.contains("useSelectedOnBlock"));
        assertTrue(farm.contains("useSelectedAt(interaction)"));
        assertTrue(interactions.contains("boolean useSelectedOnBlock"));
    }
}
