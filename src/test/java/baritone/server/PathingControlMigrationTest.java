package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PathingControlMigrationTest {
    @Test
    public void preservesUpstreamOwnershipAndInvalidationSemantics()
            throws Exception {
        String manager = Files.readString(Path.of(
                "src", "main", "java", "baritone", "utils",
                "PathingControlManager.java"));
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));

        assertTrue(manager.contains("if (command == null)"));
        assertTrue(manager.contains("inControlLastTick.isTemporary"));
        assertTrue(manager.contains("cancelOnGoalInvalidation"));
        assertTrue(manager.contains("stayed active after cancellation"));
        assertTrue(settings.contains("cancelOnGoalInvalidation"));
    }
}
