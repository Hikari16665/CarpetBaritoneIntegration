package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ExecutionAndSelectionCommandTest {
    @Test
    public void pauseIsTemporaryProcessAndCommandsAreRegistered()
            throws Exception {
        String pause = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "PauseProcess.java"));
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));

        assertTrue(pause.contains("PathingCommandType.REQUEST_PAUSE"));
        assertTrue(pause.contains("return DEFAULT_PRIORITY + 1.0D"));
        assertTrue(handler.contains("case \"pause\""));
        assertTrue(handler.contains("case \"resume\""));
        assertTrue(handler.contains("case \"tunnel\""));
        assertTrue(handler.contains("manageSelection"));
    }
}
