package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BuilderCalculationContextMigrationTest {
    @Test
    public void builderCostModelSurvivesAsyncCalculationAndExecution()
            throws Exception {
        String builder = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BuilderProcess.java"));
        String baritone = Files.readString(Path.of(
                "src", "main", "java", "baritone", "Baritone.java"));
        String executor = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerPathExecutor.java"));
        assertTrue(builder.contains("class BuilderCalculationContext"));
        assertTrue(builder.contains("breakCorrectBlockPenaltyMultiplier"));
        assertTrue(builder.contains("placeIncorrectBlockPenaltyMultiplier"));
        assertTrue(baritone.contains("builderProcess.calculationContext(goal)"));
        assertTrue(baritone.contains("CalculationContext calculationContext"));
        assertTrue(executor.contains("refreshCalculationContext(calculationContext)"));
    }
}
