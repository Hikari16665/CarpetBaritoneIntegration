package baritone.server;

import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Tracks drops from collateral path excavation. Drops remain real, can be
 * picked up, and are then thrown back at their original break position.
 */
public final class TrashDiscardController {
    private static final int WATCH_TICKS = 60;
    private static final int TRACK_TICKS = 200;

    private final ServerPlayer player;
    private final Map<BlockPos, Integer> watchedBreaks = new HashMap<>();
    private final Map<UUID, TrackedDrop> trackedDrops = new HashMap<>();
    private final Set<UUID> rethrownDrops = new HashSet<>();
    private int navigationGraceTicks;
    private int plannedPlacementCount;

    public TrashDiscardController(ServerPlayer player) {
        this.player = player;
    }

    public void observe(
            Set<BlockPos> toBreak, Set<BlockPos> toPlace,
            BlockPos protectedOrigin,
            Predicate<ItemStack> protectedDrop) {
        if (!Baritone.settings().trashDiscardEnabled.value) return;
        plannedPlacementCount = toPlace == null ? 0 : toPlace.size();
        ServerLevel world = (ServerLevel) player.level();
        for (BlockPos pos : toBreak) {
            if (protectedOrigin != null && protectedOrigin.distSqr(pos) <= 1) {
                continue;
            }
            if (world.getBlockState(pos).isAir()) {
                watchedBreaks.put(pos.immutable(), WATCH_TICKS);
            }
        }
    }

    public void tick(
            boolean navigating,
            BlockPos protectedOrigin, Predicate<ItemStack> protectedDrop) {
        if (!Baritone.settings().trashDiscardEnabled.value) {
            clear();
            return;
        }
        ServerLevel world = (ServerLevel) player.level();
        if (navigating) {
            navigationGraceTicks = 10;
        } else if (navigationGraceTicks > 0) {
            navigationGraceTicks--;
        } else {
            plannedPlacementCount = 0;
        }
        int requiredReserve = requiredThrowawayReserve();
        discoverDrops(world, protectedOrigin, protectedDrop);
        if (protectedDrop != null) {
            // Desired process output wins over an earlier collateral-drop
            // classification. This matters when an ore and a tunnel block
            // break on adjacent ticks.
            trackedDrops.values().removeIf(
                    tracked -> protectedDrop.test(tracked.template));
        }
        processPickedUpDrops(world, protectedDrop, requiredReserve);
        discardUntrackedInventoryGains(
                navigationGraceTicks > 0, protectedDrop, requiredReserve);

        Iterator<Map.Entry<BlockPos, Integer>> iterator = watchedBreaks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                iterator.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    private void discoverDrops(
            ServerLevel world, BlockPos protectedOrigin,
            Predicate<ItemStack> protectedDrop) {
        for (BlockPos origin : watchedBreaks.keySet()) {
            if (protectedOrigin != null && protectedOrigin.distSqr(origin) <= 1) {
                continue;
            }
            for (ItemEntity entity : world.getEntitiesOfClass(
                    ItemEntity.class,
                    new AABB(origin).inflate(1.35D),
                    item -> item.isAlive()
                            && item.getAge() < WATCH_TICKS
                            && isConfiguredTrash(item.getItem())
                            && !isProtectedTargetDrop(
                                    item, protectedOrigin, protectedDrop)
                            && !rethrownDrops.contains(item.getUUID())
                            && !trackedDrops.containsKey(item.getUUID())
            )) {
                ItemStack template = entity.getItem().copy();
                trackedDrops.put(entity.getUUID(), new TrackedDrop(
                        origin.immutable(),
                        template,
                        countInInventory(template),
                        TRACK_TICKS
                ));
            }
        }
    }

    private static boolean isProtectedTargetDrop(
            ItemEntity entity, BlockPos origin,
            Predicate<ItemStack> protectedDrop) {
        return protectedDrop != null && protectedDrop.test(entity.getItem());
    }

    /**
     * Item entities can be absorbed between two server ticks, before they are
     * discoverable by the positional drop tracker. Upstream's inventory
     * behavior observes inventory deltas as well, so do the same while a
     * navigation process owns the player.
     */
    private void discardUntrackedInventoryGains(
            boolean navigating, Predicate<ItemStack> protectedDrop,
            int requiredReserve) {
        List<InventoryCount> current = snapshotInventory();
        if (navigating) {
            // Drop items a person would discard first: non-placeable trash,
            // then generic full blocks, and configured preferred throwaways
            // last. This preserves the best bridge/pillar material.
            current.sort(Comparator.comparingInt(entry ->
                    discardPriority(entry.template)));
            for (InventoryCount now : current) {
                if (!isConfiguredTrash(now.template)
                        || protectedDrop != null && protectedDrop.test(now.template)) {
                    continue;
                }
                boolean usable = isUsablePathingBlock(now.template);
                int amount = TrashDiscardPolicy.discardableFromStack(
                        now.count, usable,
                        countUsableTrashBlocks(protectedDrop),
                        requiredReserve);
                dropFromInventory(now.template, amount);
            }
            player.inventoryMenu.broadcastChanges();
        }
    }

    private List<InventoryCount> snapshotInventory() {
        List<InventoryCount> result = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            InventoryCount existing = result.stream()
                    .filter(entry -> ItemStack.isSameItemSameComponents(
                            entry.template, stack))
                    .findFirst().orElse(null);
            if (existing == null) {
                result.add(new InventoryCount(stack.copyWithCount(1),
                        stack.getCount()));
            } else {
                existing.count += stack.getCount();
            }
        }
        return result;
    }

    private void processPickedUpDrops(
            ServerLevel world, Predicate<ItemStack> protectedDrop,
            int requiredReserve) {
        Iterator<Map.Entry<UUID, TrackedDrop>> iterator = trackedDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedDrop> entry = iterator.next();
            TrackedDrop tracked = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                int current = countInInventory(tracked.template);
                int trashCount = Math.max(
                        0, current - tracked.inventoryBefore);
                if (trashCount > 0) {
                    trashCount = TrashDiscardPolicy.discardableFromStack(
                            trashCount,
                            isUsablePathingBlock(tracked.template),
                            countUsableTrashBlocks(protectedDrop),
                            requiredReserve);
                    dropFromInventory(tracked.template, trashCount);
                    player.inventoryMenu.broadcastChanges();
                }
                iterator.remove();
            } else if (--tracked.ticksRemaining <= 0) {
                iterator.remove();
            }
        }
    }

    private int countInInventory(ItemStack template) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private List<ItemStack> removeFromInventory(ItemStack template, int requested) {
        List<ItemStack> removed = new ArrayList<>();
        NonNullList<ItemStack> inventory = player.getInventory().items;
        int remaining = requested;
        for (int slot = inventory.size() - 1; slot >= 0 && remaining > 0; slot--) {
            ItemStack stack = inventory.get(slot);
            if (!ItemStack.isSameItemSameComponents(stack, template)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            ItemStack dropped = stack.copy();
            dropped.setCount(take);
            removed.add(dropped);
            stack.shrink(take);
            remaining -= take;
        }
        return removed;
    }

    private void dropFromInventory(ItemStack template, int requested) {
        if (requested <= 0) return;
        for (ItemStack stack : removeFromInventory(template, requested)) {
            // Equivalent to the player pressing Q: keep real item entities
            // and normal throw motion, but prevent the fake player from
            // immediately absorbing its own discarded stack.
            ItemEntity dropped = player.drop(stack, false);
            if (dropped != null) {
                dropped.setPickUpDelay(200);
                rethrownDrops.add(dropped.getUUID());
            }
        }
    }

    private int requiredThrowawayReserve() {
        return TrashDiscardPolicy.requiredReserve(
                Baritone.settings().throwawayBlockReserve.value,
                Baritone.settings().throwawayBlockReserveMaximum.value,
                plannedPlacementCount,
                Baritone.settings().throwawayPlacementSafetyMargin.value);
    }

    private int countUsableTrashBlocks(Predicate<ItemStack> protectedDrop) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || !isConfiguredTrash(stack)
                    || !isUsablePathingBlock(stack)
                    || protectedDrop != null && protectedDrop.test(stack)) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }

    private int discardPriority(ItemStack stack) {
        if (!isUsablePathingBlock(stack)) return 0;
        return Baritone.settings().acceptableThrowawayItems.value
                .contains(stack.getItem()) ? 2 : 1;
    }

    private boolean isUsablePathingBlock(ItemStack stack) {
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

    private static boolean isConfiguredTrash(ItemStack stack) {
        return !stack.isEmpty()
                && Baritone.settings().trashItems.value.contains(
                        stack.getItem());
    }

    public void clear() {
        watchedBreaks.clear();
        trackedDrops.clear();
        rethrownDrops.clear();
        navigationGraceTicks = 0;
        plannedPlacementCount = 0;
    }

    public boolean isTrash(ItemEntity entity) {
        if (!Baritone.settings().trashDiscardEnabled.value) return false;
        if (!isConfiguredTrash(entity.getItem())) return false;
        UUID uuid = entity.getUUID();
        if (trackedDrops.containsKey(uuid) || rethrownDrops.contains(uuid)) {
            return true;
        }
        BlockPos itemPos = entity.blockPosition();
        return watchedBreaks.keySet().stream().anyMatch(origin -> origin.distSqr(itemPos) <= 4);
    }

    private static final class TrackedDrop {
        private final BlockPos origin;
        private final ItemStack template;
        private final int inventoryBefore;
        private int ticksRemaining;

        private TrackedDrop(
                BlockPos origin,
                ItemStack template,
                int inventoryBefore,
                int ticksRemaining
        ) {
            this.origin = origin;
            this.template = template;
            this.inventoryBefore = inventoryBefore;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private static final class InventoryCount {
        private final ItemStack template;
        private int count;

        private InventoryCount(ItemStack template, int count) {
            this.template = template;
            this.count = count;
        }
    }
}
