package baritone.api.process;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

public interface IFollowProcess extends IBaritoneProcess {
    void follow(Predicate<Entity> filter);
    void pickup(Predicate<ItemStack> filter);
    List<Entity> following();
    Predicate<Entity> currentFilter();

    default void cancel() {
        onLostControl();
    }
}
