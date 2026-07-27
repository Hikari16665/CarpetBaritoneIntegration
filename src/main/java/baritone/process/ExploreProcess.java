package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IExploreProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import com.google.gson.Gson;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server implementation of ExploreProcess. The live server's loaded chunks
 * replace Baritone's client cached-world database.
 */
public final class ExploreProcess implements IExploreProcess {
    private final Baritone baritone;
    private final Set<Long> explored = new HashSet<>();
    private Set<Long> jsonFilter;
    private boolean filterInvert;
    private BlockPos explorationOrigin;
    private int distanceCompleted;
    private Goal currentGoal;

    public ExploreProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void explore(int centerX, int centerZ) {
        explorationOrigin = new BlockPos(centerX, 0, centerZ);
        distanceCompleted = 0;
        explored.clear();
        currentGoal = null;
    }

    public void serverTick() {
        if (!isActive()) {
            return;
        }
        markVisibleChunksExplored();
        if (baritone.getPathExecutor() != null) {
            return;
        }
        if (!Baritone.settings().disableCompletionCheck.value
                && filterInvert && jsonFilter != null
                && jsonFilter.stream().allMatch(explored::contains)) {
            onLostControl();
            return;
        }
        Goal[] next = closestUnexploredChunks();
        if (next.length == 0) {
            onLostControl();
            return;
        }
        currentGoal = new GoalComposite(next);
        if (!baritone.pathToGoal(currentGoal, 2_000L, 8_000L)) {
            distanceCompleted++;
            currentGoal = null;
        }
    }

    private void markVisibleChunksExplored() {
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        int centerX = feet.getX() >> 4;
        int centerZ = feet.getZ() >> 4;
        int radius = baritone.getPlayerContext().server().getPlayerList().getViewDistance();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (baritone.getPlayerContext().world().getChunkSource()
                        .getChunkNow(centerX + dx, centerZ + dz) != null) {
                    long key = ChunkPos.asLong(centerX + dx, centerZ + dz);
                    explored.add(key);
                }
            }
        }
        explored.addAll(baritone.getWorldCache().observedChunkKeys());
    }

    private Goal[] closestUnexploredChunks() {
        int originChunkX = explorationOrigin.getX() >> 4;
        int originChunkZ = explorationOrigin.getZ() >> 4;
        int wanted = Baritone.settings().exploreChunkSetMinimumSize.value;
        List<Goal> goals = new ArrayList<>();
        for (int distance = distanceCompleted; goals.size() < wanted; distance++) {
            for (int dx = -distance; dx <= distance && goals.size() < wanted; dx++) {
                int dzAbs = distance - Math.abs(dx);
                for (int sign : new int[]{-1, 1}) {
                    int dz = dzAbs * sign;
                    int chunkX = originChunkX + dx;
                    int chunkZ = originChunkZ + dz;
                    long key = ChunkPos.asLong(chunkX, chunkZ);
                    if (!explored.contains(key) && !excludedByFilter(key)) {
                        goals.add(createGoal(
                                explorationTarget(chunkX, dx),
                                explorationTarget(chunkZ, dz)));
                    }
                    if (dzAbs == 0 || goals.size() >= wanted) {
                        break;
                    }
                }
            }
            if (goals.isEmpty()) {
                distanceCompleted = distance + 1;
            }
        }
        return goals.toArray(Goal[]::new);
    }

    /**
     * Mirrors Baritone's exploration offset. A positive offset deliberately
     * aims beyond the center of the first uncached chunk so that the server
     * loads a useful band of chunks before the next path recalculation.
     */
    private static int explorationTarget(int chunkCoordinate, int direction) {
        int target = (chunkCoordinate << 4) + 8;
        int offset = Baritone.settings().worldExploringChunkOffset.value << 4;
        if (direction < 0) {
            return target - offset;
        }
        if (direction > 0) {
            return target + offset;
        }
        return target;
    }

    private static Goal createGoal(int x, int z) {
        if (Baritone.settings().exploreMaintainY.value == -1) {
            return new GoalXZ(x, z);
        }
        return new GoalXZ(x, z) {
            @Override
            public double heuristic(int px, int py, int pz) {
                return super.heuristic(px, py, pz)
                        + GoalYLevel.calculate(Baritone.settings().exploreMaintainY.value, py);
            }
        };
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed) {
            // Do not terminate exploration because one frontier is blocked.
            // Advance the Manhattan ring and let the server scheduler choose
            // another frontier, matching the intent of upstream exploration.
            distanceCompleted++;
            currentGoal = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(currentGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void applyJsonFilter(Path path, boolean invert) {
        try (Reader reader = Files.newBufferedReader(path)) {
            MyChunkPos[] positions = new Gson().fromJson(reader, MyChunkPos[].class);
            Set<Long> loaded = new HashSet<>();
            if (positions != null) {
                for (MyChunkPos position : positions) {
                    loaded.add(ChunkPos.asLong(position.x, position.z));
                }
            }
            jsonFilter = loaded;
            filterInvert = invert;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to read chunk filter " + path + ": " + exception.getMessage(),
                    exception);
        }
    }

    private boolean excludedByFilter(long key) {
        if (jsonFilter == null) return false;
        // Original semantics: normal lists mark their entries explored; inverted
        // lists mark everything outside the list explored.
        return jsonFilter.contains(key) ^ filterInvert;
    }

    private static final class MyChunkPos {
        int x;
        int z;
    }

    @Override public boolean isActive() { return explorationOrigin != null; }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() { explorationOrigin = null; currentGoal = null; }
    @Override public String displayName0() {
        return "Exploring around " + explorationOrigin + ", distance completed " + distanceCompleted;
    }
}
