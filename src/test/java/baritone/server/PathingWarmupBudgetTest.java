package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PathingWarmupBudgetTest {
    @Test
    public void pathSubmissionWarmsBoundedSharedSnapshots()
            throws Exception {
        String baritone = Files.readString(Path.of(
                "src", "main", "java", "baritone", "Baritone.java"));
        String cache = Files.readString(Path.of(
                "src", "main", "java", "baritone", "cache",
                "ServerWorldCache.java"));
        String scheduler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerPathingScheduler.java"));

        assertTrue(baritone.contains("warmExactSnapshots"));
        assertTrue(cache.contains("copied >= budget"));
        assertTrue(scheduler.contains("Math.min(2"));
        assertTrue(scheduler.contains("REJECTED"));
    }
}
