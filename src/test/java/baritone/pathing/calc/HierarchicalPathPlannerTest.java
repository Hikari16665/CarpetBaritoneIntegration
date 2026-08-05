package baritone.pathing.calc;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.Goal;
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
        assertTrue(plan.refinementGoal()
                instanceof HierarchicalPathPlanner.ChunkGoal);
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
    public void refinementGatewayAcceptsAnyEntryInTheNextChunk() {
        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(8, 64, 8),
                        new GoalBlock(1024, 64, 8), 1);

        assertNotNull(plan);
        assertFalse(plan.finalSegment());
        assertTrue(plan.refinementGoal()
                instanceof HierarchicalPathPlanner.ChunkGoal);
        HierarchicalPathPlanner.ChunkGoal gateway =
                (HierarchicalPathPlanner.ChunkGoal) plan.refinementGoal();
        assertEquals(1, gateway.chunkX());
        assertEquals(0, gateway.chunkZ());
        assertTrue(gateway.isInGoal(16, -64, 0));
        assertTrue(gateway.isInGoal(31, 320, 15));
        assertFalse(gateway.isInGoal(15, 64, 15));
        assertFalse(gateway.isInGoal(32, 64, 0));
    }

    @Test
    public void diagonalGatewayAndItsSnapshotChunkAreIncluded() {
        HierarchicalPathPlanner.Plan plan =
                new HierarchicalPathPlanner().plan(
                        new BetterBlockPos(15, 64, 15),
                        new GoalBlock(64, 64, 64), 1);

        assertNotNull(plan);
        assertFalse(plan.finalSegment());
        HierarchicalPathPlanner.ChunkGoal gateway =
                (HierarchicalPathPlanner.ChunkGoal) plan.refinementGoal();
        assertEquals(1, gateway.chunkX());
        assertEquals(1, gateway.chunkZ());
        assertTrue(gateway.isInGoal(16, 64, 16));
        long key = ((long) gateway.chunkX() << 32)
                ^ (gateway.chunkZ() & 0xffffffffL);
        assertTrue("The immutable worker view must contain the diagonal "
                        + "gateway selected by HPA",
                plan.corridorChunks().contains(key));
    }

}
