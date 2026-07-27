package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BuilderActionableScanTest {
    @Test
    public void missingMaterialsDoNotHideActionableTargets()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BuilderProcess.java"));

        assertTrue(source.contains("missingInScan"));
        assertTrue(source.contains("!canPlace(wanted)"));
        assertTrue(source.contains("skipFailedLayers"));
        assertTrue(source.contains("failedUntil"));
        assertTrue(source.contains("deferFailedTarget"));
    }
}
