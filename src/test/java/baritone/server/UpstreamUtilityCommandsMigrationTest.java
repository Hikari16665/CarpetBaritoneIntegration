package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class UpstreamUtilityCommandsMigrationTest {
    @Test
    public void serverCommandSurfaceIncludesRemainingUpstreamUtilities()
            throws Exception {
        String commands = Files.readString(Path.of(
                "src/main/java/baritone/command/defaults/"
                        + "NavigationUtilityCommand.java"));
        String handler = Files.readString(Path.of(
                "src/main/java/baritone/server/"
                        + "BasicGoalCommandHandler.java"));
        for (String command : new String[]{
                "blacklist", "find", "pickup",
                "reloadall", "saveall", "gc"}) {
            assertTrue(commands.contains("\"" + command + "\""));
            assertTrue(handler.contains("case \"" + command + "\""));
        }
    }
}
