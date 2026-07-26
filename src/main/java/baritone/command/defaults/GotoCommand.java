package baritone.command.defaults;
import baritone.api.IBaritone;
public final class GotoCommand extends ServerCommand {
    public GotoCommand(IBaritone baritone) { super(baritone, "前往坐标", "goto"); }
}
