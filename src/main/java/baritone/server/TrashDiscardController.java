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
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
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
    private static final int SCAFFOLD_RESERVE = 128;

    private final ServerPlayer player;
    private final Map<BlockPos, Integer> watchedBreaks = new HashMap<>();
    private final Map<UUID, TrackedDrop> trackedDrops = new HashMap<>();
    private final Set<UUID> rethrownDrops = new HashSet<>();

    public TrashDiscardController(ServerPlayer player) {
        this.player = player;
    }

    public void observe(
            Set<BlockPos> toBreak, BlockPos protectedOrigin,
            Predicate<ItemStack> protectedDrop) {
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
            BlockPos protectedOrigin, Predicate<ItemStack> protectedDrop) {
        ServerLevel world = (ServerLevel) player.level();
        discoverDrops(world, protectedOrigin, protectedDrop);
        if (protectedOrigin != null && protectedDrop != null) {
            trackedDrops.values().removeIf(tracked ->
                    protectedOrigin.distSqr(tracked.origin) <= 64.0D
                            && protectedDrop.test(tracked.template));
        }
        processPickedUpDrops(world);

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
        return origin != null && protectedDrop != null
                && origin.distSqr(entity.blockPosition()) <= 64.0D
                && protectedDrop.test(entity.getItem());
    }

    private void processPickedUpDrops(ServerLevel world) {
        Iterator<Map.Entry<UUID, TrackedDrop>> iterator = trackedDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedDrop> entry = iterator.next();
            TrackedDrop tracked = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                int current = countInInventory(tracked.template);
                int reserve = isScaffold(tracked.template)
                        ? Math.max(tracked.inventoryBefore, SCAFFOLD_RESERVE)
                        : tracked.inventoryBefore;
                int trashCount = Math.max(0, current - reserve);
                if (trashCount > 0) {
                    List<ItemStack> removed = removeFromInventory(tracked.template, trashCount);
                    for (ItemStack stack : removed) {
                        // Equivalent to the player pressing Q: the item is
                        // dropped from the fake player's current position with
                        // the normal forward throw motion.
                        ItemEntity dropped = player.drop(stack, false);
                        if (dropped != null) {
                            dropped.setPickUpDelay(200);
                            rethrownDrops.add(dropped.getUUID());
                        }
                    }
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
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private List<ItemStack> removeFromInventory(ItemStack template, int requested) {
        List<ItemStack> removed = new ArrayList<>();
        NonNullList<ItemStack> inventory = player.getInventory().getNonEquipmentItems();
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

    private static boolean isScaffold(ItemStack stack) {
        return stack.getItem() instanceof BlockItem
                && Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem());
    }

    public void clear() {
        watchedBreaks.clear();
        trackedDrops.clear();
        rethrownDrops.clear();
    }

    public boolean isTrash(ItemEntity entity) {
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
}
