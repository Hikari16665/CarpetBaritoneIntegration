package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PathSnapshotRevisionTest {
    @Test
    public void staleAsyncPathIsRecalculatedWithoutFailure()
            throws Exception {
        String baritone = Files.readString(Path.of(
                "src", "main", "java", "baritone", "Baritone.java"));
        String cache = Files.readString(Path.of(
                "src", "main", "java", "baritone", "cache",
                "ServerWorldCache.java"));

        assertTrue(baritone.contains("snapshotRevisions"));
        assertTrue(baritone.contains("pathSnapshotStillValid"));
        assertTrue(baritone.contains("nextRecalculationTick"));
        assertTrue(cache.contains("exactSnapshotRevisions"));
        assertTrue(cache.contains("exactSnapshotRevision"));
        assertTrue(cache.contains(
                "ExactChunkSnapshot exact = exactSnapshots.get(key)"));
        assertTrue(cache.contains("previous.getBlockState"));
    }
}
