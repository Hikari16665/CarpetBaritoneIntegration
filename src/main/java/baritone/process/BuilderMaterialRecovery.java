package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.selection.ISelection;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bounded, live container refill used only by Builder. Positions may be
 * retained while approaching them; contents are read again on every use.
 */
final class BuilderMaterialRecovery {
    private static final long MAX_SORTED_SELECTION_CHUNKS = 262_144L;

    enum Result { IDLE, WORKING, ACQUIRED, EXHAUSTED }

    private final Baritone baritone;
    private String requestedKey;
    private Predicate<ItemStack> requested =
            ignored -> false;
    private BlockPos origin;
    private List<ChunkOffset> order = List.of();
    private int cursor;
    private boolean selectionSearch;
    private BlockPos selectionMin;
    private BlockPos selectionMax;
    private int centerChunkX;
    private int centerChunkZ;
    private int minChunkX;
    private int maxChunkX;
    private int minChunkZ;
    private int maxChunkZ;
    private long rectangularCursor;
    private long rectangularChunkCount;
    private BlockPos target;
    private int targetFailures;
    private BlockPos scanProbe;
    private int probeChunkX;
    private int probeChunkZ;
    private int probeFailures;
    private int targetInventoryCount;
    private int acquiredTotal;
    private final Set<BlockPos> visitedContainers = new HashSet<>();

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
        if (target != null) return new GoalGetToBlock(target);
        return scanProbe == null ? null : new GoalNear(scanProbe, 8);
    }

    void pathFailed() {
        if (target != null) {
            if (++targetFailures >= Math.max(1, Baritone.settings()
                    .pathingFailureRetryCount.value)) {
                target = null;
            }
        } else if (scanProbe != null
                && ++probeFailures >= Math.max(1, Baritone.settings()
                        .pathingFailureRetryCount.value)) {
            scanProbe = null;
        }
    }

    void request(Item item) {
        if (item == null) return;
        request("item:" + item, stack -> stack.is(item), 1, 1);
    }

    boolean request(String key, Predicate<ItemStack> matcher,
                    int estimatedRequired, int maximumBatch) {
        if (key == null || matcher == null) return false;
        int desired = targetInventoryCount(
                estimatedRequired, maximumBatch);
        if (key.equals(requestedKey)) {
            targetInventoryCount = Math.max(
                    targetInventoryCount, desired);
            return true;
        }
        // Finish one material acquisition before switching to another. The
        // Builder scan can observe several missing types in one tick; letting
        // the last one replace the first caused repeated partial refills.
        if (requestedKey != null) return false;
        requestedKey = key;
        requested = matcher;
        targetInventoryCount = desired;
        acquiredTotal = 0;
        visitedContainers.clear();
        origin = baritone.getPlayerContext().playerFeet().immutable();
        target = null;
        targetFailures = 0;
        scanProbe = null;
        probeFailures = 0;
        cursor = 0;
        rectangularCursor = 0L;
        ISelection selection = baritone.getSelectionManager()
                .getOnlySelection();
        if (selection != null) {
            configureSelection(selection);
            if (Baritone.settings().diagnosticLogging.value) {
                System.out.println("[CBI-DIAG] builder-refill key="
                        + requestedKey + " selection=" + selectionMin
                        + ".." + selectionMax + " chunks="
                        + rectangularChunkCount);
            }
            return true;
        }
        selectionSearch = false;
        selectionMin = null;
        selectionMax = null;
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
        return true;
    }

    void clear() {
        requestedKey = null;
        requested = ignored -> false;
        target = null;
        targetFailures = 0;
        scanProbe = null;
        probeFailures = 0;
        order = List.of();
        cursor = 0;
        selectionSearch = false;
        selectionMin = null;
        selectionMax = null;
        rectangularCursor = 0L;
        rectangularChunkCount = 0L;
        targetInventoryCount = 0;
        acquiredTotal = 0;
        visitedContainers.clear();
    }

    Result tick() {
        if (requestedKey == null) return Result.IDLE;
        if (target != null) return approachOrTake();
        if (scanProbe != null) {
            LevelChunk loaded = baritone.getPlayerContext().world()
                    .getChunkSource().getChunkNow(
                            probeChunkX, probeChunkZ);
            if (loaded == null) return Result.WORKING;
            scanProbe = null;
            probeFailures = 0;
            target = findTarget(loaded);
            targetFailures = 0;
            if (target != null) return approachOrTake();
        }
        int budget = Math.max(1, Baritone.settings()
                .printerContainerScanChunksPerTick.value);
        while (!scanExhausted() && budget-- > 0) {
            ChunkOffset offset = nextChunk();
            if (offset == null) break;
            LevelChunk chunk = baritone.getPlayerContext().world()
                    .getChunkSource().getChunkNow(
                            offset.x, offset.z);
            if (chunk == null) {
                if (selectionSearch) {
                    probeChunkX = offset.x;
                    probeChunkZ = offset.z;
                    scanProbe = selectionProbe(offset.x, offset.z);
                    probeFailures = 0;
                    return Result.WORKING;
                }
                continue;
            }
            target = findTarget(chunk);
            targetFailures = 0;
            if (target != null) return approachOrTake();
        }
        return scanExhausted()
                ? Result.EXHAUSTED : Result.WORKING;
    }

    private void configureSelection(ISelection selection) {
        selectionSearch = true;
        selectionMin = selection.min().immutable();
        selectionMax = selection.max().immutable();
        minChunkX = selectionMin.getX() >> 4;
        maxChunkX = selectionMax.getX() >> 4;
        minChunkZ = selectionMin.getZ() >> 4;
        maxChunkZ = selectionMax.getZ() >> 4;
        centerChunkX = clamp(origin.getX() >> 4, minChunkX, maxChunkX);
        centerChunkZ = clamp(origin.getZ() >> 4, minChunkZ, maxChunkZ);
        long width = (long) maxChunkX - minChunkX + 1L;
        long length = (long) maxChunkZ - minChunkZ + 1L;
        rectangularChunkCount = saturatedMultiply(width, length);
        if (rectangularChunkCount > MAX_SORTED_SELECTION_CHUNKS) {
            order = List.of();
            return;
        }
        List<ChunkOffset> chunks = new ArrayList<>(
                (int) rectangularChunkCount);
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                chunks.add(new ChunkOffset(x, z));
            }
        }
        chunks.sort(Comparator.comparingLong(chunk ->
                squaredDistance(chunk.x, chunk.z,
                        centerChunkX, centerChunkZ)));
        order = List.copyOf(chunks);
    }

    private ChunkOffset nextChunk() {
        if (!selectionSearch || !order.isEmpty()) {
            if (cursor >= order.size()) return null;
            ChunkOffset next = order.get(cursor++);
            if (selectionSearch) return next;
            return new ChunkOffset(
                    (origin.getX() >> 4) + next.x,
                    (origin.getZ() >> 4) + next.z);
        }
        if (rectangularCursor >= rectangularChunkCount) return null;
        long width = (long) maxChunkX - minChunkX + 1L;
        long index = rectangularCursor++;
        return new ChunkOffset(
                minChunkX + (int) (index % width),
                minChunkZ + (int) (index / width));
    }

    private boolean scanExhausted() {
        if (!selectionSearch || !order.isEmpty()) {
            return cursor >= order.size();
        }
        return rectangularCursor >= rectangularChunkCount;
    }

    private BlockPos findTarget(LevelChunk chunk) {
        int range = Math.max(1,
                Baritone.settings().printerContainerSearchRange.value);
        return chunk.getBlockEntities().entrySet().stream()
                .filter(entry -> selectionSearch
                        ? insideSelection(entry.getKey(),
                                selectionMin, selectionMax)
                        : origin.distSqr(entry.getKey())
                                <= (double) range * range)
                .filter(entry -> count(
                        supported(entry.getValue())) > 0)
                .map(entry -> entry.getKey().immutable())
                .filter(pos -> !visitedContainers.contains(pos))
                .min(Comparator.comparingDouble(origin::distSqr))
                .orElse(null);
    }

    private BlockPos selectionProbe(int chunkX, int chunkZ) {
        int x = clamp((chunkX << 4) + 8,
                selectionMin.getX(), selectionMax.getX());
        int z = clamp((chunkZ << 4) + 8,
                selectionMin.getZ(), selectionMax.getZ());
        int y = clamp(origin.getY(),
                selectionMin.getY(), selectionMax.getY());
        return new BlockPos(x, y, z);
    }

    static boolean insideSelection(
            BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long squaredDistance(
            int x, int z, int centerX, int centerZ) {
        long dx = (long) x - centerX;
        long dz = (long) z - centerZ;
        return dx * dx + dz * dz;
    }

    private Result approachOrTake() {
        int remaining = remainingToAcquire();
        if (remaining <= 0) {
            clear();
            return Result.ACQUIRED;
        }
        Container container = containerAt(target);
        if (container == null || count(container) <= 0) {
            target = null;
            targetFailures = 0;
            return scanExhausted()
                    ? Result.EXHAUSTED : Result.WORKING;
        }
        if (!baritone.getFakeInteractionController().canReach(target)) {
            // The Builder process publishes this target through onTick().
            // PathingControlManager is the only path executor owner.
            return Result.WORKING;
        }
        baritone.cancelPath();
        BlockPos visited = target;
        int acquired = take(container, remaining);
        acquiredTotal += acquired;
        visitedContainers.add(visited);
        target = null;
        targetFailures = 0;
        if (remainingToAcquire() <= 0) {
            clear();
            return Result.ACQUIRED;
        }
        if (!hasInventorySpace()) {
            boolean obtainedSomething = acquiredTotal > 0;
            clear();
            return obtainedSomething ? Result.ACQUIRED : Result.EXHAUSTED;
        }
        // More than one useful container may share the same chunk. Re-read
        // it immediately before advancing the chunk cursor so a large
        // forecast can be satisfied in one visit to the storage area.
        LevelChunk chunk = baritone.getPlayerContext().world()
                .getChunkSource().getChunkNow(
                        visited.getX() >> 4, visited.getZ() >> 4);
        if (chunk != null) {
            target = findTarget(chunk);
            if (target != null) return Result.WORKING;
        }
        if (!scanExhausted()) return Result.WORKING;
        boolean obtainedSomething = acquiredTotal > 0;
        clear();
        return obtainedSomething ? Result.ACQUIRED : Result.EXHAUSTED;
    }

    private int remainingToAcquire() {
        int carried = baritone.getInventoryController()
                .countAccessible(requested);
        return Math.max(0, targetInventoryCount - carried);
    }

    private boolean hasInventorySpace() {
        for (ItemStack stack : baritone.getPlayerContext().player()
                .getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()
                    || requested.test(stack)
                    && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    static int targetInventoryCount(
            int estimatedRequired, int maximumBatch) {
        int maximum = Math.max(1, maximumBatch);
        return Math.max(1, Math.min(estimatedRequired, maximum));
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
