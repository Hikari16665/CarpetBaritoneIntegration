package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class CleanProcessTest {
    @Test
    public void commandsUsePerBaritoneCuboidSelection() throws Exception {
        String handler = read("baritone/server/BasicGoalCommandHandler.java");
        String baritone = read("baritone/Baritone.java");
        assertTrue(handler.contains("case \"pos1\""));
        assertTrue(handler.contains("case \"pos2\""));
        assertTrue(handler.contains("case \"clean\""));
        assertTrue(baritone.contains("selectionPos1"));
        assertTrue(baritone.contains("selectionPos2"));
        assertTrue(baritone.contains("new CleanProcess(this)"));
    }

    @Test
    public void cleanRescansTopDownAndRemovesItsSupports() throws Exception {
        String process = read("baritone/process/CleanProcess.java");
        String context = read("baritone/pathing/movement/CalculationContext.java");
        assertTrue(process.contains(
                "SEAL_FLUID, BREAK_BLOCK, REMOVE_SUPPORT"));
        assertTrue(process.contains("checkY--"));
        assertTrue(process.contains("findHighestTarget()"));
        assertTrue(process.contains("sealFluid()"));
        assertTrue(process.contains("recordPlacedSupport"));
        assertTrue(process.contains("breakBlock(target)"));
        assertTrue(process.contains("baritone.cancelPath()"));
        assertTrue(process.contains("interactionStance"));
        assertTrue(process.contains("currentGoal = null;"));
        assertTrue(context.contains("cleanMin != null"));
        assertTrue(context.contains("return COST_INF"));
        assertTrue(process.contains("new GoalWithinInteractionReach(target)"));
        assertTrue(process.contains("farthestDistance("));
        assertTrue(process.contains("breakApproachGoal(target)"));
        assertTrue(process.contains("new GoalComposite"));
        assertTrue(process.contains("canBreakFromHere(target)"));
        assertTrue(process.contains("world.clip(new ClipContext"));
        assertTrue(process.contains("[CBI-DIAG] clean"));
    }

    @Test
    public void fluidFillCompletesGloballyBeforeTimedBreak() throws Exception {
        String process = read("baritone/process/CleanProcess.java");
        String baritone = read("baritone/Baritone.java");
        assertTrue(process.contains("fillFluidWithSelectedBlock(target)"));
        assertTrue(process.contains("sealingFluids"));
        assertTrue(process.contains(
                "all fluids sealed, beginning top-down break phase"));
        assertTrue(process.contains("advanceTarget();"));
        assertTrue(process.contains(
                "getFakeInteractionController().canReach(pos)"));
        assertTrue(baritone.contains(
                "boolean suppressTrashDiscard = cleanProcess.isActive()"));
        assertTrue(baritone.contains("trashDiscardController.clear()"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src", "main", "java")
                .resolve(relative));
    }
}
