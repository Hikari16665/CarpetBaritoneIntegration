package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICollectItemProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server storage collection process. Container contents are accessed only
 * after the fake player has walked into interaction range. Its path context
 * disables breaking and placing.
 */
public final class CollectItemProcess implements ICollectItemProcess {
    private static final int CHUNKS_PER_TICK = 6;

    private enum State { SEARCHING, ACQUIRING, DELIVERING, RETURNING }

    private final Baritone baritone;
    /** Only positions are retained. Container contents are never cached. */
    private final List<BlockPos> candidates = new ArrayList<>();
    private final Set<BlockPos> failedContainers = new HashSet<>();
    private Item item;
    private int amount;
    private final Map<Item, Integer> requestedItems = new LinkedHashMap<>();
    private final Map<Item, Integer> deliveredItems = new LinkedHashMap<>();
    private final Set<Item> exhaustedItems = new HashSet<>();
    private UUID recipientId;
    private Consumer<String> feedback = ignored -> { };
    private State state;
    private StorageCandidate target;
    private Goal currentGoal;
    private int scanRadius;
    private int scanBlockRadius;
    private int scanCursor;
    private BlockPos searchOrigin;
    private List<ChunkOffset> scanOrder = List.of();
    private int deliveredAmount;
    private boolean sourcesExhausted;
    private boolean incompleteBecauseInventoryFull;
    private BlockPos deliveryStart;
    private int targetPathFailures;

    public CollectItemProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void collect(Item item, int amount, ServerPlayer recipient,
                        Consumer<String> feedback) {
        collect(Map.of(item, amount), recipient, feedback);
    }

    @Override
    public void collect(Map<Item, Integer> items, ServerPlayer recipient,
                        Consumer<String> feedback) {
        onLostControl();
        items.forEach((requested, count) -> {
            if (requested != null && count != null && count > 0) {
                requestedItems.merge(requested, count, Integer::sum);
            }
        });
        if (requestedItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one positive item requirement is required");
        }
        this.recipientId = recipient.getUUID();
        this.feedback = feedback == null ? ignored -> { } : feedback;
        this.scanBlockRadius = Math.max(1,
                baritone.settings().collectItemMaxDistance.value);
        this.scanRadius = Math.max(1,
                (scanBlockRadius + 15) / 16);
        advanceToNextItem();
    }

    private void advanceToNextItem() {
        Map.Entry<Item, Integer> next = requestedItems.entrySet().stream()
                .filter(entry -> !exhaustedItems.contains(entry.getKey()))
                .filter(entry -> deliveredItems.getOrDefault(
                        entry.getKey(), 0) < entry.getValue())
                .findFirst().orElse(null);
        if (next == null) {
            finishAllItems();
            return;
        }
        item = next.getKey();
        amount = next.getValue();
        deliveredAmount = deliveredItems.getOrDefault(item, 0);
        failedContainers.clear();
        beginSearch();
        if (deliveredAmount + availableInInventory() >= amount) {
            beginDelivery();
        }
    }

    private void finishCurrentItem(boolean exhausted) {
        deliveredItems.put(item, deliveredAmount);
        if (exhausted) exhaustedItems.add(item);
        advanceToNextItem();
    }

    private void finishAllItems() {
        String summary = requestedItems.entrySet().stream()
                .map(entry -> deliveredItems.getOrDefault(entry.getKey(), 0)
                        + "/" + entry.getValue() + " "
                        + BuiltInRegistries.ITEM.getKey(entry.getKey()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        feedback.accept("收集任务结束：" + summary);
        onLostControl();
    }

    public void serverTick() {
        if (!isActive()) return;
        switch (state) {
            case SEARCHING -> scanTick();
            case ACQUIRING -> {
                if (target != null && baritone
                        .getFakeInteractionController()
                        .canReach(target.pos)) {
                    baritone.cancelPath();
                    takeFromTarget();
                }
            }
            case DELIVERING -> deliverTick();
            case RETURNING -> {
                if (deliveryStart != null
                        && baritone.getPlayerContext().playerFeet()
                        .distSqr(deliveryStart) <= 4.0D) {
                    baritone.cancelPath();
                    incompleteBecauseInventoryFull = false;
                    resumeAcquisitionAfterDelivery();
                }
            }
        }
    }

    private void scanTick() {
        ServerLevel world = baritone.getPlayerContext().world();
        int centerX = searchOrigin.getX() >> 4;
        int centerZ = searchOrigin.getZ() >> 4;
        int processed = 0;
        while (scanCursor < scanOrder.size()
                && processed++ < CHUNKS_PER_TICK) {
            ChunkOffset offset = scanOrder.get(scanCursor++);
            LevelChunk chunk = world.getChunkSource().getChunkNow(
                    centerX + offset.x, centerZ + offset.z);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey().immutable();
                if (failedContainers.contains(pos)) continue;
                if (searchOrigin.distSqr(pos)
                        > (double) scanBlockRadius * scanBlockRadius) continue;
                Container container = supportedContainer(entry.getValue());
                if (container == null) continue;
                StorageCandidate candidate = inspect(pos, container);
                if (candidate.totalValue() > 0
                        && !candidates.contains(pos)) candidates.add(pos);
            }
            if (freshCandidateTotal() >= itemsStillNeeded()) {
                chooseNextContainer();
                return;
            }
        }
        if (scanCursor < scanOrder.size()) return;
        chooseNextContainer();
    }

    private void chooseNextContainer() {
        int needed = itemsStillNeeded();
        if (needed <= 0) {
            beginDelivery();
            return;
        }
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        target = candidates.stream()
                .filter(pos -> !failedContainers.contains(pos))
                .map(pos -> freshCandidate(pos))
                .filter(candidate -> candidate != null
                        && candidate.totalValue() > 0)
                .max(Comparator
                        .comparingInt(StorageCandidate::priority)
                        .thenComparingDouble(candidate ->
                                -feet.distSqr(candidate.pos)))
                .orElse(null);
        if (target == null) {
            sourcesExhausted = true;
            int available = availableInInventory();
            if (available > 0) {
                feedback.accept("已扫描加载范围，只找到 "
                        + (deliveredAmount + available) + "/" + amount
                        + "，先把找到的部分交给玩家");
                beginDelivery();
            } else if (deliveredAmount > 0) {
                feedback.accept("目标物品没有找全：已投递 "
                        + deliveredAmount + "/" + amount + "，还缺 "
                        + (amount - deliveredAmount));
                finishCurrentItem(true);
            } else {
                feedback.accept("已扫描加载范围，没有找到目标物品（0/"
                        + amount + "）");
                finishCurrentItem(true);
            }
            return;
        }
        state = State.ACQUIRING;
        targetPathFailures = 0;
        currentGoal = new GoalGetToBlock(target.pos);
    }

    private void takeFromTarget() {
        Container container = containerAt(target.pos);
        if (container == null) {
            failTarget();
            return;
        }
        ServerPlayer player = baritone.getPlayerContext().player();
        container.startOpen(player);
        int needed = Math.max(0,
                amount - deliveredAmount - availableInInventory());
        boolean inventoryBlocked = false;

        // Full single-item shulker boxes are atomic and taken first.
        List<Integer> boxedSlots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (isFullTargetShulker(container.getItem(slot))) {
                boxedSlots.add(slot);
            }
        }
        for (int slot : boxedSlots) {
            if (needed <= 0) break;
            ItemStack box = container.removeItemNoUpdate(slot);
            int value = boxedTargetCount(box);
            if (!moveToPlayerInventory(box)) {
                container.setItem(slot, box);
                inventoryBlocked = true;
                break;
            }
            needed -= value;
        }

        for (int slot = 0;
             slot < container.getContainerSize() && needed > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            ItemStack removed = container.removeItem(
                    slot, Math.min(needed, stack.getCount()));
            int taken = removed.getCount();
            if (!moveToPlayerInventory(removed)) {
                int inserted = taken - removed.getCount();
                container.setItem(slot, merge(container.getItem(slot), removed));
                needed -= inserted;
                inventoryBlocked = true;
                break;
            }
            needed -= taken;
        }
        // A partial/mixed shulker is left in the chest. Only the requested
        // inner items are removed from its container component.
        for (int slot = 0;
             slot < container.getContainerSize() && needed > 0; slot++) {
            ItemStack box = container.getItem(slot);
            if (isFullTargetShulker(box)
                    || boxedTargetCount(box) == 0) continue;
            while (needed > 0 && boxedTargetCount(box) > 0) {
                ItemStack extracted = extractFromShulker(box, needed);
                int taken = extracted.getCount();
                int inserted = taken;
                if (!moveToPlayerInventory(extracted)) {
                    inserted -= extracted.getCount();
                    // Inventory.add may have inserted part of the stack.
                    // Rebuild the remainder so no item is lost.
                    putIntoShulker(box, extracted);
                    inventoryBlocked = true;
                }
                needed -= inserted;
                if (inserted < taken) break;
            }
        }
        container.setChanged();
        container.stopOpen(player);
        player.inventoryMenu.broadcastChanges();
        candidates.remove(target.pos);
        target = null;
        currentGoal = null;
        if (deliveredAmount + availableInInventory() >= amount) {
            beginDelivery();
        } else if (inventoryBlocked && availableRequestedInInventory() > 0) {
            feedback.accept("背包已满，先投递当前批次 "
                    + availableRequestedInInventory() + " 个目标物品");
            incompleteBecauseInventoryFull = true;
            beginDelivery();
        } else if (inventoryBlocked) {
            feedback.accept("背包已满且当前物品无法取出，跳过该物品");
            finishCurrentItem(true);
        } else {
            beginSearch();
        }
    }

    private void deliverTick() {
        ServerPlayer recipient = recipient();
        if (recipient == null) {
            feedback.accept("接收玩家已离线，收集任务停止，物品保留在假人物品栏");
            onLostControl();
            return;
        }
        ServerPlayer player = baritone.getPlayerContext().player();
        if (recipient.level() != player.level()) {
            feedback.accept("接收玩家不在同一维度，无法投递");
            onLostControl();
            return;
        }
        if (player.distanceToSqr(recipient) > 9.0D) {
            currentGoal = new GoalNear(recipient.blockPosition(), 2);
            return;
        }
        baritone.cancelPath();
        /*
         * Delivery is intentionally uncapped. Once we reach the recipient,
         * every related loose item and every target item inside carried
         * shulkers is handed over, including surplus acquired in the batch.
         */
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        Item activeItem = item;
        int delivered = 0;
        for (Item requested : requestedItems.keySet()) {
            item = requested;
            int dropped = dropCurrentRelated(inventory);
            deliveredItems.merge(requested, dropped, Integer::sum);
            if (requested == activeItem) delivered = dropped;
        }
        item = activeItem;
        deliveredAmount = deliveredItems.getOrDefault(item, 0);
        player.inventoryMenu.broadcastChanges();
        if (deliveredAmount >= amount) {
            feedback.accept("已向 " + recipient.getScoreboardName()
                    + " 累计投递目标物品 " + deliveredAmount
                    + (deliveredAmount > amount
                    ? "（包含整盒潜影盒，实际数量超过要求）" : ""));
            finishCurrentItem(false);
        } else if (incompleteBecauseInventoryFull) {
            feedback.accept("已向 " + recipient.getScoreboardName()
                    + " 交付一批 " + delivered + " 个，累计 "
                    + deliveredAmount + "/" + amount
                    + "，返回交付起点后继续收集");
            state = State.RETURNING;
            currentGoal = deliveryStart == null ? null
                    : new GoalNear(deliveryStart, 1);
        } else if (sourcesExhausted) {
            feedback.accept("目标物品没有找全：已向 "
                    + recipient.getScoreboardName() + " 投递 "
                    + deliveredAmount + "/" + amount + "，还缺 "
                    + (amount - deliveredAmount));
            finishCurrentItem(true);
        } else {
            feedback.accept("已向 " + recipient.getScoreboardName()
                    + " 投递一批 " + delivered + " 个，累计 "
                    + deliveredAmount + "/" + amount + "，继续收集");
            beginSearch();
        }
    }

    private int dropCurrentRelated(NonNullList<ItemStack> inventory) {
        int delivered = 0;
        List<Integer> boxes = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isFullTargetShulker(inventory.get(slot))) boxes.add(slot);
        }
        for (int slot : boxes) {
            ItemStack box = inventory.get(slot);
            int value = boxedTargetCount(box);
            inventory.set(slot, ItemStack.EMPTY);
            dropTowardRecipient(box);
            delivered += value;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack box = inventory.get(slot);
            if (isFullTargetShulker(box)
                    || boxedTargetCount(box) == 0) continue;
            while (boxedTargetCount(box) > 0) {
                ItemStack extracted = extractFromShulker(
                        box, Integer.MAX_VALUE);
                if (extracted.isEmpty()) break;
                delivered += extracted.getCount();
                dropTowardRecipient(extracted);
            }
        }
        for (int slot = inventory.size() - 1; slot >= 0; slot--) {
            ItemStack stack = inventory.get(slot);
            if (!stack.is(item)) continue;
            int take = stack.getCount();
            ItemStack dropped = stack.copy();
            inventory.set(slot, ItemStack.EMPTY);
            dropTowardRecipient(dropped);
            delivered += take;
        }
        return delivered;
    }

    private void dropTowardRecipient(ItemStack stack) {
        ServerPlayer player = baritone.getPlayerContext().player();
        var dropped = player.drop(stack, false);
        if (dropped == null) return;
        ServerPlayer recipient = recipient();
        if (recipient != null) {
            var direction = recipient.getEyePosition()
                    .subtract(player.getEyePosition()).normalize();
            dropped.setDeltaMovement(direction.scale(0.35D)
                    .add(0.0D, 0.15D, 0.0D));
        }
        dropped.setPickUpDelay(10);
    }

    private void failTarget() {
        if (target != null) failedContainers.add(target.pos);
        target = null;
        targetPathFailures = 0;
        currentGoal = null;
        beginSearch();
    }

    /**
     * Starts a fresh radial search at the current position. Container
     * positions exist only for this search pass; their contents are re-read
     * whenever a decision is made.
     */
    private void beginSearch() {
        state = State.SEARCHING;
        currentGoal = null;
        target = null;
        sourcesExhausted = false;
        candidates.clear();
        scanCursor = 0;
        searchOrigin = baritone.getPlayerContext().playerFeet().immutable();
        List<ChunkOffset> offsets = new ArrayList<>();
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int z = -scanRadius; z <= scanRadius; z++) {
                if (x * x + z * z <= scanRadius * scanRadius) {
                    offsets.add(new ChunkOffset(x, z));
                }
            }
        }
        offsets.sort(Comparator.comparingInt(ChunkOffset::distanceSquared));
        scanOrder = List.copyOf(offsets);
    }

    private int itemsStillNeeded() {
        return Math.max(0, amount - deliveredAmount
                - availableInInventory());
    }

    private int freshCandidateTotal() {
        int total = 0;
        int needed = itemsStillNeeded();
        for (BlockPos pos : candidates) {
            StorageCandidate candidate = freshCandidate(pos);
            if (candidate != null) total += candidate.totalValue();
            if (total >= needed) break;
        }
        return total;
    }

    private StorageCandidate freshCandidate(BlockPos pos) {
        Container container = containerAt(pos);
        return container == null ? null : inspect(pos, container);
    }

    private void resumeAcquisitionAfterDelivery() {
        boolean hasFreshSource = candidates.stream()
                .filter(pos -> !failedContainers.contains(pos))
                .map(this::freshCandidate)
                .anyMatch(candidate -> candidate != null
                        && candidate.totalValue() > 0);
        if (hasFreshSource) {
            chooseNextContainer();
        } else {
            beginSearch();
        }
    }

    private void beginDelivery() {
        state = State.DELIVERING;
        deliveryStart = baritone.getPlayerContext().playerFeet().immutable();
        ServerPlayer recipient = recipient();
        currentGoal = recipient == null ? null
                : new GoalNear(recipient.blockPosition(), 2);
    }

    private StorageCandidate inspect(BlockPos pos, Container container) {
        int fullBoxes = 0;
        int boxes = 0;
        int loose = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            int boxed = boxedTargetCount(stack);
            if (boxed > 0) {
                boxes += boxed;
                if (isFullTargetShulker(stack)) fullBoxes += boxed;
            } else if (stack.is(item)) {
                loose += stack.getCount();
            }
        }
        return new StorageCandidate(pos, fullBoxes, boxes, loose);
    }

    private Container supportedContainer(BlockEntity blockEntity) {
        if (!(blockEntity instanceof Container container)) return null;
        var block = blockEntity.getBlockState().getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock ? container : null;
    }

    private Container containerAt(BlockPos pos) {
        if (!baritone.getPlayerContext().world().hasChunkAt(pos)) return null;
        return supportedContainer(
                baritone.getPlayerContext().world().getBlockEntity(pos));
    }

    private int boxedTargetCount(ItemStack stack) {
        return boxedItemCount(stack, item);
    }

    private int boxedItemCount(ItemStack stack, Item targetItem) {
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return 0;
        }
        ItemContainerContents contents =
                stack.getOrDefault(DataComponents.CONTAINER,
                        ItemContainerContents.EMPTY);
        return contents.nonEmptyStream()
                .filter(inner -> inner.is(targetItem))
                .mapToInt(ItemStack::getCount).sum();
    }

    private boolean isFullTargetShulker(ItemStack stack) {
        if (boxedTargetCount(stack) <= 0) return false;
        ItemContainerContents contents =
                stack.getOrDefault(DataComponents.CONTAINER,
                        ItemContainerContents.EMPTY);
        List<ItemStack> slots = contents.stream().toList();
        return slots.size() == 27 && slots.stream().allMatch(inner ->
                !inner.isEmpty() && inner.is(item)
                        && inner.getCount() == inner.getMaxStackSize());
    }

    private ItemStack extractFromShulker(
            ItemStack box, int requested) {
        ItemContainerContents contents =
                box.getOrDefault(DataComponents.CONTAINER,
                        ItemContainerContents.EMPTY);
        NonNullList<ItemStack> slots =
                NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(slots);
        int extractedCount = 0;
        int remaining = Math.min(requested,
                new ItemStack(item).getMaxStackSize());
        for (ItemStack inner : slots) {
            if (remaining <= 0) break;
            if (!inner.is(item)) continue;
            int take = Math.min(remaining, inner.getCount());
            inner.shrink(take);
            extractedCount += take;
            remaining -= take;
        }
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(slots));
        return extractedCount == 0 ? ItemStack.EMPTY
                : new ItemStack(item, extractedCount);
    }

    private void putIntoShulker(ItemStack box, ItemStack returned) {
        if (returned.isEmpty()) return;
        ItemContainerContents contents =
                box.getOrDefault(DataComponents.CONTAINER,
                        ItemContainerContents.EMPTY);
        NonNullList<ItemStack> slots =
                NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(slots);
        for (int slot = 0;
             slot < slots.size() && !returned.isEmpty(); slot++) {
            ItemStack inner = slots.get(slot);
            if (inner.isEmpty()) {
                slots.set(slot, returned.copy());
                returned.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(inner, returned)) {
                int move = Math.min(returned.getCount(),
                        inner.getMaxStackSize() - inner.getCount());
                inner.grow(move);
                returned.shrink(move);
            }
        }
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(slots));
    }

    public boolean isProtectedStack(ItemStack stack) {
        return isActive() && requestedItems.keySet().stream().anyMatch(
                requested -> stack.is(requested)
                        || boxedItemCount(stack, requested) > 0);
    }

    private int availableInInventory() {
        if (!isActive()) return 0;
        int result = 0;
        for (ItemStack stack :
                baritone.getPlayerContext().player().getInventory()
                        .items) {
            if (stack.is(item)) result += stack.getCount();
            result += boxedTargetCount(stack);
        }
        return result;
    }

    private int availableRequestedInInventory() {
        if (!isActive()) return 0;
        int result = 0;
        for (ItemStack stack :
                baritone.getPlayerContext().player().getInventory()
                        .items) {
            for (Item requested : requestedItems.keySet()) {
                if (stack.is(requested)) result += stack.getCount();
                result += boxedItemCount(stack, requested);
            }
        }
        return result;
    }

    private boolean moveToPlayerInventory(ItemStack stack) {
        if (stack.isEmpty()) return true;
        return baritone.getPlayerContext().player().getInventory().add(stack);
    }

    private boolean isNear(BlockPos pos) {
        return new GoalGetToBlock(pos).isInGoal(
                baritone.getPlayerContext().playerFeet());
    }

    private ServerPlayer recipient() {
        return recipientId == null ? null
                : baritone.getPlayerContext().server().getPlayerList()
                        .getPlayer(recipientId);
    }

    private static ItemStack merge(ItemStack existing, ItemStack returned) {
        if (existing.isEmpty()) return returned;
        if (ItemStack.isSameItemSameComponents(existing, returned)) {
            existing.grow(returned.getCount());
            return existing;
        }
        return existing;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed && state == State.ACQUIRING) {
            /*
             * A no-break route around a long wall often has to move away from
             * the container before approaching it. Do not blacklist a live
             * container after one bounded search; allow the scheduler's
             * expanded second-pass budget first.
             */
            if (++targetPathFailures >= 2) {
                failTarget();
            }
        }
        if (state == State.DELIVERING) {
            ServerPlayer recipient = recipient();
            if (recipient != null) {
                currentGoal = new GoalNear(recipient.blockPosition(), 2);
            }
        }
        PathingCommandType type = currentGoal == null
                ? PathingCommandType.REQUEST_PAUSE
                : PathingCommandType.REVALIDATE_GOAL_AND_PATH;
        return new PathingCommand(currentGoal, type);
    }

    @Override public boolean isActive() { return state != null; }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        item = null;
        amount = 0;
        deliveredAmount = 0;
        requestedItems.clear();
        deliveredItems.clear();
        exhaustedItems.clear();
        sourcesExhausted = false;
        incompleteBecauseInventoryFull = false;
        recipientId = null;
        state = null;
        target = null;
        currentGoal = null;
        candidates.clear();
        failedContainers.clear();
        scanCursor = 0;
        searchOrigin = null;
        scanOrder = List.of();
        deliveryStart = null;
        targetPathFailures = 0;
    }
    @Override public String displayName0() {
        return "Collect " + amount + " " + item + " (" + state + ")";
    }

    private record StorageCandidate(
            BlockPos pos, int fullBoxValue, int boxValue, int looseValue) {
        private int totalValue() { return boxValue + looseValue; }
        private int priority() {
            if (fullBoxValue > 0) return 3;
            if (boxValue > 0) return 2;
            return looseValue > 0 ? 1 : 0;
        }
    }

    private record ChunkOffset(int x, int z) {
        private int distanceSquared() {
            return x * x + z * z;
        }
    }
}
