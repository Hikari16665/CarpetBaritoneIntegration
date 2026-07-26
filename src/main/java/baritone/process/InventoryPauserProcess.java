package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

/** Original InventoryPauserProcess behavior adapted to a server player. */
public final class InventoryPauserProcess implements IBaritoneProcess {
    private final Baritone baritone;
    private boolean pauseRequestedLastTick;
    private boolean safeToCancelLastTick;
    private int ticksOfStationary;

    public InventoryPauserProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    private boolean stationaryNow() {
        return baritone.getPlayerContext().playerMotion().multiply(1, 0, 1).length() < 0.00001D;
    }

    public boolean stationaryForInventoryMove() {
        pauseRequestedLastTick = true;
        return safeToCancelLastTick && ticksOfStationary > 1;
    }

    public void serverTick() {
        onTick(false, baritone.getPathExecutor() == null);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        safeToCancelLastTick = isSafeToCancel;
        if (pauseRequestedLastTick) {
            pauseRequestedLastTick = false;
            if (stationaryNow()) {
                ticksOfStationary++;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ticksOfStationary = 0;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    @Override public boolean isActive() { return true; }
    @Override public boolean isTemporary() { return true; }
    @Override public void onLostControl() { }
    @Override public String displayName0() { return "inventory pauser"; }
    @Override public double priority() { return 5.1D; }
}
