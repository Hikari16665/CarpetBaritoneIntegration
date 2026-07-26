package baritone.command.defaults;
import baritone.api.IBaritone;
public final class UtilityCommand extends ServerCommand {
    public UtilityCommand(IBaritone baritone) {
        super(baritone, "服务端工具与设置",
                "get", "getto", "get_to_block", "backfill", "avoid",
                "avoidance", "cache");
    }
}
