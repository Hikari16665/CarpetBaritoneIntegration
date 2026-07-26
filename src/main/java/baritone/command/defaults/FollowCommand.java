package baritone.command.defaults;
import baritone.api.IBaritone;
public final class FollowCommand extends ServerCommand {
    public FollowCommand(IBaritone baritone) { super(baritone, "跟随玩家", "follow"); }
}
