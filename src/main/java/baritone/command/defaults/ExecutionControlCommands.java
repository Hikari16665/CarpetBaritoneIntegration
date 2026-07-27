package baritone.command.defaults;
import baritone.api.IBaritone;
public final class ExecutionControlCommands extends ServerCommand {
    public ExecutionControlCommands(IBaritone baritone) {
        super(baritone, "停止任务或查看状态",
                "stop", "cancel", "status", "stats",
                "pause", "p", "paws",
                "resume", "r", "unpause", "unpaws",
                "paused");
    }
}
