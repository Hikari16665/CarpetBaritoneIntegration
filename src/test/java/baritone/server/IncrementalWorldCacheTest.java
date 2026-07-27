package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class IncrementalWorldCacheTest {
    @Test
    public void blockChangesUseCopyOnWriteSnapshots() throws Exception {
        String cache = Files.readString(Path.of(
                "src", "main", "java", "baritone", "cache",
                "ServerWorldCache.java"));
        String exact = Files.readString(Path.of(
                "src", "main", "java", "baritone", "cache",
                "ExactChunkSnapshot.java"));
        String mixins = Files.readString(Path.of(
                "src", "main", "resources",
                "carpetbaritoneintegration.mixins.json"));

        assertTrue(cache.contains("void updateBlock"));
        assertTrue(cache.contains("updateIndexAt"));
        assertTrue(cache.contains("pendingCaptures.add(key)"));
        assertTrue(exact.contains("withBlock"));
        assertTrue(exact.contains("sections.clone()"));
        assertTrue(mixins.contains("LevelBlockChangeMixin"));
    }
}
