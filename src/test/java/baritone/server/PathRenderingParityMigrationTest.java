package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PathRenderingParityMigrationTest {
    @Test
    public void transportsOriginalPathRendererState() throws Exception {
        String payload = Files.readString(Path.of(
                "src", "main", "java", "me", "nuoyuan",
                "carpetbaritoneintegration", "network",
                "PathSnapshotPayload.java"));
        String renderer = Files.readString(Path.of(
                "src", "main", "java", "me", "nuoyuan",
                "carpetbaritoneintegration", "client",
                "ClientPathRenderer.java"));
        for (String field : new String[]{
                "bestPathSoFar", "mostRecentConsidered",
                "blocksToBreak", "blocksToPlace",
                "blocksToWalkInto", "selectionCorners"}) {
            assertTrue(payload.contains(field));
            assertTrue(renderer.contains(field + "()"));
        }
    }
}
