package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class MineLegitMigrationTest {
    @Test
    public void legitMineUsesVisibleVeinDiscovery() throws Exception {
        String mine = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        String settings = Files.readString(Path.of(
                "src", "main", "java", "baritone", "api",
                "Settings.java"));

        assertTrue(settings.contains("legitMine ="));
        assertTrue(settings.contains("legitMineYLevel"));
        assertTrue(settings.contains("legitMineIncludeDiagonals"));
        assertTrue(mine.contains("scanLegitNearby"));
        assertTrue(mine.contains("RotationUtils.reachable"));
        assertTrue(mine.contains("legitMineYLevel.value"));
    }
}
