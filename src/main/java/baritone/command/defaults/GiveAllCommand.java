package baritone.command.defaults;

import baritone.api.IBaritone;

public final class GiveAllCommand extends ServerCommand {
    public GiveAllCommand(IBaritone baritone) {
        super(baritone, "把假人身上所有物品交给指定玩家",
                "giveall", "give_all");
    }
}
