package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class FakeReachGeometryTest {
    @Test
    public void reachUsesNearestBlockBoundaryNotCenter()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerFakeInteractionController.java"));

        assertTrue(source.contains("double closestX"));
        assertTrue(source.contains("double closestY"));
        assertTrue(source.contains("double closestZ"));
        assertTrue(source.contains("entity.position()"));
    }
}
