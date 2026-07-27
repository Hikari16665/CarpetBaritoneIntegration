package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ExploreMigrationTest {

    @Test
    public void keepsUpstreamFrontierOffsetAndFailureRecovery() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/baritone/process/ExploreProcess.java"));

        assertTrue(source.contains("worldExploringChunkOffset"));
        assertTrue(source.contains("disableCompletionCheck"));
        assertTrue(source.contains("if (calcFailed)"));
        assertTrue(source.contains("distanceCompleted++"));
        assertTrue(source.contains("PathingCommandType.REQUEST_PAUSE"));
    }
}
