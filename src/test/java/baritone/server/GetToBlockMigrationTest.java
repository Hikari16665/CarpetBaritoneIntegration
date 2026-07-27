package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class GetToBlockMigrationTest {
    @Test
    public void portsExplorationAndContainerArrivalToServer()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "GetToBlockProcess.java"));

        assertTrue(source.contains("exploreForBlocks"));
        assertTrue(source.contains("new GoalRunAway"));
        assertTrue(source.contains("rightClickContainerOnArrival"));
        assertTrue(source.contains("interactBlock"));
        assertTrue(source.contains("blockOnTopMustBeRemoved"));
        assertTrue(source.contains(
                "WorldScanner.INSTANCE.scanChunkRadius"));
    }
}
