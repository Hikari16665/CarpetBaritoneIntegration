package baritone.command.defaults;

import baritone.api.IBaritone;

public final class CollectItemCommand extends ServerCommand {
    public CollectItemCommand(IBaritone baritone) {
        super(baritone, "从附近箱子收集物品并交给玩家",
                "collectitem", "collect_item", "collect");
    }
}
