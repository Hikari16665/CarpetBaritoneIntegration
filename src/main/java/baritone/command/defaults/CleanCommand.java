package baritone.command.defaults;

import baritone.api.IBaritone;

public final class CleanCommand extends ServerCommand {
    public CleanCommand(IBaritone baritone) {
        super(baritone, "设置选区并从上到下清空",
                "pos1", "pos2", "clean");
    }
}
