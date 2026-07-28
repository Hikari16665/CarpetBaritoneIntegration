package baritone.process;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class BuilderGoalTest {

    @Test
    public void breakGoalNeverChoosesUnsupportedPositionAboveTarget() {
        Goal goal = new BuilderProcess.GoalBreak(new BlockPos(10, 64, 10));
        assertTrue(goal.isInGoal(9, 64, 10));
        assertTrue(goal.isInGoal(10, 63, 10));
        assertFalse(goal.isInGoal(10, 64, 10));
        assertFalse(goal.isInGoal(10, 65, 10));
    }

    @Test
    public void adjacentGoalExcludesPlacementSupport() {
        Goal goal = new BuilderProcess.GoalAdjacent(
                new BlockPos(10, 64, 10),
                new BlockPos(9, 64, 10),
                true);
        assertFalse(goal.isInGoal(9, 64, 10));
        assertTrue(goal.isInGoal(11, 64, 10));
    }

    @Test
    public void placeGoalStandsAboveUnsupportedTarget() {
        Goal goal = new BuilderProcess.GoalPlace(new BlockPos(10, 64, 10));
        assertTrue(goal.isInGoal(10, 65, 10));
        assertFalse(goal.isInGoal(10, 64, 10));
    }

    @Test
    public void placementTargetCanNeverBeUsedAsPlayerFeet() {
        BlockPos target = new BlockPos(10, 64, 10);
        Goal unsupported = new BuilderProcess.GoalPlace(target);
        Goal supported = new BuilderProcess.GoalAdjacent(
                target, target.west(), true);

        assertFalse(unsupported.isInGoal(
                target.getX(), target.getY(), target.getZ()));
        assertFalse(supported.isInGoal(
                target.getX(), target.getY(), target.getZ()));
    }

    @Test
    public void placementCompositeDoesNotAcceptBlueprintInterior() {
        BlockPos first = new BlockPos(10, 64, 10);
        BlockPos second = first.east();
        Goal goal = new baritone.api.pathing.goals.GoalComposite(
                new BuilderProcess.GoalAdjacent(
                        first, second, true),
                new BuilderProcess.GoalAdjacent(
                        second, first, true));

        assertFalse(goal.isInGoal(
                first.getX(), first.getY(), first.getZ()));
        assertFalse(goal.isInGoal(
                second.getX(), second.getY(), second.getZ()));
        assertTrue(goal.isInGoal(10, 64, 9));
    }

    @Test
    public void builderStanceOnlyAcceptsExplicitValidatedPositions() {
        BlockPos target = new BlockPos(10, 64, 10);
        Goal goal = new BuilderProcess.GoalBuilderStance(
                target, List.of(target.north(), target.west()));

        assertTrue(goal.isInGoal(10, 64, 9));
        assertTrue(goal.isInGoal(9, 64, 10));
        assertFalse(goal.isInGoal(
                target.getX(), target.getY(), target.getZ()));
    }

    @Test
    public void primaryCompositeStillAcceptsFallbackGoal() {
        Goal goal = new BuilderProcess.JankyGoalComposite(
                new GoalBlock(1, 2, 3),
                new GoalBlock(4, 5, 6));
        assertTrue(goal.isInGoal(1, 2, 3));
        assertTrue(goal.isInGoal(4, 5, 6));
        assertFalse(goal.isInGoal(7, 8, 9));
    }

    @Test
    public void layerZeroIsEmptyAndLaterLayersAreCumulative() {
        assertArrayEquals(new int[] {0, -1},
                BuilderProcess.layerBounds(10, 0, 2, false));
        assertArrayEquals(new int[] {0, 3},
                BuilderProcess.layerBounds(10, 2, 2, false));
        assertArrayEquals(new int[] {10, 9},
                BuilderProcess.layerBounds(10, 0, 2, true));
        assertArrayEquals(new int[] {6, 9},
                BuilderProcess.layerBounds(10, 2, 2, true));
    }

    @Test
    public void breakFromAboveFallbackAvoidsStandingInsideAirAboveTarget() {
        Goal goal = new BuilderProcess.GoalBreakFromAboveFallback(
                new BlockPos(10, 64, 10));
        assertFalse(goal.isInGoal(10, 65, 10));
        assertTrue(goal.isInGoal(9, 65, 10));
        assertFalse(goal.isInGoal(9, 66, 10));
    }
}
