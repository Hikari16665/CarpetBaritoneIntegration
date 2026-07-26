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
import java.util.HashSet;
import java.util.List;
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

    private enum State { SCANNING, TO_CONTAINER, TO_PLAYER }

    private final Baritone baritone;
    private final List<StorageCandidate> candidates = new ArrayList<>();
    private final Set<BlockPos> failedContainers = new HashSet<>();
    private Item item;
    private int amount;
    private UUID recipientId;
    private Consumer<String> feedback = ignored -> { };
    private State state;
    private StorageCandidate target;
    private Goal currentGoal;
    private int scanRadius;
    private int scanSide;
    private int scanCursor;
    private int deliveredAmount;
    private boolean sourcesExhausted;

    public CollectItemProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void collect(Item item, int amount, ServerPlayer recipient,
                        Consumer<String> feedback) {
        onLostControl();
        this.item = item;
        this.amount = amount;
        this.recipientId = recipient.getUUID();
        this.feedback = feedback == null ? ignored -> { } : feedback;
        this.scanRadius = baritone.getPlayerContext().server()
                .getPlayerList().getViewDistance();
        this.scanSide = scanRadius * 2 + 1;
        this.state = State.SCANNING;
        if (availableInInventory() >= amount) {
            beginDelivery();
        }
    }

    public void serverTick() {
        if (!isActive()) return;
        switch (state) {
            case SCANNING -> scanTick();
            case TO_CONTAINER -> {
                if (target != null && isNear(target.pos)) {
                    baritone.cancelPath();
                    takeFromTarget();
                }
            }
            case TO_PLAYER -> deliverTick();
        }
    }

    private void scanTick() {
        ServerLevel world = baritone.getPlayerContext().world();
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        int centerX = feet.getX() >> 4;
        int centerZ = feet.getZ() >> 4;
        int processed = 0;
        while (scanCursor < scanSide * scanSide
                && processed++ < CHUNKS_PER_TICK) {
            int dx = scanCursor % scanSide - scanRadius;
            int dz = scanCursor / scanSide - scanRadius;
            scanCursor++;
            LevelChunk chunk = world.getChunkSource()
                    .getChunkNow(centerX + dx, centerZ + dz);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey().immutable();
                if (failedContainers.contains(pos)) continue;
                Container container = supportedContainer(entry.getValue());
                if (container == null) continue;
                StorageCandidate candidate = inspect(pos, container);
                if (candidate.totalValue() > 0) candidates.add(candidate);
            }
        }
        if (scanCursor < scanSide * scanSide) return;
        chooseNextContainer();
    }

    private void chooseNextContainer() {
        int needed = amount - deliveredAmount - availableInInventory();
        if (needed <= 0) {
            beginDelivery();
            return;
        }
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        target = candidates.stream()
                .filter(candidate -> !failedContainers.contains(candidate.pos))
                .filter(candidate -> containerAt(candidate.pos) != null)
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
                onLostControl();
            } else {
                feedback.accept("已扫描加载范围，没有找到目标物品（0/"
                        + amount + "）");
                onLostControl();
            }
            return;
        }
        state = State.TO_CONTAINER;
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
        candidates.removeIf(candidate -> candidate.pos.equals(target.pos));
        StorageCandidate remaining = inspect(target.pos, container);
        if (remaining.totalValue() > 0) candidates.add(remaining);
        target = null;
        currentGoal = null;
        if (deliveredAmount + availableInInventory() >= amount) {
            beginDelivery();
        } else if (inventoryBlocked && availableInInventory() > 0) {
            feedback.accept("背包已满，先投递当前批次 "
                    + availableInInventory() + " 个目标物品");
            beginDelivery();
        } else if (inventoryBlocked) {
            feedback.accept("背包已满且没有可先投递的目标物品，任务停止");
            onLostControl();
        } else {
            chooseNextContainer();
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
        int batchTarget = Math.min(availableInInventory(),
                Math.max(0, amount - deliveredAmount));
        int remaining = batchTarget;
        NonNullList<ItemStack> inventory =
                player.getInventory().getNonEquipmentItems();
        List<Integer> boxes = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isFullTargetShulker(inventory.get(slot))) boxes.add(slot);
        }
        for (int slot : boxes) {
            if (remaining <= 0) break;
            ItemStack box = inventory.get(slot);
            int value = boxedTargetCount(box);
            inventory.set(slot, ItemStack.EMPTY);
            dropTowardRecipient(box);
            remaining -= value;
        }
        for (int slot = 0;
             slot < inventory.size() && remaining > 0; slot++) {
            ItemStack box = inventory.get(slot);
            if (isFullTargetShulker(box)
                    || boxedTargetCount(box) == 0) continue;
            while (remaining > 0 && boxedTargetCount(box) > 0) {
                ItemStack extracted = extractFromShulker(box, remaining);
                if (extracted.isEmpty()) break;
                remaining -= extracted.getCount();
                dropTowardRecipient(extracted);
            }
        }
        for (int slot = inventory.size() - 1;
             slot >= 0 && remaining > 0; slot--) {
            ItemStack stack = inventory.get(slot);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            ItemStack dropped = stack.copyWithCount(take);
            stack.shrink(take);
            dropTowardRecipient(dropped);
            remaining -= take;
        }
        player.inventoryMenu.broadcastChanges();
        int delivered = batchTarget - Math.max(0, remaining);
        if (remaining < 0) {
            delivered -= remaining;
        }
        deliveredAmount += delivered;
        if (deliveredAmount >= amount) {
            feedback.accept("已向 " + recipient.getScoreboardName()
                    + " 累计投递目标物品 " + deliveredAmount
                    + (deliveredAmount > amount
                    ? "（包含整盒潜影盒，实际数量超过要求）" : ""));
            onLostControl();
        } else if (sourcesExhausted) {
            feedback.accept("目标物品没有找全：已向 "
                    + recipient.getScoreboardName() + " 投递 "
                    + deliveredAmount + "/" + amount + "，还缺 "
                    + (amount - deliveredAmount));
            onLostControl();
        } else {
            feedback.accept("已向 " + recipient.getScoreboardName()
                    + " 投递一批 " + delivered + " 个，累计 "
                    + deliveredAmount + "/" + amount + "，继续收集");
            chooseNextContainer();
        }
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
        currentGoal = null;
        chooseNextContainer();
    }

    private void beginDelivery() {
        state = State.TO_PLAYER;
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
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return 0;
        }
        ItemContainerContents contents =
                stack.getOrDefault(DataComponents.CONTAINER,
                        ItemContainerContents.EMPTY);
        return contents.nonEmptyStream().filter(inner -> inner.is(item))
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
        return isActive() && (stack.is(item) || boxedTargetCount(stack) > 0);
    }

    private int availableInInventory() {
        if (!isActive()) return 0;
        int result = 0;
        for (ItemStack stack :
                baritone.getPlayerContext().player().getInventory()
                        .getNonEquipmentItems()) {
            if (stack.is(item)) result += stack.getCount();
            result += boxedTargetCount(stack);
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
        if (calcFailed && state == State.TO_CONTAINER) {
            failTarget();
        }
        if (state == State.TO_PLAYER) {
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
        sourcesExhausted = false;
        recipientId = null;
        state = null;
        target = null;
        currentGoal = null;
        candidates.clear();
        failedContainers.clear();
        scanCursor = 0;
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
}
