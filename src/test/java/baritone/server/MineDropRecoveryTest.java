package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class MineDropRecoveryTest {
    @Test
    public void closeBlockedDropClearsPickupPocket() throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        assertTrue(source.contains("clearDropAccess()"));
        assertTrue(source.contains("ClipContext.Block.COLLIDER"));
        assertTrue(source.contains("for (int dy = 0; dy <= 2; dy++)"));
        assertTrue(source.contains(
                "getFakeInteractionController().pickup(collecting)"));
        assertTrue(source.contains(
                "getFakeInteractionController().canReach(pos)"));
        assertTrue(source.contains("Never include origin.below()"));
    }
}
