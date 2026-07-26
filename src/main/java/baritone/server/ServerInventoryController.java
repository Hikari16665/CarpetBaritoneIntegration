/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.Baritone;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import baritone.utils.ToolSet;

import java.util.Objects;
import java.util.function.Predicate;

/** Hotbar-only inventory operations needed by path calculation and movement. */
public final class ServerInventoryController {
    private final ServerPlayer player;
    private Baritone baritone;

    public ServerInventoryController(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    public void bind(Baritone baritone) {
        this.baritone = Objects.requireNonNull(baritone, "baritone");
    }

    public boolean hasGenericThrowaway() {
        return findThrowawaySlot() >= 0;
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        int slot = -1;
        if (baritone != null) {
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            BlockState wanted = baritone.getBuilderProcess().placeAt(x, y, z, current);
            if (wanted != null) {
                slot = findSlot(stack -> stack.getItem() instanceof BlockItem blockItem
                        && blockItem.getBlock() == wanted.getBlock());
            }
        }
        if (slot < 0) slot = findThrowawaySlot();
        if (slot < 0) {
            return false;
        }
        if (select) {
            selectInventorySlot(slot, 8);
        }
        return true;
    }

    public boolean selectBlock(Block block) {
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
        for (int index = 0; index < inventory.size(); index++) {
            ItemStack stack = inventory.get(index);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                selectInventorySlot(index, 7);
                return true;
            }
        }
        return false;
    }

    public boolean selectItem(Predicate<ItemStack> desired) {
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
        for (int index = 0; index < inventory.size(); index++) {
            if (desired.test(inventory.get(index))) {
                selectInventorySlot(index, 7);
                return true;
            }
        }
        return false;
    }

    /**
     * Relevant subset of upstream InventoryBehavior#bestToolAgainst and its
     * hotbar swap: scan the complete non-equipment inventory and move the
     * fastest tool to hotbar slot zero.
     */
    public void ensureBestToolOnHotbar(BlockState state) {
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
        int bestIndex = -1;
        double bestSpeed = -1.0D;
        for (int index = 0; index < inventory.size(); index++) {
            ItemStack stack = inventory.get(index);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value
                    && stack.getMaxDamage() > 1
                    && stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value
                    >= stack.getMaxDamage()) {
                continue;
            }
            if (!stack.getItem().components().has(DataComponents.TOOL)) {
                continue;
            }
            double speed = ToolSet.calculateSpeedVsBlock(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestIndex = index;
            }
        }
        if (bestIndex >= 9) {
            ItemStack hotbar = inventory.get(0);
            inventory.set(0, inventory.get(bestIndex));
            inventory.set(bestIndex, hotbar);
            player.inventoryMenu.broadcastChanges();
        }
    }

    private int findThrowawaySlot() {
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && isAcceptable(stack.getItem())) {
                return slot;
            }
        }
        return -1;
    }

    private int findSlot(Predicate<ItemStack> desired) {
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.get(slot).isEmpty() && desired.test(inventory.get(slot))) return slot;
        }
        return -1;
    }

    private void selectInventorySlot(int inventorySlot, int destinationHotbarSlot) {
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
        int selected = inventorySlot;
        if (inventorySlot >= 9) {
            ItemStack hotbar = inventory.get(destinationHotbarSlot);
            inventory.set(destinationHotbarSlot, inventory.get(inventorySlot));
            inventory.set(inventorySlot, hotbar);
            player.inventoryMenu.broadcastChanges();
            selected = destinationHotbarSlot;
        }
        player.getInventory().setSelectedSlot(selected);
    }

    private static boolean isAcceptable(Item item) {
        return Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }
}
