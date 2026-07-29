package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.selection.ISelection;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.cache.ServerWorldCache;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Server adaptation of upstream MineProcess. Candidate selection, goal
 * coalescing, failure blacklisting and drop collection are owned by the
 * process manager instead of the legacy single-block task.
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess {
    private static final int DROP_TIMEOUT_TICKS = 200;

    private BlockOptionalMetaLookup filter;
    private final List<BlockPos> knownOreLocations = new ArrayList<>();
    private final Map<BlockPos, Long> blacklistUntil = new HashMap<>();
    private final Map<BlockPos, Long> anticipatedDrops = new HashMap<>();
    private final Set<UUID> ignoredDrops = new HashSet<>();
    private final Set<Item> desiredDropItems = new HashSet<>();
    private Consumer<String> feedback = ignored -> { };
    private int desiredQuantity;
    private int tickCount;
    private int initialQuantity;
    private BlockPos branchPoint;
    private GoalRunAway branchGoal;
    private BlockPos breaking;
    private ItemEntity collecting;
    private int collectionTicks;
    private int nextDropScanTick;
    private BlockPos lastSoleFailedTarget;
    private int soleTargetFailureRounds;
    private BlockPos areaMin;
    private BlockPos areaMax;
    private boolean areaMine;
    private int compositeBatchOffset;
    private int observedPathFailures;

    public MineProcess(Baritone baritone) {
        super(baritone);
    }

    @Override public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isActive()) return null;
        int inventoryCount = matchingInventoryCount();
        if (desiredQuantity > 0 && inventoryCount - initialQuantity >= desiredQuantity) {
            feedback.accept("已获得 " + (inventoryCount - initialQuantity) + " 个目标物品");
            onLostControl();
            return null;
        }

        int pathFailures = baritone.getConsecutivePathFailures();
        boolean newlyFailed = pathFailures > observedPathFailures;
        observedPathFailures = pathFailures;
        if (pathFailures == 0) observedPathFailures = 0;
        if (calcFailed || newlyFailed) {
            rotateCompositeBatch();
            BlockPos closest = knownOreLocations.stream()
                    .min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
            if (closest != null && knownOreLocations.size() == 1
                    && Baritone.settings().blacklistClosestOnFailure.value) {
                if (closest.equals(lastSoleFailedTarget)) {
                    soleTargetFailureRounds++;
                } else {
                    lastSoleFailedTarget = closest;
                    soleTargetFailureRounds = 1;
                }
                if (soleTargetFailureRounds >= 2) {
                    long cooldown = Math.max(1,
                            Baritone.settings().mineBlacklistCooldownTicks.value);
                    blacklistUntil.put(
                            closest, (long) tickCount + cooldown);
                    knownOreLocations.remove(closest);
                    lastSoleFailedTarget = null;
                    soleTargetFailureRounds = 0;
                } else {
                }
            } else if (closest != null) {
                // A composite search failure cannot be attributed to whichever
                // ore happens to be closest to the player.
                lastSoleFailedTarget = null;
                soleTargetFailureRounds = 0;
            } else if (!areaMine
                    && !Baritone.settings().exploreForBlocks.value) {
                feedback.accept("没有可到达的目标方块，挖掘任务结束");
                onLostControl();
                return null;
            }
            rescan();
        }

        updateAnticipatedDrops();
        if (handleDropCollection()) {
            if (collecting != null && ctx.player().distanceToSqr(collecting) > 2.25D) {
                return new PathingCommand(
                        new GoalNear(collecting.blockPosition(), 1),
                        PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        int rescanInterval = areaMine ? 20 : Math.max(
                1, Baritone.settings().mineGoalUpdateInterval.value);
        if (tickCount++ % rescanInterval == 0) {
            if (Baritone.settings().legitMine.value && !areaMine) {
                scanLegitNearby();
            } else {
                rescan();
            }
        } else if (!areaMine && knownOreLocations.isEmpty()
                && !Baritone.settings().legitMine.value) {
            rescan();
        }
        prune();

        BlockPos reachable = knownOreLocations.stream()
                .filter(this::withinReach)
                .filter(pos -> baritone.getFakeInteractionController()
                        .canBreakFromHere(pos))
                .filter(pos -> filter.has(ctx.world().getBlockState(pos)))
                .min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
        if (reachable != null && isSafeToCancel) {
            compositeBatchOffset = 0;
            breaking = reachable;
            baritone.getInventoryController().ensureBestToolOnHotbar(
                    BlockStateInterface.get(ctx, reachable));
            rememberDesiredDrops(reachable);
            if (baritone.getFakeInteractionController()
                    .breakBlock(reachable)) {
                anticipatedDrops.put(
                        reachable, (long) tickCount + DROP_TIMEOUT_TICKS);
                breaking = null;
                nextDropScanTick = Math.min(nextDropScanTick, tickCount);
            }
            return new PathingCommand(
                    null, PathingCommandType.REQUEST_PAUSE);
        }

        if (!knownOreLocations.isEmpty()) {
            CalculationContext calculation = new CalculationContext(baritone);
            List<BlockPos> planned = currentGoalBatch();
            Goal[] goals = planned.stream()
                    .map(pos -> coalesce(pos, calculation))
                    .toArray(Goal[]::new);
            return new PathingCommand(new GoalComposite(goals),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        if (areaMine) {
            return new PathingCommand(
                    null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!Baritone.settings().exploreForBlocks.value) {
            feedback.accept("当前已加载和缓存的区块中没有目标方块");
            onLostControl();
            return null;
        }
        if (branchPoint == null) branchPoint = ctx.playerFeet();
        if (branchGoal == null) {
            int miningY = Baritone.settings().legitMine.value
                    ? Baritone.settings().legitMineYLevel.value
                    : ctx.playerFeet().getY();
            branchGoal = new GoalRunAway(1, miningY, branchPoint) {
                @Override public boolean isInGoal(int x, int y, int z) { return false; }
                @Override public double heuristic() { return Double.NEGATIVE_INFINITY; }
            };
        }
        return new PathingCommand(branchGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private boolean handleDropCollection() {
        if (breaking != null && !filter.has(ctx.world().getBlockState(breaking))) {
            anticipatedDrops.put(breaking, (long) tickCount + DROP_TIMEOUT_TICKS);
            breaking = null;
        }
        if (collecting != null && (!collecting.isAlive()
                || collecting.getItem().isEmpty()
                || ignoredDrops.contains(collecting.getUUID()))) {
            collecting = null;
            collectionTicks = 0;
        }
        if (collecting == null) {
            collecting = nearestDesiredDrop();
            collectionTicks = 0;
        }
        if (collecting == null) return false;
        if (baritone.getFakeInteractionController().pickup(collecting)) {
            collecting = null;
            collectionTicks = 0;
            return false;
        }
        if (++collectionTicks > DROP_TIMEOUT_TICKS) {
            ignoredDrops.add(collecting.getUUID());
            collecting = null;
            collectionTicks = 0;
            return false;
        }
        if (ctx.player().distanceToSqr(collecting) <= 2.25D) {
            if (clearDropAccess()) {
                // Clearing a pickup pocket is useful progress. Do not let the
                // ordinary ten-second pickup timeout expire halfway through a
                // few legitimately mineable obstruction blocks.
                collectionTicks = Math.min(
                        collectionTicks, DROP_TIMEOUT_TICKS / 2);
                return true;
            }
            Rotation rotation = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(), collecting.position(), ctx.playerRotations())
                    .withPitch(ctx.playerRotations().getPitch());
            baritone.getLookBehavior().updateTarget(rotation, false);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            baritone.getInputController().tick();
        }
        return true;
    }

    /**
     * A dropped item one block forward and one block down can be within the
     * pickup goal radius while still separated by a solid lip or wall. In
     * that case MOVE_FORWARD never makes progress. Clear the ray obstruction
     * first, then a small pickup pocket at the item's level and above.
     */
    private boolean clearDropAccess() {
        if (collecting == null) return false;
        Vec3 eyes = ctx.player().getEyePosition();
        Vec3 item = collecting.position().add(0.0D, 0.2D, 0.0D);
        List<BlockPos> candidates = new ArrayList<>();
        HitResult hit = ctx.world().clip(new ClipContext(
                eyes, item,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                ctx.player()));
        BlockPos obstruction = hit instanceof BlockHitResult blockHit
                && hit.getType() == HitResult.Type.BLOCK
                ? blockHit.getBlockPos().immutable() : null;
        if (originSupport(collecting).equals(obstruction)) {
            obstruction = null;
        }
        // With a clear ray, give ordinary pickup movement half a second
        // before widening the pocket. This avoids mining harmless neighboring
        // walls whenever an item is already directly collectible.
        if (obstruction == null && collectionTicks < 10) return false;
        if (obstruction != null) candidates.add(obstruction);
        final BlockPos primaryObstruction = obstruction;

        BlockPos origin = collecting.blockPosition();
        // Never include origin.below(): it normally supports the item and
        // breaking it would make the drop fall farther away.
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!candidates.contains(pos)) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        CalculationContext calculation = new CalculationContext(baritone);
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) ->
                        pos.equals(primaryObstruction) ? 0
                                : pos.equals(origin) ? 1
                                : pos.getY() == origin.getY() ? 2 : 3)
                .thenComparingDouble(pos ->
                        ctx.player().getEyePosition().distanceToSqr(
                                pos.getCenter())));
        for (BlockPos pos : candidates) {
            BlockState state = ctx.world().getBlockState(pos);
            if (state.isAir()
                    || !plausibleToBreak(calculation, pos)) continue;
            if (baritone.getFakeInteractionController().canReach(pos)) {
                return baritone.getFakeInteractionController()
                        .breakBlock(pos);
            }
        }
        return false;
    }

    private static BlockPos originSupport(ItemEntity entity) {
        return entity.blockPosition().below();
    }

    private ItemEntity nearestDesiredDrop() {
        if (anticipatedDrops.isEmpty()) return null;
        if (tickCount < nextDropScanTick) return null;
        nextDropScanTick = tickCount + 2;
        ServerPlayer player = ctx.player();
        return anticipatedDrops.keySet().stream()
                .flatMap(origin -> ctx.world().getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(origin).inflate(8.0D),
                        entity -> entity.isAlive()
                                && !entity.getItem().isEmpty()
                                && !ignoredDrops.contains(entity.getUUID())
                                && isDesiredMiningDrop(entity.getItem())
                                && !baritone.isTrashDrop(entity)).stream())
                .distinct()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private void updateAnticipatedDrops() {
        anticipatedDrops.entrySet().removeIf(entry -> entry.getValue() < tickCount);
    }

    private void rescan() {
        if (Baritone.settings().legitMine.value && !areaMine) {
            scanLegitNearby();
            return;
        }
        int maximum = Math.max(1, Baritone.settings().mineMaxOreLocationsCount.value);
        LinkedHashSet<BlockPos> found = new LinkedHashSet<>(knownOreLocations);
        BetterBlockPos feet = ctx.playerFeet();
        for (BlockOptionalMeta selector : filter.blocks()) {
            Block block = selector.getBlock();
            found.addAll(baritone.getWorldCache().locationsOfNear(
                    block, feet.x, feet.z,
                    areaMine ? areaSearchRadiusChunks()
                            : ctx.player().getServer().getPlayerList()
                            .getViewDistance(),
                    Math.max(maximum * 2,
                            Baritone.settings().maxCachedWorldScanCount.value)));
        }
        knownOreLocations.clear();
        knownOreLocations.addAll(found);
        prune();
    }

    /**
     * Upstream legit-mine discovery: only accept ores that a player could
     * observe from the current position, then optionally expand through
     * directly/diagonally connected members of the same vein.
     */
    private void scanLegitNearby() {
        if (filter == null) return;
        BlockPos feet = ctx.playerFeet();
        int distance = 10;
        double visibilityReach = 20.0D;
        Set<BlockPos> known = new HashSet<>(knownOreLocations);
        List<BlockPos> newlyVisible = new ArrayList<>();
        for (int x = feet.getX() - distance;
             x <= feet.getX() + distance; x++) {
            for (int y = feet.getY() - distance;
                 y <= feet.getY() + distance; y++) {
                for (int z = feet.getZ() - distance;
                     z <= feet.getZ() + distance; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!filter.has(ctx.world().getBlockState(pos))) continue;
                    boolean veinAdjacent = Baritone.settings()
                            .legitMineIncludeDiagonals.value
                            && known.stream().anyMatch(
                                    ore -> ore.distSqr(pos) <= 2.0D);
                    if (veinAdjacent || RotationUtils.reachable(
                            ctx, pos, visibilityReach).isPresent()) {
                        newlyVisible.add(pos);
                        known.add(pos);
                    }
                }
            }
        }
        knownOreLocations.addAll(newlyVisible);
        prune();
    }

    private void prune() {
        blacklistUntil.entrySet().removeIf(
                entry -> entry.getValue() <= tickCount);
        CalculationContext calculation = new CalculationContext(baritone);
        int viewDistance = ctx.player().getServer().getPlayerList()
                .getViewDistance();
        int playerChunkX = ctx.playerFeet().x >> 4;
        int playerChunkZ = ctx.playerFeet().z >> 4;
        knownOreLocations.removeIf(pos ->
                blacklistUntil.containsKey(pos)
                        || areaMine && !insideArea(pos)
                        || !areaMine && (Math.abs(
                        (pos.getX() >> 4) - playerChunkX)
                                > viewDistance
                        || Math.abs((pos.getZ() >> 4) - playerChunkZ)
                                > viewDistance)
                        || !areaMine && (pos.getY()
                        < Baritone.settings().minYLevelWhileMining.value
                                + ctx.world().dimensionType().minY()
                        || pos.getY()
                        > Baritone.settings().maxYLevelWhileMining.value)
                        || (ctx.world().hasChunkAt(pos) && !filter.has(ctx.world().getBlockState(pos)))
                        || !plausibleToBreak(calculation, pos)
                        || (Baritone.settings().allowOnlyExposedOres.value
                                && !isNextToAir(calculation, pos)));
        knownOreLocations.sort(Comparator.comparingDouble(ctx.playerFeet()::distSqr));
        int maximum = Math.max(1, Baritone.settings().mineMaxOreLocationsCount.value);
        if (knownOreLocations.size() > maximum) {
            knownOreLocations.subList(maximum, knownOreLocations.size()).clear();
        }
        if (knownOreLocations.isEmpty()) {
            compositeBatchOffset = 0;
        } else {
            compositeBatchOffset = Math.floorMod(
                    compositeBatchOffset, knownOreLocations.size());
        }
    }

    private List<BlockPos> currentGoalBatch() {
        int size = knownOreLocations.size();
        if (size <= 1) return List.copyOf(knownOreLocations);
        int configured = Math.max(1,
                Baritone.settings().mineGoalCompositeBatchSize.value);
        int distance = Math.max(1,
                Baritone.settings().mineGoalBatchingDistance.value);
        double nearestSq = ctx.playerFeet().distSqr(
                knownOreLocations.getFirst());
        if (nearestSq <= (double) distance * distance
                || size <= configured) {
            return List.copyOf(knownOreLocations);
        }
        int count = Math.min(configured, size);
        List<BlockPos> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(knownOreLocations.get(Math.floorMod(
                    compositeBatchOffset + index, size)));
        }
        return result;
    }

    private void rotateCompositeBatch() {
        int size = knownOreLocations.size();
        if (size <= 1) return;
        int distance = Math.max(1,
                Baritone.settings().mineGoalBatchingDistance.value);
        if (ctx.playerFeet().distSqr(knownOreLocations.getFirst())
                <= (double) distance * distance) {
            compositeBatchOffset = 0;
            return;
        }
        int batch = Math.max(1,
                Baritone.settings().mineGoalCompositeBatchSize.value);
        compositeBatchOffset = Math.floorMod(
                compositeBatchOffset + batch, size);
        if (Baritone.settings().diagnosticLogging.value) {
            System.out.println("[CBI-DIAG] mine-goal-batch player="
                    + ctx.player().getScoreboardName()
                    + " offset=" + compositeBatchOffset
                    + " batch=" + Math.min(batch, size)
                    + " total=" + size);
        }
    }

    private Goal coalesce(BlockPos location, CalculationContext calculation) {
        boolean shaftSafe = !(calculation.bsi.get0(location.above()).getBlock() instanceof FallingBlock);
        if (!Baritone.settings().forceInternalMining.value) {
            return shaftSafe ? new GoalThreeBlocks(location) : new GoalTwoBlocks(location);
        }
        boolean above = internalGoal(location.above(), calculation);
        boolean below = internalGoal(location.below(), calculation);
        boolean twoBelow = internalGoal(location.below(2), calculation);
        if (above == below) {
            return twoBelow && shaftSafe ? new GoalThreeBlocks(location) : new GoalTwoBlocks(location);
        }
        if (above) return new GoalBlock(location);
        if (twoBelow && shaftSafe) return new GoalTwoBlocks(location.below());
        return new GoalBlock(location.below());
    }

    private boolean internalGoal(BlockPos pos, CalculationContext calculation) {
        if (knownOreLocations.contains(pos)) return true;
        BlockState state = calculation.bsi.get0(pos);
        return (Baritone.settings().internalMiningAirException.value
                && state.getBlock() instanceof AirBlock)
                || filter.has(state) && plausibleToBreak(calculation, pos);
    }

    public static boolean plausibleToBreak(CalculationContext calculation, BlockPos pos) {
        BlockState state = calculation.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(calculation,
                pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) return false;
        if (MovementHelper.avoidBreaking(calculation.bsi,
                pos.getX(), pos.getY(), pos.getZ(), state)) return false;
        return !(calculation.bsi.get0(pos.above()).is(Blocks.BEDROCK)
                && calculation.bsi.get0(pos.below()).is(Blocks.BEDROCK));
    }

    public static boolean isNextToAir(CalculationContext calculation, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                        && MovementHelper.isTransparent(calculation.getBlock(
                                pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) return true;
            }
        }
        return false;
    }

    private boolean withinReach(BlockPos pos) {
        return baritone.getFakeInteractionController().canReach(pos);
    }

    private void rememberDesiredDrops(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Block.getDrops(state, ctx.world(), pos, ctx.world().getBlockEntity(pos),
                ctx.player(), ctx.player().getMainHandItem())
                .forEach(stack -> desiredDropItems.add(stack.getItem()));
        if (state.getBlock().asItem() != net.minecraft.world.item.Items.AIR) {
            desiredDropItems.add(state.getBlock().asItem());
        }
    }

    private int matchingInventoryCount() {
        return baritone.getInventoryController().countAccessible(
                stack -> filter.has(stack)
                        || desiredDropItems.contains(stack.getItem()));
    }

    private void primeDesiredDrops() {
        BlockPos samplePos = ctx.playerFeet();
        List<ItemStack> tools = new ArrayList<>(
                ctx.player().getInventory().items);
        tools.add(ItemStack.EMPTY);
        for (BlockOptionalMeta selector : filter.blocks()) {
            Block block = selector.getBlock();
            if (block.asItem() != net.minecraft.world.item.Items.AIR) {
                desiredDropItems.add(block.asItem());
            }
            for (ItemStack tool : tools) {
                Block.getDrops(block.defaultBlockState(), ctx.world(),
                                samplePos, null, ctx.player(), tool)
                        .forEach(drop ->
                                desiredDropItems.add(drop.getItem()));
            }
        }
    }

    @Override public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override public void mine(int quantity, BlockOptionalMetaLookup filter) {
        mineWithFeedback(quantity, filter, ignored -> { });
    }

    public void mineWithFeedback(int quantity, BlockOptionalMetaLookup requested, Consumer<String> feedback) {
        onLostControl();
        if (requested == null || requested.blocks().isEmpty()) return;
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMeta[] allowed = requested.blocks().stream()
                    .filter(entry -> Baritone.settings().allowBreakAnyway.value.contains(entry.getBlock()))
                    .toArray(BlockOptionalMeta[]::new);
            requested = new BlockOptionalMetaLookup(allowed);
            if (requested.blocks().isEmpty()) {
                feedback.accept("allowBreak 已关闭，目标也不在 allowBreakAnyway 中");
                return;
            }
        }
        baritone.cancelLegacyBlockTask();
        this.filter = requested;
        primeDesiredDrops();
        ServerWorldCache.registerTrackedBlocks(
                requested.blocks().stream()
                        .map(BlockOptionalMeta::getBlock).toList());
        net.minecraft.world.level.chunk.LevelChunk currentChunk =
                ctx.world().getChunkSource().getChunkNow(
                        ctx.playerFeet().x >> 4, ctx.playerFeet().z >> 4);
        if (currentChunk != null) {
            baritone.getWorldCache().capture(currentChunk);
        }
        this.desiredQuantity = Math.max(0, quantity);
        this.feedback = feedback == null ? ignored -> { } : feedback;
        this.initialQuantity = matchingInventoryCount();
        rescan();
    }

    @Override
    public void mineArea(
            ISelection selection, BlockOptionalMetaLookup requested) {
        mineAreaWithFeedback(selection, requested, ignored -> { });
    }

    public void mineAreaWithFeedback(
            ISelection selection, BlockOptionalMetaLookup requested,
            Consumer<String> feedback) {
        mineWithFeedback(0, requested, feedback);
        if (!isActive()) return;
        this.areaMin = selection.min().immutable();
        this.areaMax = selection.max().immutable();
        this.areaMine = true;
        this.knownOreLocations.clear();
        BlockPos center = new BlockPos(
                (areaMin.getX() + areaMax.getX()) / 2,
                (areaMin.getY() + areaMax.getY()) / 2,
                (areaMin.getZ() + areaMax.getZ()) / 2);
        baritone.getWorldCache().queueCaptureAround(
                center, areaSelectionRadiusChunks());
        rescan();
    }

    private boolean insideArea(BlockPos pos) {
        return areaMin != null
                && pos.getX() >= areaMin.getX()
                && pos.getX() <= areaMax.getX()
                && pos.getY() >= areaMin.getY()
                && pos.getY() <= areaMax.getY()
                && pos.getZ() >= areaMin.getZ()
                && pos.getZ() <= areaMax.getZ();
    }

    /**
     * locationsOfNear is centered on the fake player, so include both the
     * distance to the selection and its farthest corner.
     */
    private int areaSearchRadiusChunks() {
        if (areaMin == null || areaMax == null) return 0;
        int dx = Math.max(
                Math.abs(ctx.playerFeet().x - areaMin.getX()),
                Math.abs(ctx.playerFeet().x - areaMax.getX()));
        int dz = Math.max(
                Math.abs(ctx.playerFeet().z - areaMin.getZ()),
                Math.abs(ctx.playerFeet().z - areaMax.getZ()));
        return Math.max(dx, dz) / 16 + 2;
    }

    private int areaSelectionRadiusChunks() {
        if (areaMin == null || areaMax == null) return 0;
        int width = areaMax.getX() - areaMin.getX() + 1;
        int length = areaMax.getZ() - areaMin.getZ() + 1;
        return Math.max(width, length) / 32 + 2;
    }

    public BlockPos protectedDropOrigin() {
        if (breaking != null) return breaking;
        return anticipatedDrops.keySet().stream()
                .min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
    }

    public boolean isDesiredMiningDrop(ItemStack stack) {
        return isActive() && !stack.isEmpty()
                && (desiredDropItems.contains(stack.getItem()) || filter.has(stack));
    }

    public boolean isProtectedDesiredDrop(ItemEntity entity) {
        if (!isDesiredMiningDrop(entity.getItem())) return false;
        return anticipatedDrops.keySet().stream().anyMatch(origin ->
                origin.distSqr(entity.blockPosition()) <= 64.0D);
    }

    @Override public void onLostControl() {
        filter = null;
        knownOreLocations.clear();
        blacklistUntil.clear();
        anticipatedDrops.clear();
        ignoredDrops.clear();
        desiredDropItems.clear();
        breaking = null;
        collecting = null;
        collectionTicks = 0;
        nextDropScanTick = 0;
        tickCount = 0;
        lastSoleFailedTarget = null;
        soleTargetFailureRounds = 0;
        branchPoint = null;
        branchGoal = null;
        areaMin = null;
        areaMax = null;
        areaMine = false;
        compositeBatchOffset = 0;
        observedPathFailures = 0;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override public boolean isTemporary() { return false; }
    @Override public String displayName0() {
        return (areaMine ? "AreaMine " : "Mine ") + filter;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static final class GoalThreeBlocks extends GoalTwoBlocks {
        private GoalThreeBlocks(BlockPos pos) { super(pos); }
        @Override public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2)
                    && z == this.z;
        }
        @Override public double heuristic(int x, int y, int z) {
            int dx = x - this.x, dy = y - this.y, dz = z - this.z;
            return GoalBlock.calculate(dx, dy < -1 ? dy + 2 : dy == -1 ? 0 : dy, dz);
        }
    }
}
