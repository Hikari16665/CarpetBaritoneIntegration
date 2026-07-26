package baritone.command.defaults;
import baritone.api.IBaritone;
public final class GoalCommand extends ServerCommand {
    public GoalCommand(IBaritone baritone) {
        super(baritone, "设置坐标或逃离目标", "come", "y", "runaway", "run_away");
    }
}
