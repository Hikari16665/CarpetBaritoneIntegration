package baritone.command.defaults;

import baritone.api.IBaritone;

/** Server-safe settings command exposed through tell. */
public final class SettingsCommand extends ServerCommand {
    public SettingsCommand(IBaritone baritone) {
        super(baritone, "查看或修改 Baritone 设置",
                "set", "setting", "settings");
    }
}
