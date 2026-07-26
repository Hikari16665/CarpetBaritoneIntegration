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

        if (calcFailed) {
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
                    feedback.accept("目标 " + format(closest)
                            + " 已用完整采矿预算失败两轮，暂时跳过 "
                            + cooldown / 20 + " 秒后会重新尝试");
                    lastSoleFailedTarget = null;
                    soleTargetFailureRounds = 0;
                } else {
                    feedback.accept("目标 " + format(closest)
                            + " 首轮寻路失败，保留目标并用完整预算重算");
                }
            } else if (closest != null) {
                // A composite search failure cannot be attributed to whichever
                // ore happens to be closest to the player.
                lastSoleFailedTarget = null;
                soleTargetFailureRounds = 0;
                feedback.accept("复合采矿路径暂未算出，保留全部 "
                        + knownOreLocations.size() + " 个矿物目标并重算");
            } else if (!Baritone.settings().exploreForBlocks.value) {
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

        if (tickCount++ % Math.max(1, Baritone.settings().mineGoalUpdateInterval.value) == 0
                || knownOreLocations.isEmpty()) {
            rescan();
        }
        prune();

        BlockPos reachable = knownOreLocations.stream()
                .filter(this::withinReach)
                .filter(pos -> filter.has(ctx.world().getBlockState(pos)))
                .min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
        if (reachable != null && isSafeToCancel) {
            java.util.Optional<Rotation> rotation = RotationUtils.reachable(ctx, reachable);
            if (rotation.isPresent()) {
                breaking = reachable;
                baritone.getInventoryController()
                        .ensureBestToolOnHotbar(
                                BlockStateInterface.get(ctx, reachable));
                MovementHelper.switchToBestToolFor(
                        ctx, BlockStateInterface.get(ctx, reachable),
                        new ToolSet(ctx.player()), Baritone.settings().preferSilkTouch.value);
                // Loot tables depend on the tool. Resolve the desired drops only
                // after upstream auto-tool selection, otherwise an ore first
                // reached with a sword can incorrectly produce an empty result.
                rememberDesiredDrops(reachable);
                baritone.getInputController().setBlockBreakTarget(reachable);
                baritone.getLookBehavior().updateTarget(rotation.get(), true);
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                baritone.getInputController().tick();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (!knownOreLocations.isEmpty()) {
            CalculationContext calculation = new CalculationContext(baritone);
            Goal[] goals = knownOreLocations.stream()
                    .map(pos -> coalesce(pos, calculation))
                    .toArray(Goal[]::new);
            return new PathingCommand(new GoalComposite(goals),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        if (!Baritone.settings().exploreForBlocks.value) {
            feedback.accept("当前已加载和缓存的区块中没有目标方块");
            onLostControl();
            return null;
        }
        if (branchPoint == null) branchPoint = ctx.playerFeet();
        if (branchGoal == null) {
            branchGoal = new GoalRunAway(1, ctx.playerFeet().getY(), branchPoint) {
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
        if (++collectionTicks > DROP_TIMEOUT_TICKS) {
            ignoredDrops.add(collecting.getUUID());
            feedback.accept("拾取目标掉落物超过 10 秒，继续挖掘");
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
            java.util.Optional<Rotation> rotation =
                    RotationUtils.reachable(ctx, pos);
            if (rotation.isEmpty()) continue;
            baritone.getInventoryController()
                    .ensureBestToolOnHotbar(state);
            MovementHelper.switchToBestToolFor(
                    ctx, state, new ToolSet(ctx.player()),
                    Baritone.settings().preferSilkTouch.value);
            baritone.getInputController().setBlockBreakTarget(pos);
            baritone.getLookBehavior().updateTarget(rotation.get(), true);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler()
                    .setInputForceState(Input.CLICK_LEFT, true);
            baritone.getInputController().tick();
            return true;
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
        int maximum = Math.max(1, Baritone.settings().mineMaxOreLocationsCount.value);
        LinkedHashSet<BlockPos> found = new LinkedHashSet<>(knownOreLocations);
        BetterBlockPos feet = ctx.playerFeet();
        for (BlockOptionalMeta selector : filter.blocks()) {
            Block block = selector.getBlock();
            found.addAll(baritone.getWorldCache().locationsOfNear(
                    block, feet.x, feet.z,
                    ctx.player().getServer().getPlayerList().getViewDistance(),
                    Math.max(maximum * 2,
                            Baritone.settings().maxCachedWorldScanCount.value)));
        }
        knownOreLocations.clear();
        knownOreLocations.addAll(found);
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
                        || Math.abs((pos.getX() >> 4) - playerChunkX)
                                > viewDistance
                        || Math.abs((pos.getZ() >> 4) - playerChunkZ)
                                > viewDistance
                        || pos.getY() < Baritone.settings().minYLevelWhileMining.value
                                + ctx.world().dimensionType().minY()
                        || pos.getY() > Baritone.settings().maxYLevelWhileMining.value
                        || (ctx.world().hasChunkAt(pos) && !filter.has(ctx.world().getBlockState(pos)))
                        || !plausibleToBreak(calculation, pos)
                        || (Baritone.settings().allowOnlyExposedOres.value
                                && !isNextToAir(calculation, pos)));
        knownOreLocations.sort(Comparator.comparingDouble(ctx.playerFeet()::distSqr));
        int maximum = Math.max(1, Baritone.settings().mineMaxOreLocationsCount.value);
        if (knownOreLocations.size() > maximum) {
            knownOreLocations.subList(maximum, knownOreLocations.size()).clear();
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
        double reach = RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
        return ctx.player().getEyePosition().distanceToSqr(pos.getCenter()) <= reach * reach;
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
                ctx.player().getInventory().getNonEquipmentItems());
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
        tickCount = 0;
        lastSoleFailedTarget = null;
        soleTargetFailureRounds = 0;
        branchPoint = null;
        branchGoal = null;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override public boolean isTemporary() { return false; }
    @Override public String displayName0() { return "Mine " + filter; }

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
