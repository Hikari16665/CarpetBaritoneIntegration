package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.api.process.ICleanProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Clears a cuboid top-down. Each layer seals liquid cells with throwaway
 * blocks before any blocks in that layer are broken.
 */
public final class CleanProcess implements ICleanProcess {
    private enum Phase { SEAL_FLUIDS, BREAK_LAYER }

    private final Baritone baritone;
    private Consumer<String> feedback = ignored -> { };
    private BlockPos min;
    private BlockPos max;
    private int y;
    private int cursor;
    private Phase phase;
    private BlockPos target;
    private boolean targetWasFluid;
    private Goal currentGoal;
    private int cleared;
    private int sealed;
    private int failedPaths;
    private int targetTicks;
    private int verificationPass;

    public CleanProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    public BlockPos selectionMin() {
        return min == null ? null : min.immutable();
    }

    public BlockPos selectionMax() {
        return max == null ? null : max.immutable();
    }

    @Override
    public void clean(ISelection selection, Consumer<String> feedback) {
        onLostControl();
        this.min = selection.min().immutable();
        this.max = selection.max().immutable();
        this.y = max.getY();
        this.phase = Phase.SEAL_FLUIDS;
        this.feedback = feedback == null ? ignored -> { } : feedback;
    }

    public void serverTick() {
        if (!isActive() || baritone.isPathing()) return;
        if (target != null && ++targetTicks > 400) {
            feedback.accept("方块处理超时，已跳过: " + target);
            advanceTarget();
            return;
        }
        if (target == null) {
            findNextTarget();
            if (!isActive() || target == null) return;
        }
        BlockState state = baritone.getPlayerContext().world()
                .getBlockState(target);
        if (phase == Phase.SEAL_FLUIDS) {
            if (state.getFluidState().isEmpty()) {
                advanceTarget();
                return;
            }
            if (!withinReach(target)) {
                updateApproachGoal();
                return;
            }
            placeIntoFluid();
            return;
        }
        if (state.isAir()) {
            cleared++;
            advanceTarget();
            return;
        }
        // A fluid can reform after the seal pass. Seal it again before
        // breaking so the fake player never enters it.
        if (!state.getFluidState().isEmpty()) {
            targetWasFluid = true;
            if (!withinReach(target)) {
                updateApproachGoal();
                return;
            }
            placeIntoFluid();
            return;
        }
        if (!withinReach(target)) {
            updateApproachGoal();
            return;
        }
        breakTarget(state);
    }

    private void findNextTarget() {
        int width = max.getX() - min.getX() + 1;
        int length = max.getZ() - min.getZ() + 1;
        int layerSize = width * length;
        while (y >= min.getY()) {
            while (cursor < layerSize) {
                int index = cursor++;
                int x = min.getX() + index % width;
                int z = min.getZ() + index / width;
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = baritone.getPlayerContext().world()
                        .getBlockState(pos);
                boolean wanted = phase == Phase.SEAL_FLUIDS
                        ? !state.getFluidState().isEmpty()
                        : !state.isAir();
                if (wanted) {
                    target = pos;
                    targetWasFluid = !state.getFluidState().isEmpty();
                    updateApproachGoal();
                    return;
                }
            }
            cursor = 0;
            if (phase == Phase.SEAL_FLUIDS) {
                phase = Phase.BREAK_LAYER;
            } else {
                phase = Phase.SEAL_FLUIDS;
                y--;
            }
        }
        if (verificationPass == 0 && hasRemainingBlocks()) {
            verificationPass = 1;
            y = max.getY();
            cursor = 0;
            phase = Phase.SEAL_FLUIDS;
            feedback.accept("检测到回流流体或残留方块，开始第二次清理");
            return;
        }
        int remaining = countRemainingBlocks();
        feedback.accept("选区清理完成：破坏 " + cleared
                + " 个方块，填实 " + sealed + " 个流体格"
                + (remaining == 0 ? "" : "，仍有 " + remaining
                + " 个无法处理的方块或回流流体"));
        onLostControl();
    }

    private void advanceTarget() {
        target = null;
        targetWasFluid = false;
        currentGoal = null;
        failedPaths = 0;
        targetTicks = 0;
    }

    private boolean hasRemainingBlocks() {
        return countRemainingBlocks() > 0;
    }

    private int countRemainingBlocks() {
        int remaining = 0;
        for (int checkY = min.getY(); checkY <= max.getY(); checkY++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    if (!baritone.getPlayerContext().world().getBlockState(
                            new BlockPos(x, checkY, z)).isAir()) {
                        remaining++;
                    }
                }
            }
        }
        return remaining;
    }

    private void placeIntoFluid() {
        if (!baritone.getInventoryController().selectThrowawayForLocation(
                true, target.getX(), target.getY(), target.getZ())) {
            feedback.accept("没有可用于清除流体的完整垫脚方块，清理已停止");
            onLostControl();
            return;
        }
        if (baritone.getFakeInteractionController()
                .fillFluidWithSelectedBlock(target)) {
            sealed++;
        } else {
            updateApproachGoal();
        }
    }

    private void breakTarget(BlockState state) {
        if (!baritone.getFakeInteractionController().canReach(target)) {
            updateApproachGoal();
            return;
        }
        baritone.getFakeInteractionController().breakBlock(target);
    }

    private void updateApproachGoal() {
        currentGoal = target == null
                ? null : new GoalWithinInteractionReach(target);
    }

    private boolean withinReach(BlockPos pos) {
        return baritone.getPlayerContext().player().getEyePosition()
                .distanceToSqr(pos.getCenter())
                <= RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE
                * RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
    }

    @Override
    public PathingCommand onTick(
            boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed && ++failedPaths >= 3) {
            feedback.accept("无法安全到达清理位置 " + target
                    + "，已跳过该方块");
            advanceTarget();
        }
        return new PathingCommand(currentGoal,
                currentGoal == null
                        ? PathingCommandType.REQUEST_PAUSE
                        : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override public boolean isActive() { return min != null; }
    @Override public boolean isTemporary() { return false; }
    @Override
    public void onLostControl() {
        min = null;
        max = null;
        target = null;
        currentGoal = null;
        cursor = 0;
        cleared = 0;
        sealed = 0;
        failedPaths = 0;
        targetTicks = 0;
        verificationPass = 0;
        targetWasFluid = false;
        baritone.getInputOverrideHandler().clearAllKeys();
    }
    @Override public String displayName0() {
        return "Clean selection y=" + y + " " + phase;
    }

    /**
     * GoalGetToBlock treats the block directly below the target as reached,
     * even when a tall vertical gap still puts it outside interaction range.
     * Model the standing node's eye position instead, so A* can choose a jump,
     * stair or pillar until the target is genuinely reachable.
     */
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
            double dx = x - target.x;
            double dy = y + EYE_HEIGHT - (target.y + 0.5D);
            double dz = z - target.z;
            return dx * dx + dy * dy + dz * dz <= REACH * REACH;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isInGoal(x, y, z)) return 0.0D;
            int dx = x - target.x;
            int dy = y - target.y;
            int dz = z - target.z;
            return Math.max(0.0D,
                    GoalBlock.calculate(dx, dy, dz)
                            - REACH * 3.563D);
        }

        @Override
        public BlockPos getGoalPos() {
            return target;
        }
    }
}
