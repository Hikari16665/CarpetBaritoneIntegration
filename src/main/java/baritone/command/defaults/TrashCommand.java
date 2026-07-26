package baritone.command.defaults;

import baritone.api.IBaritone;

public final class TrashCommand extends ServerCommand {
    public TrashCommand(IBaritone baritone) {
        super(baritone, "管理自动丢弃物品黑名单", "trash", "trashlist");
    }
}
