package baritone.utils;

import baritone.Baritone;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.IPlayerContext;

public abstract class BaritoneProcessHelper implements IBaritoneProcess {
    protected final Baritone baritone;
    protected final IPlayerContext ctx;

    protected BaritoneProcessHelper(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    @Override public boolean isTemporary() { return false; }
}
