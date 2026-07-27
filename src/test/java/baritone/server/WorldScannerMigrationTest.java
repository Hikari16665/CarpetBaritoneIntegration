package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class WorldScannerMigrationTest {
    @Test
    public void loadedChunkScanDoesNotWaitForAsyncPacking()
            throws Exception {
        String scanner = Files.readString(Path.of(
                "src", "main", "java", "baritone", "cache",
                "WorldScanner.java"));
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));

        assertTrue(scanner.contains("scanLoadedChunk"));
        assertTrue(scanner.contains("section.maybeHas"));
        assertTrue(scanner.contains("synchronousWorldScannerChunkBudget"));
        assertTrue(settings.contains(
                "synchronousWorldScannerChunkBudget"));
    }
}
