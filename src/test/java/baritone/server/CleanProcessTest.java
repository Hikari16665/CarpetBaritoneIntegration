package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class CleanProcessTest {
    @Test
    public void commandsUsePerBaritoneCuboidSelection()
            throws IOException {
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        String baritone = Files.readString(Path.of(
                "src", "main", "java", "baritone", "Baritone.java"));
        assertTrue(handler.contains("case \"pos1\""));
        assertTrue(handler.contains("case \"pos2\""));
        assertTrue(handler.contains("case \"clean\""));
        assertTrue(baritone.contains("selectionPos1"));
        assertTrue(baritone.contains("selectionPos2"));
        assertTrue(baritone.contains("new CleanProcess(this)"));
    }

    @Test
    public void cleanRunsTopDownSealsLiquidsAndAvoidsFluidPaths()
            throws IOException {
        String process = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "CleanProcess.java"));
        String context = Files.readString(Path.of(
                "src", "main", "java", "baritone", "pathing",
                "movement", "CalculationContext.java"));
        assertTrue(process.contains(
                "SEAL_FLUIDS, BREAK_LAYER"));
        assertTrue(process.contains("y = max.getY()"));
        assertTrue(process.contains("y--"));
        assertTrue(process.contains("placeIntoFluid()"));
        assertTrue(process.contains("开始第二次清理"));
        assertTrue(context.contains("cleanMin != null"));
        assertTrue(context.contains("return COST_INF"));
        assertTrue(process.contains(
                "new GoalWithinInteractionReach(target)"));
        assertTrue(process.contains(
                "y + EYE_HEIGHT - (target.y + 0.5D)"));
        assertTrue(!process.contains("GoalComposite"));
        assertTrue(!context.contains("avoidFluidOccupancy"));
    }

    @Test
    public void fluidPlacementAimsThenVerifiesBeforeUseAndKeepsTrash()
            throws IOException {
        String process = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "CleanProcess.java"));
        String baritone = Files.readString(Path.of(
                "src", "main", "java", "baritone", "Baritone.java"));
        assertTrue(process.contains(
                "getFakeInteractionController()"));
        assertTrue(process.contains(
                "fillFluidWithSelectedBlock(target)"));
        assertTrue(process.contains("breakBlock(target)"));
        assertTrue(baritone.contains(
                "if (cleanProcess.isActive())"));
        assertTrue(baritone.contains(
                "trashDiscardController.clear()"));
    }
}
