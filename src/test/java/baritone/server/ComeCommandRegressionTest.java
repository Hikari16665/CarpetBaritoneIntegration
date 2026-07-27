package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ComeCommandRegressionTest {
    @Test
    public void comeStopsNearSenderInsteadOfOccupyingSenderBlock()
            throws Exception {
        String handler = Files.readString(Path.of("src", "main", "java",
                "baritone", "server", "BasicGoalCommandHandler.java"));
        assertTrue(handler.contains(
                "new GoalNear(sender.blockPosition(), 2)"));
    }
}
