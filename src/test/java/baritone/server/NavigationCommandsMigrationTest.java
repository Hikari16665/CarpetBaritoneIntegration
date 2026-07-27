package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class NavigationCommandsMigrationTest {
    @Test
    public void registersServerSafeUpstreamNavigationCommands()
            throws Exception {
        String defaults = Files.readString(Path.of(
                "src", "main", "java", "baritone", "command",
                "defaults", "DefaultCommands.java"));
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));

        assertTrue(defaults.contains("NavigationUtilityCommand"));
        assertTrue(handler.contains("case \"goal\""));
        assertTrue(handler.contains("case \"path\""));
        assertTrue(handler.contains("case \"surface\", \"top\""));
        assertTrue(handler.contains("case \"thisway\", \"forward\""));
        assertTrue(handler.contains("case \"axis\", \"highway\""));
        assertTrue(handler.contains("case \"eta\""));
        assertTrue(handler.contains(
                "getCustomGoalProcess().setGoalAndPath("));
    }
}
