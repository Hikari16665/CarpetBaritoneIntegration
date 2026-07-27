package baritone.command.defaults;
import baritone.api.IBaritone;
public final class HelpCommand extends ServerCommand {
    public HelpCommand(IBaritone baritone) {
        super(baritone, "显示服务端 Baritone 命令帮助",
                "help", "commands", "?");
    }
}
