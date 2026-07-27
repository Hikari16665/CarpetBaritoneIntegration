package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BuilderRepeatMigrationTest {
    @Test
    public void repeatBuildUsesUpstreamSettings() throws Exception {
        String builder = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BuilderProcess.java"));
        String command = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));

        assertTrue(builder.contains("repeatBuild"));
        assertTrue(builder.contains("buildRepeatCount"));
        assertTrue(builder.contains("buildRepeatSneaky"));
        assertTrue(builder.contains("origin.offset(repeat)"));
        assertTrue(command.contains("text.split(\"[, :]\"")
                || command.contains("text.split(\"[,:]\""));
    }
}
