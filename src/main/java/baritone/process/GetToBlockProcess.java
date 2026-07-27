package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IGetToBlockProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.cache.ServerWorldCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Live-world server adaptation of the original GetToBlockProcess. */
public final class GetToBlockProcess implements IGetToBlockProcess {
    private final Baritone baritone;
    private BlockOptionalMeta gettingTo;
    private List<BlockPos> knownLocations;
    private final List<BlockPos> blacklist = new ArrayList<>();
    private Goal currentGoal;
    private BlockPos start;
    private int ticks;
    private int arrivalTicks;

    public GetToBlockProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void getToBlock(BlockOptionalMeta block) {
        onLostControl();
        gettingTo = block;
        start = baritone.getPlayerContext().playerFeet();
        ServerWorldCache.registerTrackedBlocks(
                java.util.List.of(block.getBlock()));
        var feet = baritone.getPlayerContext().playerFeet();
        var chunk = baritone.getPlayerContext().world().getChunkSource()
                .getChunkNow(feet.x >> 4, feet.z >> 4);
        if (chunk != null) baritone.getWorldCache().capture(chunk);
        scanWorld();
    }

    public void serverTick() {
        if (!isActive()) {
            return;
        }
        int interval = Baritone.settings().mineGoalUpdateInterval.value;
        if (knownLocations == null || (interval != 0 && ticks++ % interval == 0)) {
            scanWorld();
        }
        if (knownLocations.isEmpty()) {
            if (Baritone.settings().exploreForBlocks.value) {
                currentGoal = explorationGoal();
                if (baritone.getPathExecutor() == null) {
                    baritone.pathToGoal(currentGoal, 2_000L, 8_000L);
                }
            }
            return;
        }
        currentGoal = new GoalComposite(knownLocations.stream()
                .map(this::createGoal).toArray(Goal[]::new));
        if (currentGoal.isInGoal(baritone.getPlayerContext().playerFeet())
                && currentGoal.isInGoal(baritone.getPathingBehavior().pathStart())) {
            baritone.cancelPath();
            if (rightClickOnArrival(gettingTo.getBlock())) {
                if (baritone.getFakeInteractionController()
                        .interactBlock(closestReachableLocation())
                        || arrivalTicks++ > 20) {
                    onLostControl();
                }
            } else {
                onLostControl();
            }
            return;
        }
        if (baritone.getPathExecutor() == null
                && !baritone.pathToGoal(currentGoal, 2_000L, 8_000L)) {
            if (Baritone.settings().blacklistClosestOnFailure.value && blacklistClosest()) {
                scanWorld();
            } else {
                onLostControl();
            }
        }
    }

    private Goal createGoal(BlockPos pos) {
        Block block = gettingTo.getBlock();
        if (Baritone.settings().enterPortal.value && block == Blocks.NETHER_PORTAL) {
            return new GoalTwoBlocks(pos);
        }
        if (blockOnTopMustBeRemoved(block)
                && !baritone.getPlayerContext().world()
                        .getBlockState(pos.above()).isAir()) {
            return new GoalBlock(pos.above());
        }
        return new GoalGetToBlock(pos);
    }

    private Goal explorationGoal() {
        return new GoalRunAway(1, start) {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return false;
            }

            @Override
            public double heuristic() {
                return Double.NEGATIVE_INFINITY;
            }
        };
    }

    private BlockPos closestReachableLocation() {
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        return knownLocations.stream()
                .filter(baritone.getFakeInteractionController()::canReach)
                .min(Comparator.comparingDouble(feet::distSqr))
                .orElseGet(() -> knownLocations.stream()
                        .min(Comparator.comparingDouble(feet::distSqr))
                        .orElse(feet));
    }

    private static boolean rightClickOnArrival(Block block) {
        if (!Baritone.settings().rightClickContainerOnArrival.value) {
            return false;
        }
        return block == Blocks.CRAFTING_TABLE
                || block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.ENDER_CHEST
                || block == Blocks.CHEST
                || block == Blocks.TRAPPED_CHEST;
    }

    private static boolean blockOnTopMustBeRemoved(Block block) {
        return rightClickOnArrival(block)
                && (block == Blocks.ENDER_CHEST
                        || block == Blocks.CHEST
                        || block == Blocks.TRAPPED_CHEST);
    }

    private void scanWorld() {
        BlockPos origin = baritone.getPlayerContext().playerFeet();
        List<BlockPos> found = new ArrayList<>();
        // Matches upstream GetToBlockProcess: durable points of interest found
        // in previously observed chunks supplement the live-world scan.
        found.addAll(baritone.getWorldCache().locationsOfNear(
                gettingTo.getBlock(), origin.getX(), origin.getZ(),
                baritone.getPlayerContext().server().getPlayerList()
                        .getViewDistance(),
                Math.max(128,
                        Baritone.settings().maxCachedWorldScanCount.value)));
        found = new ArrayList<>(new java.util.LinkedHashSet<>(found));
        found.removeAll(blacklist);
        found.sort(Comparator.comparingDouble(origin::distSqr));
        knownLocations = found.size() > 64
                ? new ArrayList<>(found.subList(0, 64)) : found;
    }

    @Override
    public boolean blacklistClosest() {
        if (knownLocations == null || knownLocations.isEmpty()) return false;
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        BlockPos closest = knownLocations.stream()
                .min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
        if (closest == null) return false;
        List<BlockPos> adjacent = knownLocations.stream()
                .filter(pos -> manhattan(pos, closest) <= 1).toList();
        blacklist.addAll(adjacent);
        knownLocations.removeAll(adjacent);
        return true;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return new PathingCommand(currentGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override public boolean isActive() { return gettingTo != null; }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        gettingTo = null;
        knownLocations = null;
        currentGoal = null;
        start = null;
        arrivalTicks = 0;
        blacklist.clear();
    }
    @Override public String displayName0() {
        return "Get To " + gettingTo + ", "
                + (knownLocations == null ? 0 : knownLocations.size()) + " known locations";
    }
}
