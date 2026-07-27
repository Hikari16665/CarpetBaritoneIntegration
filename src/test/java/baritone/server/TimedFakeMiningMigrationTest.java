package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class TimedFakeMiningMigrationTest {
    @Test
    public void fakeMiningRequiresVisibilityAndAccumulatesProgress()
            throws Exception {
        String controller = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerFakeInteractionController.java"));
        String mine = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        assertTrue(controller.contains("canBreakFromHere"));
        assertTrue(controller.contains("ClipContext.Block.OUTLINE"));
        assertTrue(controller.contains(
                "hit.getType() == HitResult.Type.MISS"));
        assertTrue(controller.contains("state.getDestroyProgress"));
        assertTrue(controller.contains("destroyBlockProgress"));
        assertTrue(controller.contains("hardness == 0.0F"));
        assertTrue(controller.contains(
                "player.swing(InteractionHand.MAIN_HAND, true)"));
        assertTrue(controller.contains("breakProgress >= 1.0D")
                || controller.contains("breakProgress < 1.0D"));
        assertTrue(mine.contains("canBreakFromHere(pos)"));
    }
}
