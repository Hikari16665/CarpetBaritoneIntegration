package baritone.command.defaults;
import baritone.api.IBaritone;
public final class MineCommand extends ServerCommand {
    public MineCommand(IBaritone baritone) { super(baritone, "挖掘指定方块", "mine"); }
}
