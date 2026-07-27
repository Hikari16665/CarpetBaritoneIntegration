package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class HelpCommandRegressionTest {
    @Test
    public void helpHasAliasesAndBoundedReplyLines() throws Exception {
        String command = Files.readString(Path.of(
                "src", "main", "java", "baritone", "command",
                "defaults", "HelpCommand.java"));
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        assertTrue(command.contains("\"help\", \"commands\", \"?\""));
        assertTrue(handler.contains("showHelp(sender, fakePlayer, args)"));
        assertTrue(handler.contains("导航: goto"));
        assertTrue(handler.contains("格式: /tell <假人>"));
    }
}
