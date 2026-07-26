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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
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
    }

    public void serverTick() {
        if (!active || baritone.getPathExecutor() != null) return;
        // Release the previous interaction before choosing this tick's
        // action. Otherwise a harvested target leaves ATTACK held while the
        // process scans or walks toward the next crop.
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputController().tick();
        if (replantAt != null && tryReplant()) return;
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
            collectNearestFarmDrop();
            return;
        }
        Optional<Rotation> rotation = RotationUtils.reachable(
                baritone.getPlayerContext(), target, RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE);
        if (rotation.isEmpty()) {
            if (!baritone.pathToGoal(
                    new BuilderProcess.GoalBreak(target), 2_000L, 8_000L)) {
                target = null;
            }
            return;
        }
        harvestedBlock = baritone.getPlayerContext().world().getBlockState(target).getBlock();
        MovementHelper.switchToBestToolFor(
                baritone.getPlayerContext(),
                BlockStateInterface.get(baritone.getPlayerContext(), target));
        baritone.getInputController().setBlockBreakTarget(target);
        baritone.getLookBehavior().updateTarget(rotation.get(), true);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        baritone.getInputController().tick();
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

    private void collectNearestFarmDrop() {
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
        if (drop == null) return;
        if (baritone.getPlayerContext().player().distanceToSqr(drop) <= 4.0D) {
            Rotation rotation = RotationUtils.calcRotationFromVec3d(
                    baritone.getPlayerContext().playerHead(),
                    drop.position(),
                    baritone.getPlayerContext().playerRotations());
            baritone.getLookBehavior().updateTarget(rotation, false);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            baritone.getInputController().tick();
        } else {
            baritone.pathToGoal(new GoalNear(drop.blockPosition(), 1), 2_000L, 5_000L);
        }
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
        Optional<Rotation> rotation = RotationUtils.reachable(
                baritone.getPlayerContext(), support, RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE);
        if (rotation.isEmpty()) {
            if (!baritone.pathToGoal(new GoalGetToBlock(support), 2_000L, 5_000L)) {
                replantAt = null;
            }
            return true;
        }
        baritone.getLookBehavior().updateTarget(rotation.get(), true);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        baritone.getInputController().tick();
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
        if (target != null && RotationUtils.reachable(
                baritone.getPlayerContext(), target,
                RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE).isPresent()) {
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
    @Override public void onLostControl() { active = false; target = null; replantAt = null; }
    @Override public String displayName0() { return "Farming"; }
}
