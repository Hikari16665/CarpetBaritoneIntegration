package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class AreaMineProcessTest {
    @Test
    public void areaMineUsesSelectionAndIsPersistent()
            throws IOException {
        String process = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        assertTrue(handler.contains("case \"areamine\""));
        assertTrue(handler.contains("mineAreaWithFeedback"));
        assertTrue(process.contains("insideArea(pos)"));
        assertTrue(process.contains("areaMine ? 20"));
        assertTrue(process.contains(
                "null, PathingCommandType.REQUEST_PAUSE"));
        assertTrue(process.contains(
                "(areaMine ? \"AreaMine \" : \"Mine \")"));
    }

    @Test
    public void areaMineDoesNotUseQuantityCompletion()
            throws IOException {
        String process = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        assertTrue(process.contains(
                "mineWithFeedback(0, requested, feedback)"));
        assertTrue(process.contains(
                "areaMin = selection.min().immutable()"));
        assertTrue(process.contains(
                "areaMax = selection.max().immutable()"));
    }
}
