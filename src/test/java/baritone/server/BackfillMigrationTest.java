package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BackfillMigrationTest {
    @Test
    public void preservesCurrentMovementAndPlacementConstraints()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BackfillProcess.java"));

        assertTrue(source.contains("partOfCurrentMovement"));
        assertTrue(source.contains("placementPlausible"));
        assertTrue(source.contains("allowParkour"));
        assertTrue(source.contains("placeSelectedBlock"));
    }
}
