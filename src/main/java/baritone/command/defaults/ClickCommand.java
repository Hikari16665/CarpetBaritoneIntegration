package baritone.command.defaults;
import baritone.api.IBaritone;
public final class ClickCommand extends ServerCommand {
    public ClickCommand(IBaritone baritone) { super(baritone, "破坏或放置方块", "break", "place"); }
}
