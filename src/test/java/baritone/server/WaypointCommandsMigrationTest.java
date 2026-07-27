package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class WaypointCommandsMigrationTest {
    @Test
    public void supportsPersistentServerWaypointCommands()
            throws Exception {
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        String commands = Files.readString(Path.of(
                "src", "main", "java", "baritone", "command",
                "defaults", "NavigationUtilityCommand.java"));

        assertTrue(commands.contains("\"waypoints\""));
        assertTrue(commands.contains("\"sethome\""));
        assertTrue(handler.contains("manageWaypoints"));
        assertTrue(handler.contains("getMostRecentByTag"));
        assertTrue(handler.contains("removeWaypoint"));
        assertTrue(handler.contains("new Waypoint"));
    }
}
