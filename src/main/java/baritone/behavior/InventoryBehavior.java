package baritone.behavior;

import baritone.Baritone;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Server inventory behavior backed by direct fake-player inventory updates. */
public final class InventoryBehavior extends Behavior {
    public InventoryBehavior(Baritone baritone) { super(baritone); }
    public boolean hasGenericThrowaway() {
        return baritone.getInventoryController().hasGenericThrowaway();
    }
    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        return baritone.getInventoryController().selectThrowawayForLocation(select, x, y, z);
    }
    public boolean selectBlock(Block block) {
        return baritone.getInventoryController().selectBlock(block);
    }
    public boolean selectItem(Predicate<ItemStack> predicate) {
        return baritone.getInventoryController().selectItem(predicate);
    }
    public void ensureBestToolOnHotbar(BlockState state) {
        baritone.getInventoryController().ensureBestToolOnHotbar(state);
    }
}
