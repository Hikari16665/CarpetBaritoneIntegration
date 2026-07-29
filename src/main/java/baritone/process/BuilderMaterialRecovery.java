package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Bounded, live container refill used only by Builder. Positions may be
 * retained while approaching them; contents are read again on every use.
 */
final class BuilderMaterialRecovery {
    enum Result { IDLE, WORKING, ACQUIRED, EXHAUSTED }

    private final Baritone baritone;
    private String requestedKey;
    private Predicate<ItemStack> requested =
            ignored -> false;
    private BlockPos origin;
    private List<ChunkOffset> order = List.of();
    private int cursor;
    private BlockPos target;

    BuilderMaterialRecovery(Baritone baritone) {
        this.baritone = baritone;
    }

    boolean isActive() {
        return requestedKey != null;
    }

    String key() {
        return requestedKey;
    }

    Goal goal() {
        return target == null ? null : new GoalGetToBlock(target);
    }

    void pathFailed() {
        target = null;
    }

    void request(Item item) {
        if (item == null) return;
        request("item:" + item,
                stack -> stack.is(item));
    }

    void request(String key, Predicate<ItemStack> matcher) {
        if (key == null || matcher == null
                || key.equals(requestedKey)) return;
        requestedKey = key;
        requested = matcher;
        origin = baritone.getPlayerContext().playerFeet().immutable();
        target = null;
        cursor = 0;
        int blocks = Math.max(1,
                Baritone.settings().printerContainerSearchRange.value);
        int radius = (blocks + 15) / 16;
        List<ChunkOffset> offsets = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if ((long) x * x + (long) z * z
                        <= (long) radius * radius) {
                    offsets.add(new ChunkOffset(x, z));
                }
            }
        }
        offsets.sort(Comparator.comparingInt(offset ->
                offset.x * offset.x + offset.z * offset.z));
        order = List.copyOf(offsets);
    }

    void clear() {
        requestedKey = null;
        requested = ignored -> false;
        target = null;
        order = List.of();
        cursor = 0;
    }

    Result tick() {
        if (requestedKey == null) return Result.IDLE;
        if (target != null) return approachOrTake();
        int budget = Math.max(1, Baritone.settings()
                .printerContainerScanChunksPerTick.value);
        int centerX = origin.getX() >> 4;
        int centerZ = origin.getZ() >> 4;
        int range = Math.max(1,
                Baritone.settings().printerContainerSearchRange.value);
        while (cursor < order.size() && budget-- > 0) {
            ChunkOffset offset = order.get(cursor++);
            LevelChunk chunk = baritone.getPlayerContext().world()
                    .getChunkSource().getChunkNow(
                            centerX + offset.x, centerZ + offset.z);
            if (chunk == null) continue;
            target = chunk.getBlockEntities().entrySet().stream()
                    .filter(entry -> origin.distSqr(entry.getKey())
                            <= (double) range * range)
                    .filter(entry -> count(
                            supported(entry.getValue())) > 0)
                    .map(entry -> entry.getKey().immutable())
                    .min(Comparator.comparingDouble(origin::distSqr))
                    .orElse(null);
            if (target != null) return approachOrTake();
        }
        return cursor >= order.size()
                ? Result.EXHAUSTED : Result.WORKING;
    }

    private Result approachOrTake() {
        Container container = containerAt(target);
        if (container == null || count(container) <= 0) {
            target = null;
            return cursor >= order.size()
                    ? Result.EXHAUSTED : Result.WORKING;
        }
        if (!baritone.getFakeInteractionController().canReach(target)) {
            // The Builder process publishes this target through onTick().
            // PathingControlManager is the only path executor owner.
            return Result.WORKING;
        }
        baritone.cancelPath();
        int acquired = take(container, Math.max(1,
                Baritone.settings().printerContainerRefillBatch.value));
        target = null;
        if (acquired > 0) {
            clear();
            return Result.ACQUIRED;
        }
        return cursor >= order.size()
                ? Result.EXHAUSTED : Result.WORKING;
    }

    private int take(Container container, int maximum) {
        ServerPlayer player = baritone.getPlayerContext().player();
        container.startOpen(player);
        int acquired = 0;
        for (int slot = 0; slot < container.getContainerSize()
                && acquired < maximum; slot++) {
            ItemStack stack = container.getItem(slot);
            if (requested.test(stack)) {
                ItemStack removed = container.removeItem(
                        slot, Math.min(maximum - acquired,
                                stack.getCount()));
                int before = removed.getCount();
                player.getInventory().add(removed);
                int inserted = before - removed.getCount();
                acquired += inserted;
                if (!removed.isEmpty()) {
                    container.setItem(slot,
                            merge(container.getItem(slot), removed));
                }
            } else if (isShulker(stack)) {
                acquired += takeFromShulker(
                        stack, maximum - acquired, player);
            }
        }
        container.setChanged();
        container.stopOpen(player);
        player.inventoryMenu.broadcastChanges();
        return acquired;
    }

    private int takeFromShulker(
            ItemStack box, int maximum, ServerPlayer player) {
        NonNullList<ItemStack> slots =
                NonNullList.withSize(27, ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY).copyInto(slots);
        int acquired = 0;
        for (ItemStack inner : slots) {
            if (acquired >= maximum || !requested.test(inner)) continue;
            int take = Math.min(maximum - acquired, inner.getCount());
            ItemStack extracted = inner.split(take);
            int before = extracted.getCount();
            player.getInventory().add(extracted);
            int inserted = before - extracted.getCount();
            acquired += inserted;
            if (!extracted.isEmpty()) inner.grow(extracted.getCount());
            if (inserted == 0) break;
        }
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(slots));
        return acquired;
    }

    private int count(Container container) {
        if (container == null) return 0;
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (requested.test(stack)) result += stack.getCount();
            if (isShulker(stack)) {
                result += stack.getOrDefault(DataComponents.CONTAINER,
                        ItemContainerContents.EMPTY).nonEmptyItemCopyStream()
                        .filter(requested)
                        .mapToInt(ItemStack::getCount).sum();
            }
        }
        return result;
    }

    private Container containerAt(BlockPos pos) {
        if (pos == null
                || !baritone.getPlayerContext().world().hasChunkAt(pos)) {
            return null;
        }
        return supported(baritone.getPlayerContext().world()
                .getBlockEntity(pos));
    }

    private static Container supported(BlockEntity entity) {
        if (!(entity instanceof Container container)) return null;
        var block = entity.getBlockState().getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock
                ? container : null;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static ItemStack merge(ItemStack existing, ItemStack returned) {
        if (existing.isEmpty()) return returned;
        if (ItemStack.isSameItemSameComponents(existing, returned)) {
            existing.grow(returned.getCount());
            return existing;
        }
        return existing;
    }

    private record ChunkOffset(int x, int z) { }
}
