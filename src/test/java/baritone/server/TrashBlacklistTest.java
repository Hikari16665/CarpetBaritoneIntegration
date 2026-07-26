package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrashBlacklistTest {
    @Test
    public void onlyConfiguredTrashIsDiscarded() throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "TrashDiscardController.java"));
        assertTrue(source.contains("isConfiguredTrash"));
        assertTrue(source.contains("trashItems.value.contains"));
        assertFalse(source.contains("SCAFFOLD_RESERVE"));
    }

    @Test
    public void mineSupportsCombinedTargetsAndDropCounts() throws IOException {
        String command = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        String mine = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        assertTrue(command.contains("targets.toArray(Block[]::new)"));
        assertTrue(mine.contains("primeDesiredDrops"));
        assertTrue(mine.contains("desiredDropItems.contains"));
    }
}
