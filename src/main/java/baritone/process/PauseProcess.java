package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

/** High-priority temporary owner used by pause/resume commands. */
public final class PauseProcess implements IBaritoneProcess {
    private final Baritone baritone;
    private boolean paused;

    public PauseProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    @Override public boolean isActive() {
        return paused;
    }

    @Override
    public PathingCommand onTick(
            boolean calcFailed, boolean isSafeToCancel) {
        baritone.getInputOverrideHandler().clearAllKeys();
        return new PathingCommand(
                null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override public boolean isTemporary() {
        return true;
    }

    /** Cancellation does not implicitly resume an explicit pause. */
    @Override public void onLostControl() {
    }

    @Override public double priority() {
        return DEFAULT_PRIORITY + 1.0D;
    }

    @Override public String displayName0() {
        return "Pause/Resume Commands";
    }
}
