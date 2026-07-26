package baritone.command.defaults;
import baritone.api.IBaritone;
public final class BuildCommand extends ServerCommand {
    public BuildCommand(IBaritone baritone) { super(baritone, "建造蓝图或填充区域", "build"); }
}
