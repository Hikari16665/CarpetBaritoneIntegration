package baritone.server;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.cache.ServerWorldCache;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/** A small server-side process for mining, breaking, and placing blocks. */
public final class BlockInteractionTask {
    public enum Type { MINE, BREAK, PLACE }

    private static final long QUICK_PATH_TIMEOUT_MS = 2_500L;
    private static final long DEEP_PATH_TIMEOUT_MS = 12_000L;
    private static final int DROP_COLLECTION_TIMEOUT_TICKS = 200;

    private final Baritone baritone;
    private final Type type;
    private final Block block;
    private final int requestedCount;
    private final Consumer<String> feedback;
    private BlockPos target;
    private int completedCount;
    private int pathAttempts;
    private boolean finished;
    private BlockPos collectionOrigin;
    private ItemEntity collectionTarget;
    private boolean collectionPathRequested;
    private int collectionWaitTicks;
    private final Set<UUID> ignoredDrops = new HashSet<>();
    private final Set<BlockPos> unreachableTargets = new HashSet<>();
    private boolean internalMiningApproach;
    private List<BlockPos> knownTargets = new ArrayList<>();
    private final Set<Item> desiredDropItems = new HashSet<>();

    private BlockInteractionTask(
            Baritone baritone,
            Type type,
            Block block,
            BlockPos target,
            int requestedCount,
            Consumer<String> feedback
    ) {
        this.baritone = baritone;
        this.type = type;
        this.block = block;
        this.target = target == null ? null : target.immutable();
        this.requestedCount = requestedCount;
        this.feedback = feedback;
    }

    public static BlockInteractionTask mine(
            Baritone baritone, Block block, int count, Consumer<String> feedback
    ) {
        return new BlockInteractionTask(baritone, Type.MINE, block, null, count, feedback);
    }

    public static BlockInteractionTask breakAt(
            Baritone baritone, BlockPos pos, Consumer<String> feedback
    ) {
        return new BlockInteractionTask(baritone, Type.BREAK, null, pos, 1, feedback);
    }

    public static BlockInteractionTask place(
            Baritone baritone, Block block, BlockPos pos, Consumer<String> feedback
    ) {
        return new BlockInteractionTask(baritone, Type.PLACE, block, pos, 1, feedback);
    }

    public void tick() {
        if (finished || baritone.isPathing()) {
            return;
        }
        if (type == Type.MINE && collectionOrigin != null) {
            collectDropsTick();
            return;
        }
        if (type == Type.MINE && target == null) {
            target = findNearest();
            pathAttempts = 0;
            if (target == null) {
                finish("在玩家当前可视范围内没有找到目标方块");
                return;
            }
            feedback.accept("找到目标方块: " + format(target));
        }

        if (type == Type.MINE && target != null && !withinReach(target)) {
            resolveCompositeDestination();
        }
        ServerLevel world = baritone.getPlayerContext().world();
        if (type == Type.BREAK && world.getBlockState(target).isAir()) {
            completedCount++;
            finish("已破坏方块: " + format(target));
            return;
        }
        if (type == Type.PLACE) {
            if (world.getBlockState(target).is(block)) {
                finish("已完成放置: " + format(target));
                return;
            }
        } else if (type == Type.MINE && !world.getBlockState(target).is(block)) {
            if (!world.getBlockState(target).isAir()) {
                target = null;
                pathAttempts = 0;
                internalMiningApproach = false;
                return;
            }
            if (type == Type.MINE) {
                completedCount++;
                collectionOrigin = target.immutable();
                target = null;
                feedback.accept("已挖掘 " + completedCount + "/" + requestedCount
                        + "，正在收集掉落物");
                return;
            }
            completedCount++;
            if (type == Type.MINE && completedCount < requestedCount) {
                feedback.accept("已挖掘 " + completedCount + "/" + requestedCount);
                target = null;
                return;
            }
            finish("已破坏 " + completedCount + " 个方块");
            return;
        }

        if (!withinReach(target)) {
            if (pathAttempts >= 2) {
                if (type == Type.MINE) {
                    blacklistTargetAndContinue();
                    return;
                }
                finish("无法到达目标方块: " + format(target));
                return;
            }
            pathAttempts++;
            long timeout = pathAttempts >= 2
                    ? DEEP_PATH_TIMEOUT_MS
                    : QUICK_PATH_TIMEOUT_MS;
            Goal approach = type == Type.MINE ? compositeMiningGoal() : new GoalGetToBlock(target);
            internalMiningApproach = type == Type.MINE;
            if (!planPath(approach, timeout)) {
                if (pathAttempts < 2) {
                    feedback.accept("第一次没有找到路径，下一 tick 将重新计算");
                    return;
                }
                finish("没有找到通往目标方块的路径: " + format(target));
            }
            return;
        }

        if (type == Type.PLACE) {
            placeTick();
        } else {
            breakTick();
        }
    }

    private void collectDropsTick() {
        if (collectionTarget == null || !collectionTarget.isAlive()) {
            collectionTarget = findNearestDrop();
            collectionPathRequested = false;
            collectionWaitTicks = 0;
            if (collectionTarget == null) {
                finishCollection();
                return;
            }
        }

        if (++collectionWaitTicks > DROP_COLLECTION_TIMEOUT_TICKS) {
            feedback.accept("等待拾取非 Trash 掉落物超过 10 秒，继续处理剩余目标");
            ignoredDrops.add(collectionTarget.getUUID());
            collectionTarget = null;
            return;
        }
        if (baritone.getFakeInteractionController()
                .pickup(collectionTarget)) {
            collectionTarget = null;
            collectionWaitTicks = 0;
            return;
        }

        ServerPlayer player = baritone.getPlayerContext().player();
        if (player.distanceToSqr(collectionTarget) > 4.0D) {
            if (collectionPathRequested
                    && collectionWaitTicks >= DROP_COLLECTION_TIMEOUT_TICKS) {
                feedback.accept("无法到达一件掉落物，继续处理剩余目标");
                ignoredDrops.add(collectionTarget.getUUID());
                collectionTarget = null;
                return;
            }
            collectionPathRequested = true;
            if (!planPath(new GoalNear(collectionTarget.blockPosition(), 1), QUICK_PATH_TIMEOUT_MS)
                    && collectionWaitTicks >= DROP_COLLECTION_TIMEOUT_TICKS) {
                feedback.accept("没有找到通往掉落物的路径，继续任务");
                ignoredDrops.add(collectionTarget.getUUID());
                collectionTarget = null;
            }
            return;
        }

        if (collectionWaitTicks > DROP_COLLECTION_TIMEOUT_TICKS) {
            feedback.accept("等待拾取掉落物超时，继续任务");
            ignoredDrops.add(collectionTarget.getUUID());
            collectionTarget = null;
            return;
        }

        Rotation rotation = RotationUtils.calcRotationFromVec3d(
                baritone.getPlayerContext().playerHead(),
                collectionTarget.position(),
                baritone.getPlayerContext().playerRotations()
        ).withPitch(baritone.getPlayerContext().playerRotations().getPitch());
        baritone.getLookBehavior().updateTarget(rotation, false);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputController().tick();
    }

    private ItemEntity findNearestDrop() {
        if (collectionOrigin == null) {
            return null;
        }
        ServerPlayer player = baritone.getPlayerContext().player();
        return baritone.getPlayerContext().world()
                .getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(collectionOrigin).inflate(8.0D),
                        item -> item.isAlive()
                                && !item.getItem().isEmpty()
                                && !ignoredDrops.contains(item.getUUID())
                                && !baritone.isTrashDrop(item)
                )
                .stream()
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private void finishCollection() {
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputController().tick();
        collectionOrigin = null;
        collectionTarget = null;
        collectionPathRequested = false;
        collectionWaitTicks = 0;
        ignoredDrops.clear();
        desiredDropItems.clear();
        if (completedCount >= requestedCount) {
            finish("已挖掘并收集 " + completedCount + " 个方块的掉落物");
        }
    }

    private void breakTick() {
        if (!baritone.getFakeInteractionController().canReach(target)) {
            if (tryInternalMiningApproach()) {
                return;
            }
            finish("目标方块在附近，但没有可交互的表面: " + format(target));
            return;
        }
        rememberDesiredDrops();
        baritone.getFakeInteractionController().breakBlock(target);
    }

    private void placeTick() {
        int slot = findBlockSlot();
        if (slot < 0) {
            finish("快捷栏中没有所需方块");
            return;
        }
        baritone.getPlayerContext().player().getInventory().setSelectedSlot(slot);
        if (baritone.getFakeInteractionController()
                .placeSelectedBlock(target)) {
            return;
        }
        finish("目标位置周围没有可点击的支撑方块");
    }

    private void applyInteraction(Rotation rotation, Input input) {
        baritone.getLookBehavior().updateTarget(rotation, true);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(input, true);
        baritone.getInputController().tick();
    }

    private boolean planPath(Goal goal, long timeout) {
        return baritone.pathToGoal(goal, timeout, timeout);
    }

    private boolean tryInternalMiningApproach() {
        if (!internalMiningApproach) {
            internalMiningApproach = true;
            pathAttempts = 0;
        }
        if (pathAttempts++ >= 3) {
            return false;
        }
        boolean planned = planPath(miningGoal(target), DEEP_PATH_TIMEOUT_MS);
        if (target == null) {
            return true;
        }
        if (planned) {
            feedback.accept("目标暂时没有可交互表面，正在按原版 Baritone 逻辑挖掘通道");
        }
        return planned;
    }

    private Goal miningGoal(BlockPos pos) {
        boolean safeToMineFromBelow = !(baritone.getPlayerContext().world()
                .getBlockState(pos.above()).getBlock() instanceof FallingBlock);
        return safeToMineFromBelow ? new GoalThreeBlocks(pos) : new GoalTwoBlocks(pos);
    }

    private Goal compositeMiningGoal() {
        CalculationContext context = new CalculationContext(baritone);
        Goal[] goals = knownTargets.stream()
                .filter(pos -> !unreachableTargets.contains(pos))
                .filter(pos -> baritone.getPlayerContext().world().getBlockState(pos).is(block))
                .filter(pos -> plausibleToBreak(context, pos))
                .filter(pos -> !Baritone.settings().allowOnlyExposedOres.value
                        || isNextToAir(context, pos))
                .filter(pos -> pos.getY() >= Baritone.settings().minYLevelWhileMining.value
                        + baritone.getPlayerContext().world().dimensionType().minY())
                .filter(pos -> pos.getY() <= Baritone.settings().maxYLevelWhileMining.value)
                .limit(Baritone.settings().mineMaxOreLocationsCount.value)
                .map(this::miningGoal)
                .toArray(Goal[]::new);
        return new GoalComposite(goals);
    }

    private static boolean plausibleToBreak(CalculationContext context, BlockPos pos) {
        BlockState state = context.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(
                context, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(
                context.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        return !(context.bsi.get0(pos.above()).is(Blocks.BEDROCK)
                && context.bsi.get0(pos.below()).is(Blocks.BEDROCK));
    }

    private static boolean isNextToAir(CalculationContext context, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(context.getBlock(
                                    pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void resolveCompositeDestination() {
        BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
        knownTargets.stream()
                .filter(pos -> !unreachableTargets.contains(pos))
                .filter(pos -> miningGoal(pos).isInGoal(feet))
                .min(Comparator.comparingDouble(feet::distSqr))
                .ifPresent(pos -> target = pos);
    }

    private void blacklistTargetAndContinue() {
        BlockPos rejected = target.immutable();
        unreachableTargets.add(rejected);
        target = null;
        pathAttempts = 0;
        internalMiningApproach = false;
        feedback.accept("无法到达 " + format(rejected) + "，已加入本次任务黑名单，继续寻找下一处");
    }

    public BlockPos protectedDropOrigin() {
        if (type != Type.MINE) {
            return null;
        }
        return collectionOrigin != null ? collectionOrigin : target;
    }

    public boolean isDesiredMiningDrop(ItemStack stack) {
        return type == Type.MINE && !stack.isEmpty()
                && desiredDropItems.contains(stack.getItem());
    }

    private void rememberDesiredDrops() {
        if (type != Type.MINE || target == null) return;
        ServerLevel world = baritone.getPlayerContext().world();
        BlockState state = world.getBlockState(target);
        ItemStack tool = baritone.getPlayerContext().player().getMainHandItem();
        Block.getDrops(state, world, target, world.getBlockEntity(target),
                baritone.getPlayerContext().player(), tool)
                .forEach(drop -> desiredDropItems.add(drop.getItem()));
        // Covers blocks whose loot table is conditional or supplied by another
        // mod. The normal block item is still a valid protected result.
        if (state.getBlock().asItem() != net.minecraft.world.item.Items.AIR) {
            desiredDropItems.add(state.getBlock().asItem());
        }
    }

    private static final class GoalThreeBlocks extends GoalTwoBlocks {
        private GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x
                    && (y == this.y || y == this.y - 1 || y == this.y - 2)
                    && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(
                    xDiff,
                    yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff,
                    zDiff
            );
        }
    }

    private BlockPos findNearest() {
        ServerPlayer player = baritone.getPlayerContext().player();
        BlockPos origin = player.blockPosition();
        int maxTargets = Baritone.settings().mineMaxOreLocationsCount.value;
        ServerWorldCache.registerTrackedBlocks(
                java.util.List.of(block));
        net.minecraft.world.level.chunk.LevelChunk current =
                baritone.getPlayerContext().world().getChunkSource()
                        .getChunkNow(origin.getX() >> 4, origin.getZ() >> 4);
        if (current != null) baritone.getWorldCache().capture(current);
        List<BlockPos> candidates = new ArrayList<>();
        candidates.addAll(baritone.getWorldCache().getLocationsOf(
                BuiltInRegistries.BLOCK.getKey(block).toString(),
                Baritone.settings().maxCachedWorldScanCount.value,
                origin.getX(), origin.getZ(), 2));
        candidates = new ArrayList<>(new java.util.LinkedHashSet<>(candidates));
        candidates.removeIf(candidate ->
                baritone.getPlayerContext().world().hasChunkAt(candidate)
                        && !baritone.getPlayerContext().world()
                                .getBlockState(candidate).is(block));
        candidates.removeAll(unreachableTargets);
        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        knownTargets = candidates.size() > maxTargets
                ? new ArrayList<>(candidates.subList(0, maxTargets))
                : candidates;
        return knownTargets.isEmpty() ? null : knownTargets.get(0);
    }

    private int findBlockSlot() {
        if (baritone.getInventoryController().selectBlock(block)) {
            return baritone.getPlayerContext().player().getInventory().getSelectedSlot();
        }
        ServerPlayer player = baritone.getPlayerContext().player();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                return slot;
            }
        }
        return -1;
    }

    private boolean withinReach(BlockPos pos) {
        return baritone.getPlayerContext().player().getEyePosition()
                .distanceToSqr(pos.getCenter())
                <= RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE * RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
    }

    public void cancel() {
        if (!finished) {
            finish("方块任务已停止");
        }
    }

    public boolean isFinished() {
        return finished;
    }

    public String status() {
        return type + " " + completedCount + "/" + requestedCount
                + (target == null ? "" : "，目标 " + format(target));
    }

    private void finish(String message) {
        finished = true;
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputController().tick();
        feedback.accept(message);
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
