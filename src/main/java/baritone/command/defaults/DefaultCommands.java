package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import java.util.List;

public final class DefaultCommands {
    private DefaultCommands() { }
    public static List<ICommand> createAll(IBaritone baritone) {
        return List.of(
                new HelpCommand(baritone),
                new GotoCommand(baritone),
                new MineCommand(baritone),
                new BuildCommand(baritone),
                new FollowCommand(baritone),
                new ExploreCommand(baritone),
                new FarmCommand(baritone),
                new ElytraCommand(baritone),
                new GoalCommand(baritone),
                new ExecutionControlCommands(baritone),
                new ClickCommand(baritone),
                new UtilityCommand(baritone)
        );
    }
}
