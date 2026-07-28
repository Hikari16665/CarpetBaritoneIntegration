package baritone.pathing.calc;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.process.BuilderProcess;
import org.junit.Test;

import static org.junit.Assert.*;

public class HierarchicalPathPlannerTest {
    @Test
    public void longPathIsSplitIntoBoundedChunkSegment() {
        BetterBlockPos start = new BetterBlockPos(0, 64, 0);
        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        start, new GoalBlock(4096, 70, 2048), 1);
        assertNotNull(plan);
        assertFalse(plan.finalSegment());
        assertTrue(plan.regions().size() > 1);
        assertTrue(plan.chunks().size() > 8);
        assertFalse(plan.clusters().isEmpty());
        assertTrue(plan.corridor().contains(0, 0));
        assertFalse(plan.corridor().contains(-4096, 4096));
        assertFalse(plan.refinementGoal().isInGoal(4096, 70, 2048));
        GoalXZ gateway = (GoalXZ) plan.refinementGoal();
        assertTrue(Math.abs((gateway.getX() >> 4)) <= 1);
        assertTrue(Math.abs((gateway.getZ() >> 4)) <= 1);
    }

    @Test
    public void nearbyGoalGoesStraightToBlockRefinement() {
        GoalBlock goal = new GoalBlock(8, 70, 8);
        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(0, 64, 0), goal, 1);
        assertNotNull(plan);
        assertTrue(plan.finalSegment());
        assertSame(goal, plan.refinementGoal());
        assertTrue(plan.corridor().contains(Integer.MAX_VALUE,
                Integer.MIN_VALUE));
    }

    @Test
    public void hierarchyHandlesNegativeChunkCoordinates() {
        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(-17, 64, -17),
                        new GoalBlock(-5000, 64, -3000), 2);
        assertNotNull(plan);
        assertEquals(-2, plan.chunks().getFirst().x());
        assertEquals(-2, plan.chunks().getFirst().z());
        assertTrue(plan.corridor().contains(-17, -17));
    }

    @Test
    public void builderCompositeCanBePlannedFromItsPrimaryGoal() {
        Goal primary = new GoalBlock(640, 70, 160);
        Goal fallback = new GoalBlock(644, 70, 164);
        Goal composite =
                new BuilderProcess.JankyGoalComposite(primary, fallback);

        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(0, 64, 0), composite, 1);

        assertNotNull("Builder composite goals must enter HPA refinement",
                plan);
        assertFalse(plan.chunks().isEmpty());
    }

    @Test
    public void unknownGoalIsReportedAsUnplannableForCallerFallback() {
        Goal unknown = new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return x == 1000;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                return Math.abs(1000 - x);
            }
        };

        assertNull("A null plan is the signal for HybridPathFinder to use "
                        + "unbounded block A*",
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(0, 64, 0), unknown, 1));
    }

    @Test
    public void corridorRadiusIncludesChunksBesideAbstractEdge() {
        BetterBlockPos start = new BetterBlockPos(8, 64, 8);
        GoalBlock goal = new GoalBlock(1024, 64, 8);
        HierarchicalPathPlanner.Plan narrow =
                new HierarchicalPathPlanner().plan(start, goal, 0);
        HierarchicalPathPlanner.Plan widened =
                new HierarchicalPathPlanner().plan(start, goal, 1);

        assertNotNull(narrow);
        assertNotNull(widened);
        assertFalse(narrow.corridor().contains(8, 24));
        assertTrue("A widened refinement must be able to detour through "
                        + "the chunk beside the abstract edge",
                widened.corridor().contains(8, 24));
    }

    @Test
    public void refinementGatewayIsOnSharedChunkBoundaryNotChunkCenter() {
        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(8, 64, 8),
                        new GoalBlock(1024, 64, 8), 1);

        assertNotNull(plan);
        assertFalse(plan.finalSegment());
        assertTrue(plan.refinementGoal() instanceof GoalXZ);
        GoalXZ gateway = (GoalXZ) plan.refinementGoal();
        assertEquals("Eastbound refinement should stop at the entrance "
                + "of the adjacent chunk", 16, gateway.getX());
        assertTrue(gateway.getZ() >= 0 && gateway.getZ() < 16);
    }
}
