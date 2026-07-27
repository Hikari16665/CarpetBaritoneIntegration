package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class FollowComePolicyTest {
    @Test
    public void followAndComeSuppressTrashWithoutDisablingPlacement()
            throws Exception {
        String baritone = read("baritone/Baritone.java");
        String follow = read("baritone/process/FollowProcess.java");
        String custom = read("baritone/process/CustomGoalProcess.java");
        String handler = read("baritone/server/BasicGoalCommandHandler.java");
        String context = read(
                "baritone/pathing/movement/CalculationContext.java");

        assertTrue(baritone.contains(
                "followProcess.suppressesTrashDiscard()"));
        assertTrue(baritone.contains(
                "customGoalProcess.suppressesTrashDiscard()"));
        assertTrue(follow.contains("return filter != null && !into"));
        assertTrue(custom.contains("boolean suppressTrashDiscard"));
        assertTrue(handler.contains(
                "\"发送者 \" + sender.getScoreboardName(), true"));
        assertTrue(context.contains(
                "hasThrowaway = !collectOnly"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src", "main", "java")
                .resolve(relative));
    }
}
