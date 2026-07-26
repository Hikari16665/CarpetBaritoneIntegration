package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Server adaptation of the original BackfillProcess. */
public final class BackfillProcess implements IBaritoneProcess {
    private final Baritone baritone;
    public final Map<BlockPos, BlockState> blocksToReplace = new HashMap<>();

    public BackfillProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    public void observe(Set<BlockPos> toBreak, BlockPos protectedTarget) {
        if (!Baritone.settings().backfill.value) {
            return;
        }
        for (BlockPos pos : toBreak) {
            if (protectedTarget != null && protectedTarget.distSqr(pos) <= 1) {
                continue;
            }
            BlockState state = baritone.getPlayerContext().world().getBlockState(pos);
            if (!state.isAir()) {
                blocksToReplace.putIfAbsent(pos.immutable(), state);
            }
        }
    }

    public void serverTick() {
        blocksToReplace.entrySet().removeIf(entry ->
                !baritone.getPlayerContext().world().getBlockState(entry.getKey()).isAir());
        if (!isActive() || baritone.getPathExecutor() != null) {
            return;
        }
        BlockPos toPlace = blocksToReplace.keySet().stream()
                .filter(pos -> pos.distSqr(baritone.getPlayerContext().playerFeet()) <= 25)
                .max(Comparator.comparingDouble(
                        baritone.getPlayerContext().playerFeet()::distSqr))
                .orElse(null);
        if (toPlace == null) {
            return;
        }
        MovementState state = new MovementState();
        MovementHelper.PlaceResult result = MovementHelper.attemptToPlaceABlock(
                state, baritone, toPlace, false, false);
        switch (result) {
            case READY_TO_PLACE -> {
                state.setInput(Input.CLICK_RIGHT, true);
                baritone.getInputController().apply(state);
            }
            case ATTEMPTING -> baritone.getInputController().apply(state);
            case NO_OPTION -> blocksToReplace.remove(toPlace);
        }
    }

    @Override public boolean isActive() {
        return Baritone.settings().backfill.value && !blocksToReplace.isEmpty();
    }
    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        boolean actionable = blocksToReplace.keySet().stream()
                .anyMatch(pos -> pos.distSqr(
                        baritone.getPlayerContext().playerFeet()) <= 25);
        return new PathingCommand(null, actionable
                ? PathingCommandType.REQUEST_PAUSE
                : PathingCommandType.DEFER);
    }
    @Override public boolean isTemporary() { return true; }
    @Override public void onLostControl() { blocksToReplace.clear(); }
    @Override public String displayName0() { return "Backfill"; }
    @Override public double priority() { return 5.0D; }
}
