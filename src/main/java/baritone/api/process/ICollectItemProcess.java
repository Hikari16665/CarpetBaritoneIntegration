package baritone.api.process;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.function.Consumer;

/** Collects an item from loaded storage without modifying world blocks. */
public interface ICollectItemProcess extends IBaritoneProcess {
    void collect(Item item, int amount, ServerPlayer recipient,
                 Consumer<String> feedback);
}
