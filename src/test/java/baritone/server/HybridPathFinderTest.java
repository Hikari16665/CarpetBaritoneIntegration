package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class HybridPathFinderTest {
    @Test
    public void lowBudgetJpsFallsBackToAStar() throws IOException {
        String hybrid = Files.readString(Path.of(
                "src", "main", "java", "baritone", "pathing",
                "calc", "HybridPathFinder.java"));
        String host = Files.readString(Path.of(
                "src", "main", "java", "baritone", "Baritone.java"));
        assertTrue(hybrid.contains("Math.min(100L"));
        assertTrue(hybrid.contains("Math.min(250L"));
        assertTrue(hybrid.contains(
                "PathCalculationResult.Type.SUCCESS_TO_GOAL"));
        assertTrue(hybrid.contains(
                "return aStar.calculate(primaryTimeout, failureTimeout)"));
        assertTrue(host.contains("new HybridPathFinder("));
    }

    @Test
    public void jpsIsConservativeFlatAndModificationFree()
            throws IOException {
        String jps = Files.readString(Path.of(
                "src", "main", "java", "baritone", "pathing",
                "calc", "JumpPointPathFinder.java"));
        assertTrue(jps.contains("explicitGoal.y == startY"));
        assertTrue(jps.contains("feet.isAir() && head.isAir()"));
        assertTrue(jps.contains("hasForcedNeighbor"));
        assertTrue(jps.contains("alignedWithGoal"));
    }
}
