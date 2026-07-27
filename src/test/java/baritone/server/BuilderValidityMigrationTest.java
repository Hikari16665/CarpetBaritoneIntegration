package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BuilderValidityMigrationTest {
    @Test
    public void portsUpstreamBlockValiditySettings() throws Exception {
        String builder = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BuilderProcess.java"));
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));

        assertTrue(builder.contains("okIfWater"));
        assertTrue(builder.contains("okIfAir"));
        assertTrue(builder.contains("buildIgnoreExisting"));
        assertTrue(builder.contains("buildValidSubstitutes"));
        assertTrue(builder.contains("buildIgnoreDirection"));
        assertTrue(builder.contains("buildIgnoreProperties"));
        assertTrue(settings.contains("buildValidSubstitutes"));
    }
}
