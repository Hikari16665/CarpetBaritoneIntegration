package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/** Server adaptation of Baritone's crop harvesting and replanting process. */
public final class FarmProcess implements IFarmProcess {
    private final Baritone baritone;
    private boolean active;
    private int range;
    private BlockPos center;
    private BlockPos target;
    private Block harvestedBlock;
    private BlockPos replantAt;
    private int rescanDelay;
    private BlockPos cultivationTarget;
    private Cultivation cultivation;

    private enum Cultivation {
        FARMLAND, NETHER_WART, COCOA, BONE_MEAL
    }
    private static final Set<Item> FARM_DROPS = Set.of(
            Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO,
            Items.BEETROOT, Items.BEETROOT_SEEDS, Items.MELON_SLICE,
            Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.NETHER_WART,
            Items.COCOA_BEANS, Blocks.SUGAR_CANE.asItem(),
            Blocks.BAMBOO.asItem(), Blocks.CACTUS.asItem());

    public FarmProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void farm(int range, BlockPos pos) {
        center = pos == null ? baritone.getPlayerContext().playerFeet() : pos.immutable();
        this.range = range;
        active = true;
        target = null;
        replantAt = null;
        cultivationTarget = null;
        cultivation = null;
    }

    public void serverTick() {
        if (!active || baritone.getPathExecutor() != null) return;
        // Release the previous interaction before choosing this tick's
        // action. Otherwise a harvested target leaves ATTACK held while the
        // process scans or walks toward the next crop.
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputController().tick();
        if (replantAt != null && tryReplant()) return;
        if (cultivationTarget != null && tryCultivate()) return;
        if (target != null && !readyForHarvest(
                baritone.getPlayerContext().world(), target,
                baritone.getPlayerContext().world().getBlockState(target))) {
            if (baritone.getPlayerContext().world().getBlockState(target).isAir()) {
                replantAt = target;
            }
            target = null;
        }
        if (target == null && (rescanDelay-- <= 0)) {
            target = scanNearestMatureCrop();
            rescanDelay = Math.max(1, Baritone.settings().mineGoalUpdateInterval.value);
        }
        if (target == null) {
            if (collectNearestFarmDrop()) return;
            findCultivationTarget();
            return;
        }
        if (!baritone.getFakeInteractionController().canReach(target)) {
            if (!baritone.pathToGoal(
                    new BuilderProcess.GoalBreak(target), 2_000L, 8_000L)) {
                target = null;
            }
            return;
        }
        harvestedBlock = baritone.getPlayerContext().world()
                .getBlockState(target).getBlock();
        baritone.getFakeInteractionController().breakBlock(target);
    }

    private BlockPos scanNearestMatureCrop() {
        ServerLevel world = baritone.getPlayerContext().world();
        BlockPos player = baritone.getPlayerContext().playerFeet();
        int radius = range == 0 ? 32 : range;
        int max = Baritone.settings().farmMaxScanSize.value;
        return BlockPos.betweenClosedStream(
                        center.offset(-radius, -8, -radius),
                        center.offset(radius, 8, radius))
                .filter(pos -> range == 0 || pos.distSqr(center) <= range * range)
                .filter(pos -> readyForHarvest(world, pos, world.getBlockState(pos)))
                .limit(max)
                .min(Comparator.comparingDouble(player::distSqr))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private boolean collectNearestFarmDrop() {
        ItemEntity drop = baritone.getPlayerContext().entitiesStream()
                .filter(entity -> entity instanceof ItemEntity)
                .map(entity -> (ItemEntity) entity)
                .filter(entity -> entity.isAlive() && entity.onGround())
                .filter(entity -> FARM_DROPS.contains(entity.getItem().getItem()))
                .filter(entity -> !baritone.isTrashDrop(entity))
                .filter(entity -> range == 0
                        || entity.blockPosition().distSqr(center) <= range * range)
                .min(Comparator.comparingDouble(
                        baritone.getPlayerContext().player()::distanceToSqr))
                .orElse(null);
        if (drop == null) return false;
        if (!baritone.getFakeInteractionController().pickup(drop)) {
            baritone.pathToGoal(new GoalNear(drop.blockPosition(), 1), 2_000L, 5_000L);
        }
        return true;
    }

    /**
     * Ports the non-harvesting half of upstream FarmProcess. Candidate
     * locations are derived from current server block state on every scan;
     * there is no stale client/world-scanner cache.
     */
    private void findCultivationTarget() {
        ServerLevel world = baritone.getPlayerContext().world();
        BlockPos player = baritone.getPlayerContext().playerFeet();
        int radius = range == 0 ? 32 : range;
        int max = Baritone.settings().farmMaxScanSize.value;
        BlockPos best = null;
        Cultivation bestAction = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int candidates = 0;
        for (BlockPos mutable : BlockPos.betweenClosed(
                center.offset(-radius, -8, -radius),
                center.offset(radius, 8, radius))) {
            if (range != 0 && mutable.distSqr(center) > range * range) continue;
            BlockPos pos = mutable.immutable();
            BlockState state = world.getBlockState(pos);
            Cultivation action = cultivationAt(world, pos, state);
            if (action == null || !hasCultivationItem(action)) continue;
            if (candidates++ >= max) break;
            double distance = player.distSqr(pos);
            if (distance < bestDistance) {
                best = pos;
                bestAction = action;
                bestDistance = distance;
            }
        }
        cultivationTarget = best;
        cultivation = bestAction;
        if (cultivationTarget != null) {
            tryCultivate();
        }
    }

    private static Cultivation cultivationAt(
            ServerLevel world, BlockPos pos, BlockState state) {
        if (state.is(Blocks.FARMLAND) && world.getBlockState(pos.above()).isAir()) {
            return Cultivation.FARMLAND;
        }
        if (Baritone.settings().replantNetherWart.value
                && state.is(Blocks.SOUL_SAND)
                && world.getBlockState(pos.above()).isAir()) {
            return Cultivation.NETHER_WART;
        }
        if (state.is(Blocks.JUNGLE_LOG)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (world.getBlockState(pos.relative(direction)).isAir()) {
                    return Cultivation.COCOA;
                }
            }
        }
        if (state.getBlock() instanceof BonemealableBlock growable
                && growable.isValidBonemealTarget(world, pos, state)
                && growable.isBonemealSuccess(world, world.random, pos, state)) {
            return Cultivation.BONE_MEAL;
        }
        return null;
    }

    private boolean hasCultivationItem(Cultivation action) {
        return baritone.getInventoryController().hasAccessibleItem(
                stack -> cultivationItem(action, stack));
    }

    private static boolean cultivationItem(
            Cultivation action, ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (action) {
            case FARMLAND -> stack.is(Items.WHEAT_SEEDS)
                    || stack.is(Items.BEETROOT_SEEDS)
                    || stack.is(Items.PUMPKIN_SEEDS)
                    || stack.is(Items.MELON_SEEDS)
                    || stack.is(Items.CARROT)
                    || stack.is(Items.POTATO);
            case NETHER_WART -> stack.is(Items.NETHER_WART);
            case COCOA -> stack.is(Items.COCOA_BEANS);
            case BONE_MEAL -> stack.is(Items.BONE_MEAL);
        };
    }

    private boolean tryCultivate() {
        if (cultivationTarget == null || cultivation == null) return false;
        ServerLevel world = baritone.getPlayerContext().world();
        BlockState state = world.getBlockState(cultivationTarget);
        if (cultivationAt(world, cultivationTarget, state) != cultivation
                || !baritone.getInventoryController().selectItem(
                        stack -> cultivationItem(cultivation, stack))) {
            clearCultivation();
            return false;
        }
        BlockPos interaction = cultivationTarget;
        if (cultivation == Cultivation.FARMLAND
                || cultivation == Cultivation.NETHER_WART) {
            interaction = cultivationTarget.above();
        } else if (cultivation == Cultivation.COCOA) {
            interaction = cocoaAirPosition(world, cultivationTarget);
            if (interaction == null) {
                clearCultivation();
                return false;
            }
        }
        if (!baritone.getFakeInteractionController().canReach(interaction)) {
            if (!baritone.pathToGoal(
                    new GoalGetToBlock(cultivationTarget), 2_000L, 5_000L)) {
                clearCultivation();
            }
            return true;
        }
        boolean used = cultivation == Cultivation.BONE_MEAL
                ? baritone.getFakeInteractionController()
                        .useSelectedOnBlock(cultivationTarget)
                : baritone.getFakeInteractionController()
                        .useSelectedAt(interaction);
        if (used) clearCultivation();
        return true;
    }

    private static BlockPos cocoaAirPosition(
            ServerLevel world, BlockPos log) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = log.relative(direction);
            if (world.getBlockState(target).isAir()) return target;
        }
        return null;
    }

    private void clearCultivation() {
        cultivationTarget = null;
        cultivation = null;
    }

    private static boolean readyForHarvest(ServerLevel world, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return crop.isMaxAge(state);
        if (block == Blocks.NETHER_WART) return state.getValue(NetherWartBlock.AGE) >= 3;
        if (block == Blocks.COCOA) return state.getValue(CocoaBlock.AGE) >= 2;
        if (block == Blocks.PUMPKIN || block == Blocks.MELON) return true;
        if (block == Blocks.SUGAR_CANE) return world.getBlockState(pos.below()).is(Blocks.SUGAR_CANE);
        if (block == Blocks.BAMBOO) return world.getBlockState(pos.below()).is(Blocks.BAMBOO);
        return block == Blocks.CACTUS && world.getBlockState(pos.below()).is(Blocks.CACTUS);
    }

    private boolean tryReplant() {
        if (!Baritone.settings().replantCrops.value) {
            replantAt = null;
            return false;
        }
        Item seed = seedFor(harvestedBlock);
        if (seed == null || !baritone.getInventoryController()
                .selectItem(stack -> stack.is(seed))) {
            replantAt = null;
            return false;
        }
        BlockPos support = replantAt.below();
        if (!baritone.getFakeInteractionController().canReach(support)) {
            if (!baritone.pathToGoal(new GoalGetToBlock(support), 2_000L, 5_000L)) {
                replantAt = null;
            }
            return true;
        }
        baritone.getFakeInteractionController().useSelectedAt(replantAt);
        if (!baritone.getPlayerContext().world().getBlockState(replantAt).isAir()) {
            replantAt = null;
        }
        return true;
    }

    private static Item seedFor(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.NETHER_WART && Baritone.settings().replantNetherWart.value)
            return Items.NETHER_WART;
        return null;
    }

    @Override public boolean isActive() { return active; }
    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (target != null && baritone.getFakeInteractionController()
                .canReach(target)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(
                target == null ? null : new BuilderProcess.GoalBreak(target),
                PathingCommandType.SET_GOAL_AND_PATH);
    }
    public boolean isDesiredFarmDrop(net.minecraft.world.item.ItemStack stack) {
        return active && !stack.isEmpty() && FARM_DROPS.contains(stack.getItem());
    }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        active = false;
        target = null;
        replantAt = null;
        clearCultivation();
    }
    @Override public String displayName0() { return "Farming"; }
}
