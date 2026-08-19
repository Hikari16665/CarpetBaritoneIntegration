package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.ICleanProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.server.OverloadModeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Strict top-down selection cleaner. Every mutation is followed by a fresh
 * scan, so flowing fluids and falling blocks cannot be skipped.
 */
public final class CleanProcess implements ICleanProcess {
    /** Number of vertical cells processed per local top-down work column. */
    private static final int SAFE_MULTI_LAYER_DEPTH = 3;

    private enum Phase { SEAL_FLUID, BREAK_BLOCK, REMOVE_SUPPORT }

    private final Baritone baritone;
    private final Set<BlockPos> placedSupports = new LinkedHashSet<>();
    private final Deque<BlockPos> fluidWorkQueue = new ArrayDeque<>();
    private final Deque<BlockPos> layerWorkQueue = new ArrayDeque<>();
    private final Set<Long> theoreticalBreakStances =
            new LinkedHashSet<>();
    private Consumer<String> feedback = ignored -> { };
    private BlockPos min;
    private BlockPos max;
    private BlockPos target;
    private Goal currentGoal;
    private BlockPos interactionStance;
    private boolean sealingFluids = true;
    private Phase phase = Phase.BREAK_BLOCK;
    private int y;
    private int cleared;
    private int sealed;
    private int failedPaths;
    private int targetTicks;
    private Vec3 lastRoutePosition;
    private int lastRoutePathIndex = -1;
    private int stagnantRouteTicks;

    public CleanProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    public BlockPos selectionMin() {
        return min == null ? null : min.immutable();
    }

    public BlockPos selectionMax() {
        return max == null ? null : max.immutable();
    }

    /**
     * Fluid isolation deliberately extends by exactly one block on every
     * axis. These bounds are not exposed as the clean selection: movement may
     * interact with fluid plugs here, but block breaking remains restricted
     * to {@link #selectionMin()}..{@link #selectionMax()}.
     */
    BlockPos fluidSealMin() {
        return min == null ? null : min.offset(-1, -1, -1);
    }

    BlockPos fluidSealMax() {
        return max == null ? null : max.offset(1, 1, 1);
    }

    @Override
    public void clean(ISelection selection, Consumer<String> feedback) {
        onLostControl();
        min = selection.min().immutable();
        max = selection.max().immutable();
        y = max.getY();
        sealingFluids = true;
        fluidWorkQueue.clear();
        layerWorkQueue.clear();
        this.feedback = feedback == null ? ignored -> { } : feedback;
        baritone.getStatusMessenger().beginTask("清空选区");
        diagnostic("fluid seal bounds=" + fluidSealMin()
                + ".." + fluidSealMax() + " selection=" + min
                + ".." + max);
    }

    public void serverTick() {
        if (!OverloadModeManager.INSTANCE.isEnabled(baritone)) {
            serverTickOnce();
            return;
        }
        long deadline = System.nanoTime() + 35_000_000L;
        int operations = 0;
        int previousCleared = cleared;
        int previousSealed = sealed;
        while (isActive() && operations++ < 8192
                && System.nanoTime() < deadline) {
            int beforeCleared = cleared;
            int beforeSealed = sealed;
            BlockPos beforeTarget = target;
            serverTickOnce();
            if (baritone.isPathing()) break;
            if (beforeCleared == cleared && beforeSealed == sealed
                    && java.util.Objects.equals(beforeTarget, target)) {
                break;
            }
        }
        int changed = cleared - previousCleared + sealed - previousSealed;
        if (changed > 0) {
            System.out.println("[CBI-OVERLOAD] clean player="
                    + baritone.getPlayerContext().player()
                            .getScoreboardName()
                    + " operations=" + changed + " phase=" + phase);
        }
    }

    private void serverTickOnce() {
        if (!isActive()) return;
        if (Baritone.settings().diagnosticLogging.value
                && baritone.getPlayerContext().world().getGameTime() % 20L
                        == 0L) {
            System.out.println("[CBI-DIAG] clean player="
                    + baritone.getPlayerContext().player()
                            .getScoreboardName()
                    + " phase=" + phase + " target=" + target
                    + " feet=" + baritone.getPlayerContext().playerFeet()
                    + " pathing=" + baritone.isPathing()
                    + " withinReach=" + (target != null
                            && withinReach(target))
                    + " canBreak=" + (target != null
                            && baritone.getFakeInteractionController()
                                    .canBreakFromHere(target)));
        }
        if (baritone.isPathing()) {
            if (cancelStagnantApproach()) return;
            // A completed executor/calculation may survive for one or more
            // control ticks. Once the target is physically reachable, stop
            // that route and interact now instead of waiting forever.
            if (target == null || !canPerformCurrentInteraction()) return;
            diagnostic("arrived stance="
                    + baritone.getPlayerContext().playerFeet()
                    + " theoretical=" + theoreticalBreakStances.contains(
                            baritone.getPlayerContext().playerFeet().asLong())
                    + ", entering interaction mode");
            interactionStance = baritone.getPlayerContext()
                    .playerFeet().immutable();
            // onTick runs before serverTick. Leaving the approach goal set
            // here would make the process scheduler immediately start a new
            // route on the next tick while timed mining is in progress.
            currentGoal = null;
            baritone.cancelPath();
        }
        if (target != null && ++targetTicks > 400) {
            // Retain the target, but force a fresh approach calculation.
            failedPaths = 0;
            targetTicks = 0;
            currentGoal = null;
            updateApproachGoal();
            return;
        }
        if (target == null) {
            findHighestTarget();
            if (!isActive() || target == null) return;
        }
        BlockState state = baritone.getPlayerContext().world()
                .getBlockState(target);
        if (state.isAir()) {
            placedSupports.remove(target);
            advanceTarget();
            return;
        }
        if (phase == Phase.SEAL_FLUID) {
            if (state.getFluidState().isEmpty()) {
                // The fluid disappeared or was replaced while approaching.
                // Leave any resulting solid for the later global break phase.
                advanceTarget();
                return;
            }
            if (!withinReach(target)) {
                ensureApproachGoal();
                return;
            }
            sealFluid();
            return;
        }
        if (!state.getFluidState().isEmpty()) {
            sealingFluids = true;
            phase = Phase.SEAL_FLUID;
            return;
        }
        boolean theoretical = theoreticalBreakStances.contains(
                baritone.getPlayerContext().playerFeet().asLong())
                || sameCoordinates(baritone.getPlayerContext().playerFeet(),
                        interactionStance);
        if (!withinReach(target)
                || unsafeCurrentWorkStance(target)
                || !theoretical
                && !baritone.getFakeInteractionController()
                        .canBreakFromHere(target)) {
            interactionStance = null;
            ensureApproachGoal();
            return;
        }
        // A path can finish naturally between executor.tick() and this
        // process tick, bypassing the pathing branch above. Enter the same
        // stable interaction mode here so timed mining cannot restart the
        // completed approach on the following tick.
        if (interactionStance == null) {
            interactionStance = baritone.getPlayerContext()
                    .playerFeet().immutable();
            currentGoal = null;
        }
        if (baritone.getPlayerContext().world().getGameTime() % 20L == 0L) {
            diagnostic("interaction target=" + target
                    + " stance=" + baritone.getPlayerContext().playerFeet()
                    + " theoretical=" + theoretical);
        }
        boolean broken = theoretical
                ? baritone.getFakeInteractionController()
                        .breakBlockTheoreticallyReachable(target)
                : baritone.getFakeInteractionController()
                        .breakBlock(target);
        if (broken) {
            placedSupports.remove(target);
            cleared++;
            advanceTarget();
        }
    }

    private void findHighestTarget() {
        if (sealingFluids) {
            if (fluidWorkQueue.isEmpty()) refillFluidQueue();
            while (!fluidWorkQueue.isEmpty()) {
                BlockPos pos = fluidWorkQueue.removeFirst();
                if (!baritone.getPlayerContext().world()
                        .getBlockState(pos).getFluidState().isEmpty()) {
                    assign(pos, pos.getY(), Phase.SEAL_FLUID);
                    return;
                }
            }
            // A full empty rescan is the barrier between sealing and
            // excavation. Flowing updates can send the machine back here.
            refillFluidQueue();
            if (!fluidWorkQueue.isEmpty()) {
                findHighestTarget();
                return;
            }
            sealingFluids = false;
            layerWorkQueue.clear();
            y = max.getY();
            diagnostic("all fluids sealed, beginning top-down break phase");
        }

        while (y >= min.getY()) {
            if (layerWorkQueue.isEmpty()) refillLayerQueue(y);
            while (!layerWorkQueue.isEmpty()) {
                BlockPos pos = layerWorkQueue.removeFirst();
                BlockState state = baritone.getPlayerContext().world()
                        .getBlockState(pos);
                if (state.isAir()) continue;
                if (!state.getFluidState().isEmpty()) {
                    sealingFluids = true;
                    fluidWorkQueue.clear();
                    layerWorkQueue.clear();
                    assign(pos, pos.getY(), Phase.SEAL_FLUID);
                    return;
                }
                assign(pos, y, Phase.BREAK_BLOCK);
                return;
            }
            // Verify after the sweep. Falling blocks or neighbour updates may
            // have repopulated this layer while the queue was processed.
            refillLayerQueue(y);
            if (!layerWorkQueue.isEmpty()) {
                diagnostic("layer " + y
                        + " changed during sweep; running verification pass");
                return;
            }
            diagnostic("layer " + y + " cleared; descending safely");
            y--;
        }
        BlockPos support = placedSupports.stream()
                .filter(pos -> !baritone.getPlayerContext().world()
                        .getBlockState(pos).isAir())
                .max(Comparator.comparingInt(BlockPos::getY))
                .orElse(null);
        if (support != null) {
            assign(support, support.getY(), Phase.REMOVE_SUPPORT);
            return;
        }
        String completion = "选区清理完成：破坏 " + cleared
                + " 个方块，封堵 " + sealed + " 个流体格";
        feedback.accept(completion);
        baritone.getStatusMessenger().taskComplete(completion);
        onLostControl();
    }

    /** Top-down, boustrophedon fluid queue over the one-block seal shell. */
    private void refillFluidQueue() {
        fluidWorkQueue.clear();
        BlockPos sealMin = fluidSealMin();
        BlockPos sealMax = fluidSealMax();
        for (int checkY = sealMax.getY();
                checkY >= sealMin.getY(); checkY--) {
            for (int z = sealMin.getZ(); z <= sealMax.getZ(); z++) {
                boolean reverse = ((z - sealMin.getZ())
                        + (sealMax.getY() - checkY) & 1) != 0;
                int startX = reverse ? sealMax.getX() : sealMin.getX();
                int endX = reverse ? sealMin.getX() : sealMax.getX();
                int step = reverse ? -1 : 1;
                for (int x = startX;; x += step) {
                    BlockPos pos = new BlockPos(x, checkY, z);
                    if (!baritone.getPlayerContext().world()
                            .getBlockState(pos).getFluidState().isEmpty()) {
                        fluidWorkQueue.addLast(pos);
                    }
                    if (x == endX) break;
                }
            }
        }
    }

    /**
     * A back-and-forth work window. Each horizontal stop contributes a short
     * top-down column, so one safe stance can clear several vertical cells
     * before the actor walks away. The global frontier still descends from the
     * top, and every candidate is revalidated immediately before interaction.
     */
    private void refillLayerQueue(int layer) {
        layerWorkQueue.clear();
        for (int z = min.getZ(); z <= max.getZ(); z++) {
            boolean reverse = ((z - min.getZ())
                    + (max.getY() - layer) & 1) != 0;
            int startX = reverse ? max.getX() : min.getX();
            int endX = reverse ? min.getX() : max.getX();
            int step = reverse ? -1 : 1;
            for (int x = startX;; x += step) {
                int lowest = Math.max(min.getY(),
                        layer - SAFE_MULTI_LAYER_DEPTH + 1);
                for (int workY = layer; workY >= lowest; workY--) {
                    BlockPos pos = new BlockPos(x, workY, z);
                    if (!baritone.getPlayerContext().world()
                            .getBlockState(pos).isAir()) {
                        layerWorkQueue.addLast(pos);
                    }
                }
                if (x == endX) break;
            }
        }
    }

    private void assign(BlockPos pos, int layer, Phase nextPhase) {
        target = pos.immutable();
        y = layer;
        phase = nextPhase;
        updateApproachGoal();
    }

    private void sealFluid() {
        boolean creativeOverload = OverloadModeManager.INSTANCE
                .isEnabled(baritone)
                && baritone.getPlayerContext().player()
                        .getAbilities().instabuild;
        if (!creativeOverload
                && !baritone.getInventoryController()
                        .selectThrowawayForLocation(
                                true, target.getX(), target.getY(),
                                target.getZ())) {
            feedback.accept("没有可用于清除流体的完整垫脚方块，清理已停止");
            baritone.getStatusMessenger().noBridgeBlocks(true);
            onLostControl();
            return;
        }
        if (baritone.getFakeInteractionController()
                .fillFluidWithSelectedBlock(target)) {
            sealed++;
            // Keep the plug in place. The entire selection is sealed before
            // any block, including this one, is broken.
            advanceTarget();
        } else {
            updateApproachGoal();
        }
    }

    private void advanceTarget() {
        target = null;
        currentGoal = null;
        interactionStance = null;
        theoreticalBreakStances.clear();
        failedPaths = 0;
        targetTicks = 0;
        resetRouteProgress();
    }

    /**
     * The generic movement timeout includes the movement's estimated cost,
     * which can be very large. Replan earlier when an approach executor
     * neither advances its path index nor physically moves. Legitimate
     * stationary block breaking is exempt.
     */
    private boolean cancelStagnantApproach() {
        var executor = baritone.getPathExecutor();
        if (executor == null) {
            resetRouteProgress();
            return false;
        }
        Vec3 position = baritone.getPlayerContext().player().position();
        int pathIndex = executor.getPosition();
        boolean progressed = lastRoutePosition == null
                || position.distanceToSqr(lastRoutePosition) > 0.01D
                || pathIndex != lastRoutePathIndex;
        if (progressed) {
            lastRoutePosition = position;
            lastRoutePathIndex = pathIndex;
            stagnantRouteTicks = 0;
            return false;
        }
        if (baritone.getInputController().hasActiveBreakTarget()) {
            stagnantRouteTicks = 0;
            return false;
        }
        if (++stagnantRouteTicks < 60) return false;
        diagnostic("stagnant pathIndex=" + pathIndex + " position="
                + position + ", cancelling executor and recalculating");
        baritone.cancelPath();
        resetRouteProgress();
        return true;
    }

    private void resetRouteProgress() {
        lastRoutePosition = null;
        lastRoutePathIndex = -1;
        stagnantRouteTicks = 0;
    }

    private void updateApproachGoal() {
        if (target == null) {
            currentGoal = null;
        } else if (phase == Phase.SEAL_FLUID) {
            currentGoal = new GoalWithinInteractionReach(target);
        } else {
            currentGoal = breakApproachGoal(target);
        }
    }

    /**
     * Keep one immutable approach goal for the current target. Rebuilding a
     * large GoalComposite every server tick changes the process goal identity
     * while its executor is still walking and can cause needless
     * revalidation/recalculation near the destination.
     */
    private void ensureApproachGoal() {
        if (currentGoal == null) updateApproachGoal();
    }

    private void diagnostic(String message) {
        if (Baritone.settings().diagnosticLogging.value) {
            System.out.println("[CBI-DIAG] clean-transition player="
                    + baritone.getPlayerContext().player()
                            .getScoreboardName()
                    + " " + message);
        }
    }

    private boolean canPerformCurrentInteraction() {
        if (!withinReach(target) || unsafeCurrentWorkStance(target)) {
            return false;
        }
        return phase == Phase.SEAL_FLUID
                || baritone.getFakeInteractionController()
                        .canBreakFromHere(target)
                || theoreticalBreakStances.contains(
                        baritone.getPlayerContext().playerFeet().asLong())
                || sameCoordinates(baritone.getPlayerContext().playerFeet(),
                        interactionStance);
    }

    /**
     * Enumerates standable nodes whose eye ray actually reaches the target.
     * A plain distance goal can finish behind a wall, while fake breaking
     * correctly refuses that occluded interaction.
     */
    private Goal breakApproachGoal(BlockPos block) {
        var world = baritone.getPlayerContext().world();
        double reach = RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
        double reachSq = reach * reach;
        ArrayList<Goal> visibleStances = new ArrayList<>();
        theoreticalBreakStances.clear();
        int radius = (int) Math.ceil(reach);
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos feet = block.offset(dx, dy, dz);
                    Vec3 eye = new Vec3(feet.getX() + 0.5D,
                            feet.getY() + 1.62D,
                            feet.getZ() + 0.5D);
                    if (eye.distanceToSqr(Vec3.atCenterOf(block)) > reachSq
                            || sameCoordinates(feet.below(), block)
                            || sameCoordinates(feet, block)
                            || sameCoordinates(feet.above(), block)
                            || !standable(feet)) {
                        continue;
                    }
                    if (hypotheticalCanSee(eye, block)) {
                        visibleStances.add(new GoalBlock(feet));
                        theoreticalBreakStances.add(feet.asLong());
                    }
                }
            }
        }
        if (Baritone.settings().diagnosticLogging.value) {
            System.out.println("[CBI-DIAG] clean-approach player="
                    + baritone.getPlayerContext().player()
                            .getScoreboardName()
                    + " target=" + block
                    + " visibleStances=" + visibleStances.size());
        }
        return visibleStances.isEmpty()
                ? new GoalWithinInteractionReach(block)
                : new GoalComposite(visibleStances.toArray(Goal[]::new));
    }

    private boolean hypotheticalCanSee(Vec3 eye, BlockPos block) {
        Vec3[] samples = {
                Vec3.atCenterOf(block),
                Vec3.atCenterOf(block).add(0.499D, 0D, 0D),
                Vec3.atCenterOf(block).add(-0.499D, 0D, 0D),
                Vec3.atCenterOf(block).add(0D, 0.499D, 0D),
                Vec3.atCenterOf(block).add(0D, -0.499D, 0D),
                Vec3.atCenterOf(block).add(0D, 0D, 0.499D),
                Vec3.atCenterOf(block).add(0D, 0D, -0.499D)
        };
        var world = baritone.getPlayerContext().world();
        for (Vec3 sample : samples) {
            HitResult hit = world.clip(new ClipContext(
                    eye, sample, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    baritone.getPlayerContext().player()));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            if (hit instanceof BlockHitResult blockHit
                    && hit.getType() == HitResult.Type.BLOCK
                    && blockHit.getBlockPos().equals(block)) {
                return true;
            }
        }
        return false;
    }

    private boolean standable(BlockPos feet) {
        var world = baritone.getPlayerContext().world();
        BlockState atFeet = world.getBlockState(feet);
        BlockState atHead = world.getBlockState(feet.above());
        BlockState support = world.getBlockState(feet.below());
        return atFeet.getCollisionShape(world, feet).isEmpty()
                && atHead.getCollisionShape(world, feet.above()).isEmpty()
                && !support.getCollisionShape(world, feet.below()).isEmpty();
    }

    private boolean unsafeCurrentWorkStance(BlockPos block) {
        if (block == null) return false;
        BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
        return sameCoordinates(block, feet)
                || sameCoordinates(block, feet.above())
                || sameCoordinates(block, feet.below());
    }

    private static boolean sameCoordinates(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private boolean withinReach(BlockPos pos) {
        // Match the fake interaction controller and vanilla block reach:
        // distance is measured to the nearest point of the block volume, not
        // its center. Center distance incorrectly rejects diagonal/downward
        // fluid cells whose surface is already reachable.
        return baritone.getFakeInteractionController().canReach(pos);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean safeToCancel) {
        if (calcFailed && ++failedPaths >= 3) {
            // Clean must never blacklist/skip a block. Recalculate from the
            // same target with block placement still enabled.
            failedPaths = 0;
            currentGoal = null;
            updateApproachGoal();
        }
        // Do not submit a zero-length approach calculation for a target that
        // can already be handled from the current safe stance. In overload
        // mode that calculation used to be created and cancelled later in the
        // same tick, producing an unbounded worker/revalidation loop.
        if (!baritone.isPathing() && currentGoal != null && target != null
                && canPerformCurrentInteraction()) {
            interactionStance = baritone.getPlayerContext()
                    .playerFeet().immutable();
            currentGoal = null;
        }
        return new PathingCommand(currentGoal,
                currentGoal == null ? PathingCommandType.REQUEST_PAUSE
                        : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /** Records clean-owned pillars/bridges so they are dismantled at the end. */
    public void recordPlacedSupport(BlockPos pos) {
        if (isActive() && pos != null) placedSupports.add(pos.immutable());
    }

    @Override public boolean isActive() { return min != null; }
    @Override public boolean isTemporary() { return false; }

    @Override
    public void onLostControl() {
        min = null;
        max = null;
        target = null;
        currentGoal = null;
        interactionStance = null;
        placedSupports.clear();
        fluidWorkQueue.clear();
        layerWorkQueue.clear();
        theoreticalBreakStances.clear();
        cleared = 0;
        sealed = 0;
        failedPaths = 0;
        targetTicks = 0;
        sealingFluids = true;
        resetRouteProgress();
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "Clean selection y=" + y + " " + phase;
    }

    private static final class GoalWithinInteractionReach
            implements Goal, IGoalRenderPos {
        private static final double EYE_HEIGHT = 1.62D;
        private static final double REACH =
                RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
        private final BetterBlockPos target;

        private GoalWithinInteractionReach(BlockPos target) {
            this.target = BetterBlockPos.from(target);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            double eyeY = y + EYE_HEIGHT;
            // Path nodes represent a whole standing block, while the
            // player's actual X/Z can be anywhere inside it. Use the
            // farthest edge of that standing cell so reaching this goal
            // guarantees the real player position can interact as well.
            double dx = farthestDistance(
                    x, x + 1.0D, target.x, target.x + 1.0D);
            double dy = eyeY - Math.max(target.y,
                    Math.min(eyeY, target.y + 1.0D));
            double dz = farthestDistance(
                    z, z + 1.0D, target.z, target.z + 1.0D);
            return dx * dx + dy * dy + dz * dz <= REACH * REACH;
        }

        private static double farthestDistance(
                double fromMin, double fromMax,
                double targetMin, double targetMax) {
            double fromMinDistance = fromMin - Math.max(targetMin,
                    Math.min(fromMin, targetMax));
            double fromMaxDistance = fromMax - Math.max(targetMin,
                    Math.min(fromMax, targetMax));
            return Math.max(Math.abs(fromMinDistance),
                    Math.abs(fromMaxDistance));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isInGoal(x, y, z)) return 0.0D;
            return Math.max(0.0D, GoalBlock.calculate(
                    x - target.x, y - target.y, z - target.z)
                    - REACH * 3.563D);
        }

        @Override public BlockPos getGoalPos() { return target; }
    }
}
