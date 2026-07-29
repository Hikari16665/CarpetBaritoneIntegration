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
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import baritone.utils.ToolSet;

import java.util.Objects;
import java.util.function.Predicate;

/** Hotbar-only inventory operations needed by path calculation and movement. */
public final class ServerInventoryController {
    private final ServerPlayer player;
    private Baritone baritone;
    private int lastInventoryMoveTick = Integer.MIN_VALUE / 2;

    public ServerInventoryController(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    public void bind(Baritone baritone) {
        this.baritone = Objects.requireNonNull(baritone, "baritone");
    }

    public boolean hasGenericThrowaway() {
        return hasAccessibleItem(this::isGenericThrowaway);
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        int slot = -1;
        if (baritone != null) {
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            BlockState wanted = baritone.getBuilderProcess().placeAt(x, y, z, current);
            if (wanted != null) {
                Predicate<ItemStack> wantedBlock =
                        stack -> stack.getItem() instanceof BlockItem blockItem
                                && blockItem.getBlock() == wanted.getBlock();
                slot = findSlot(wantedBlock);
                if (slot < 0 && selectItem(wantedBlock)) {
                    return true;
                }
            }
        }
        if (slot < 0) {
            // Prefer configured cheap blocks, then fall back to any safe,
            // ordinary full cube. Path calculation and execution must share
            // this exact predicate or a planned pillar can never execute.
            if (!selectItem(stack -> isAcceptable(stack.getItem()))
                    && !selectItem(this::isGenericThrowaway)) {
                return false;
            }
            return true;
        }
        if (slot < 0) {
            return false;
        }
        if (select) {
            return selectInventorySlot(slot, 8);
        }
        return true;
    }

    public boolean selectBlock(Block block) {
        return selectItem(stack ->
                stack.getItem() instanceof BlockItem blockItem
                        && blockItem.getBlock() == block);
    }

    public boolean selectItem(Predicate<ItemStack> desired) {
        NonNullList<ItemStack> inventory = player.getInventory().items;
        int accessibleSlots = Baritone.settings().allowInventory.value
                ? inventory.size() : Math.min(9, inventory.size());
        for (int index = 0; index < accessibleSlots; index++) {
            if (desired.test(inventory.get(index))) {
                return selectInventorySlot(index, 7);
            }
        }
        if (Baritone.settings().allowInventory.value
                && extractOneFromShulker(desired)) {
            for (int index = 0; index < inventory.size(); index++) {
                if (desired.test(inventory.get(index))) {
                    return selectInventorySlot(index, 7);
                }
            }
        }
        return false;
    }

    /**
     * Builder/Printer inventory operation. Its interactions are server-side
     * synthetic actions, so once the fake player can theoretically access its
     * inventory there is no reason to wait for the ordinary movement throttle.
     *
     * <p>This also unpacks one matching stack from an inventory shulker before
     * selecting it. Keeping this separate from {@link #selectItem(Predicate)}
     * preserves upstream-style throttling for normal path movements.</p>
     */
    public boolean selectItemForBuilder(Predicate<ItemStack> desired) {
        if (!Baritone.settings().allowInventory.value) {
            return selectItem(desired);
        }
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        int slot = findSlot(desired);
        if (slot < 0 && extractOneFromShulker(desired)) {
            slot = findSlot(desired);
        }
        if (slot < 0) return false;
        if (slot >= 9) {
            int destination = 7;
            ItemStack hotbar = inventory.get(destination);
            inventory.set(destination, inventory.get(slot));
            inventory.set(slot, hotbar);
            player.inventoryMenu.broadcastChanges();
            slot = destination;
        }
        player.getInventory().selected = slot;
        return true;
    }

    /**
     * Moves one matching stack out of an inventory shulker ahead of time.
     * Builder calls this when it chooses a remote target, allowing the normal
     * path execution to overlap with material preparation.
     */
    public boolean prepareItemForBuilder(Predicate<ItemStack> desired) {
        if (findSlot(desired) >= 0) return true;
        return Baritone.settings().allowInventory.value
                && extractOneFromShulker(desired);
    }

    public boolean hasAccessibleItem(Predicate<ItemStack> desired) {
        for (ItemStack stack : player.getInventory().items) {
            if (desired.test(stack)) return true;
            if (isShulker(stack) && contents(stack).nonEmptyStream()
                    .anyMatch(desired)) return true;
        }
        return false;
    }

    public int countAccessible(Predicate<ItemStack> desired) {
        int result = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (desired.test(stack)) result += stack.getCount();
            if (isShulker(stack)) {
                result += contents(stack).nonEmptyStream()
                        .filter(desired).mapToInt(ItemStack::getCount).sum();
            }
        }
        return result;
    }

    /** Performs the inventory swap used by upstream elytra safety logic. */
    public boolean equipBestElytra(int minimumRemainingDurability) {
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        int bestSlot = -1;
        int bestRemaining = minimumRemainingDurability;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack candidate = inventory.get(slot);
            if (!candidate.is(Items.ELYTRA)) continue;
            int remaining = candidate.getMaxDamage()
                    - candidate.getDamageValue();
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) return false;
        ItemStack equipped = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack replacement = inventory.get(bestSlot);
        inventory.set(bestSlot, equipped);
        player.setItemSlot(EquipmentSlot.CHEST, replacement);
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    /**
     * Relevant subset of upstream InventoryBehavior#bestToolAgainst and its
     * hotbar swap: scan the complete non-equipment inventory and move the
     * fastest tool to hotbar slot zero.
     */
    public void ensureBestToolOnHotbar(BlockState state) {
        NonNullList<ItemStack> inventory = player.getInventory().items;
        boolean allowInventory = Baritone.settings().allowInventory.value;
        int accessibleSlots = allowInventory
                ? inventory.size() : Math.min(9, inventory.size());
        ToolLocation nestedBest = allowInventory
                ? bestNestedTool(state) : null;
        double outerBestSpeed = inventory.subList(0, accessibleSlots).stream()
                .filter(this::usableTool)
                .mapToDouble(stack ->
                        ToolSet.calculateSpeedVsBlock(stack, state))
                .max().orElse(-1.0D);
        if (nestedBest != null && nestedBest.speed > outerBestSpeed) {
            extractFromShulker(nestedBest.boxSlot, nestedBest.innerSlot);
        }
        int bestIndex = -1;
        double bestSpeed = -1.0D;
        for (int index = 0; index < accessibleSlots; index++) {
            ItemStack stack = inventory.get(index);
            if (!usableTool(stack)) continue;
            double speed = ToolSet.calculateSpeedVsBlock(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestIndex = index;
            }
        }
        if (bestIndex >= 9 && canMoveInventoryNow()) {
            ItemStack hotbar = inventory.get(0);
            inventory.set(0, inventory.get(bestIndex));
            inventory.set(bestIndex, hotbar);
            player.inventoryMenu.broadcastChanges();
            markInventoryMoved();
        }
    }

    private ToolLocation bestNestedTool(BlockState state) {
        ToolLocation best = null;
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        for (int boxSlot = 0; boxSlot < inventory.size(); boxSlot++) {
            ItemStack box = inventory.get(boxSlot);
            if (!isShulker(box)) continue;
            NonNullList<ItemStack> inner =
                    NonNullList.withSize(27, ItemStack.EMPTY);
            contents(box).copyInto(inner);
            for (int innerSlot = 0; innerSlot < inner.size(); innerSlot++) {
                ItemStack tool = inner.get(innerSlot);
                if (!usableTool(tool)) continue;
                double speed = ToolSet.calculateSpeedVsBlock(tool, state);
                if (best == null || speed > best.speed) {
                    best = new ToolLocation(boxSlot, innerSlot, speed);
                }
            }
        }
        return best;
    }

    private boolean usableTool(ItemStack stack) {
        if (stack.isEmpty()
                || !stack.getItem().components().has(DataComponents.TOOL)) {
            return false;
        }
        return !Baritone.settings().itemSaver.value
                || stack.getMaxDamage() <= 1
                || stack.getDamageValue()
                + Baritone.settings().itemSaverThreshold.value
                < stack.getMaxDamage();
    }

    private boolean extractOneFromShulker(Predicate<ItemStack> desired) {
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        for (int boxSlot = 0; boxSlot < inventory.size(); boxSlot++) {
            ItemStack box = inventory.get(boxSlot);
            if (!isShulker(box)) continue;
            NonNullList<ItemStack> inner =
                    NonNullList.withSize(27, ItemStack.EMPTY);
            contents(box).copyInto(inner);
            for (int innerSlot = 0; innerSlot < inner.size(); innerSlot++) {
                if (desired.test(inner.get(innerSlot))) {
                    return extractFromShulker(boxSlot, innerSlot);
                }
            }
        }
        return false;
    }

    private boolean extractFromShulker(int boxSlot, int innerSlot) {
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        ItemStack box = inventory.get(boxSlot);
        if (!isShulker(box)) return false;
        NonNullList<ItemStack> inner =
                NonNullList.withSize(27, ItemStack.EMPTY);
        contents(box).copyInto(inner);
        ItemStack extracted = inner.get(innerSlot);
        if (extracted.isEmpty()) return false;
        int before = extracted.getCount();
        player.getInventory().add(extracted);
        int inserted = before - extracted.getCount();
        boolean swapped = false;
        if (inserted == 0) {
            // A full inventory can still access the nested item by swapping a
            // normal outer stack into the slot it just vacated. Never create
            // illegal shulker-in-shulker nesting.
            for (int outerSlot = 0;
                 outerSlot < inventory.size(); outerSlot++) {
                if (outerSlot == boxSlot) continue;
                ItemStack displaced = inventory.get(outerSlot);
                if (displaced.isEmpty() || isShulker(displaced)) continue;
                inventory.set(outerSlot, extracted);
                inner.set(innerSlot, displaced);
                inserted = before;
                extracted = ItemStack.EMPTY;
                swapped = true;
                break;
            }
        }
        if (!swapped) {
            inner.set(innerSlot, extracted.isEmpty()
                    ? ItemStack.EMPTY : extracted);
        }
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(inner));
        if (inserted > 0) {
            player.inventoryMenu.broadcastChanges();
            return true;
        }
        return false;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static ItemContainerContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
    }

    private record ToolLocation(
            int boxSlot, int innerSlot, double speed) {}

    private int findThrowawaySlot() {
        NonNullList<ItemStack> inventory = player.getInventory().items;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && isAcceptable(stack.getItem())) {
                return slot;
            }
        }
        return -1;
    }

    private int findSlot(Predicate<ItemStack> desired) {
        NonNullList<ItemStack> inventory = player.getInventory().items;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.get(slot).isEmpty() && desired.test(inventory.get(slot))) return slot;
        }
        return -1;
    }

    private boolean selectInventorySlot(
            int inventorySlot, int destinationHotbarSlot) {
        NonNullList<ItemStack> inventory = player.getInventory().items;
        int selected = inventorySlot;
        if (inventorySlot >= 9) {
            if (!Baritone.settings().allowInventory.value
                    || !canMoveInventoryNow()) {
                return false;
            }
            ItemStack hotbar = inventory.get(destinationHotbarSlot);
            inventory.set(destinationHotbarSlot, inventory.get(inventorySlot));
            inventory.set(inventorySlot, hotbar);
            player.inventoryMenu.broadcastChanges();
            markInventoryMoved();
            selected = destinationHotbarSlot;
        }
        player.getInventory().selected = selected;
        return true;
    }

    private boolean canMoveInventoryNow() {
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value
                && player.getDeltaMovement().horizontalDistanceSqr()
                > 1.0E-4D) {
            return false;
        }
        int delay = Math.max(0,
                Baritone.settings().ticksBetweenInventoryMoves.value);
        return player.tickCount - lastInventoryMoveTick >= delay;
    }

    private void markInventoryMoved() {
        lastInventoryMoveTick = player.tickCount;
    }

    private static boolean isAcceptable(Item item) {
        return Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    private boolean isGenericThrowaway(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (isAcceptable(stack.getItem())) return true;
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || blockItem.getBlock() instanceof ShulkerBoxBlock
                || blockItem.getBlock() instanceof FallingBlock) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.hasBlockEntity()
                && state.isCollisionShapeFullBlock(
                        player.level(), player.blockPosition());
    }
}
