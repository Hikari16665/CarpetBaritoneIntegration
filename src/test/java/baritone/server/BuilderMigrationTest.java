package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BuilderMigrationTest {
    @Test
    public void schematicScanIsBudgetedAcrossServerTicks()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BuilderProcess.java"));
        assertTrue(source.contains(
                "SCHEMATIC_SCAN_BUDGET_PER_TICK"));
        assertTrue(source.contains(
                "enum ScanResult { FOUND, PENDING, COMPLETE }"));
        assertTrue(source.contains("scanCursor++"));
        assertTrue(source.contains(
                "if (scan == ScanResult.PENDING) return"));
    }
}
