package baritone.server;

import baritone.Baritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.MovementHelper;
import baritone.cache.ServerWorldCache;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Server-authoritative fake interactions. Looking is retained as visual
 * feedback, but success never depends on the player's crosshair or a queued
 * Carpet mouse action.
 */
public final class ServerFakeInteractionController {
    private final Baritone baritone;
    private final ServerPlayer player;
    private final PrinterPlacementGuide printerGuide =
            new PrinterPlacementGuide();
    private final PrinterPlacementQueue printerQueue =
            new PrinterPlacementQueue();
    private BlockPos activeBreakTarget;
    private double breakProgress;
    private long lastBreakProgressTick = Long.MIN_VALUE;
    private long lastBreakRequestTick = Long.MIN_VALUE;
    private long nextBreakAllowedTick;

    public ServerFakeInteractionController(Baritone baritone) {
        this.baritone = Objects.requireNonNull(baritone);
        this.player = baritone.getPlayerContext().player();
    }

    public boolean canReach(BlockPos pos) {
        double reach = RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
        Vec3 eye = player.getEyePosition();
        double closestX = Math.max(pos.getX(),
                Math.min(eye.x, pos.getX() + 1.0D));
        double closestY = Math.max(pos.getY(),
                Math.min(eye.y, pos.getY() + 1.0D));
        double closestZ = Math.max(pos.getZ(),
                Math.min(eye.z, pos.getZ() + 1.0D));
        double dx = eye.x - closestX;
        double dy = eye.y - closestY;
        double dz = eye.z - closestZ;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    public void lookAt(BlockPos pos) {
        Rotation rotation = RotationUtils.calcRotationFromVec3d(
                baritone.getPlayerContext().playerHead(),
                pos.getCenter(),
                baritone.getPlayerContext().playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, true);
        // Apply only the visual rotation. No attack/use input is emitted.
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputController().tick();
    }

    public boolean breakBlock(BlockPos pos) {
        return breakBlock(pos, true);
    }

    /**
     * Used by clean after its standing node has already passed a world-ray
     * visibility check. Reach and timed mining still apply.
     */
    public boolean breakBlockTheoreticallyReachable(BlockPos pos) {
        return breakBlock(pos, false);
    }

    private boolean breakBlock(BlockPos pos, boolean requireCurrentRay) {
        long gameTime = player.level().getGameTime();
        lastBreakRequestTick = gameTime;
        if (!canReach(pos)
                || requireCurrentRay && !canBreakFromHere(pos)) {
            diagnosticBreak(pos, "rejected reach=" + canReach(pos)
                    + " requireRay=" + requireCurrentRay
                    + " rayHit=" + rayHitPosition(pos));
            if (pos.equals(activeBreakTarget)) resetBreakProgress();
            return false;
        }
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) {
            if (pos.equals(activeBreakTarget)) resetBreakProgress();
            return true;
        }
        if (gameTime < nextBreakAllowedTick) return false;
        boolean startedBreaking = !pos.equals(activeBreakTarget);
        if (startedBreaking) {
            resetBreakProgress();
            activeBreakTarget = pos.immutable();
        }
        baritone.getInventoryController().ensureBestToolOnHotbar(state);
        MovementHelper.switchToBestToolFor(
                baritone.getPlayerContext(), state);
        lookAt(pos);
        if (startedBreaking || gameTime % 6L == 0L) {
            player.swing(InteractionHand.MAIN_HAND, true);
        }
        if (player.getAbilities().instabuild) {
            return finishBreak(pos, gameTime);
        }
        if (lastBreakProgressTick == gameTime) return false;
        lastBreakProgressTick = gameTime;
        double increment = state.getDestroyProgress(
                player, player.level(), pos);
        if (!(increment > 0D) || !Double.isFinite(increment)) {
            float hardness = state.getDestroySpeed(player.level(), pos);
            if (hardness == 0.0F) {
                // Plants and other zero-hardness blocks are valid
                // instantaneous breaks. getDestroyProgress returns zero for
                // them, which must not be confused with an unbreakable block.
                player.level().destroyBlockProgress(
                        player.getId(), pos, 9);
                diagnosticBreak(pos, "instant zero-hardness state="
                        + state + " held=" + player.getMainHandItem());
                return finishBreak(pos, gameTime);
            }
            diagnosticBreak(pos, "zero-progress state=" + state
                    + " hardness=" + hardness
                    + " held=" + player.getMainHandItem());
            return false;
        }
        breakProgress += increment;
        diagnosticBreak(pos, "progress="
                + String.format(java.util.Locale.ROOT, "%.3f", breakProgress)
                + " increment="
                + String.format(java.util.Locale.ROOT, "%.5f", increment)
                + " held=" + player.getMainHandItem());
        player.level().destroyBlockProgress(
                player.getId(), pos,
                Math.min(9, Math.max(0,
                        (int) (breakProgress * 10.0D))));
        if (breakProgress < 1.0D) return false;
        return finishBreak(pos, gameTime);
    }

    /** Fake interaction removes crosshair alignment, not solid occlusion. */
    public boolean canBreakFromHere(BlockPos pos) {
        if (!canReach(pos)) return false;
        return findVisibleBreakPoint(pos) != null;
    }

    private Vec3 findVisibleBreakPoint(BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3[] samples = {
                pos.getCenter(),
                pos.getCenter().add(0.499D, 0D, 0D),
                pos.getCenter().add(-0.499D, 0D, 0D),
                pos.getCenter().add(0D, 0.499D, 0D),
                pos.getCenter().add(0D, -0.499D, 0D),
                pos.getCenter().add(0D, 0D, 0.499D),
                pos.getCenter().add(0D, 0D, -0.499D)
        };
        double reach = RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
        for (Vec3 sample : samples) {
            if (eye.distanceToSqr(sample) > reach * reach) continue;
            HitResult hit = player.level().clip(new ClipContext(
                    eye, sample, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            // Blocks with a very small or empty outline (for example short
            // grass) may produce MISS even though the segment reached the
            // requested sample without crossing an occluding block.
            if (hit.getType() == HitResult.Type.MISS) {
                return sample;
            }
            if (hit instanceof BlockHitResult blockHit
                    && hit.getType() == HitResult.Type.BLOCK
                    && blockHit.getBlockPos().equals(pos)) {
                return sample;
            }
        }
        return null;
    }

    private BlockPos rayHitPosition(BlockPos pos) {
        HitResult hit = player.level().clip(new ClipContext(
                player.getEyePosition(), pos.getCenter(),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        return hit instanceof BlockHitResult blockHit
                ? blockHit.getBlockPos() : null;
    }

    private void diagnosticBreak(BlockPos pos, String detail) {
        if (Baritone.settings().diagnosticLogging.value
                && player.level().getGameTime() % 20L == 0L) {
            System.out.println("[CBI-DIAG] break player="
                    + player.getScoreboardName() + " target=" + pos
                    + " feet=" + player.blockPosition() + " " + detail);
        }
    }

    public void serverTick() {
        long gameTime = player.level().getGameTime();
        if (activeBreakTarget != null
                && lastBreakRequestTick < gameTime) {
            resetBreakProgress();
        }
    }

    private boolean finishBreak(BlockPos pos, long gameTime) {
        boolean destroyed = player.gameMode.destroyBlock(pos);
        if (destroyed && player.level() instanceof ServerLevel level
                && Baritone.settings().repackOnAnyBlockChange.value) {
            ServerWorldCache.get(level).invalidateChunk(
                    pos.getX() >> 4, pos.getZ() >> 4);
        }
        resetBreakProgress();
        if (destroyed) {
            nextBreakAllowedTick = gameTime
                    + Math.max(1, Baritone.settings()
                            .blockBreakSpeed.value);
        }
        return destroyed;
    }

    private void resetBreakProgress() {
        if (activeBreakTarget != null) {
            player.level().destroyBlockProgress(
                    player.getId(), activeBreakTarget, -1);
        }
        activeBreakTarget = null;
        breakProgress = 0D;
        lastBreakProgressTick = Long.MIN_VALUE;
    }

    /**
     * Places the selected BlockItem at {@code target} through BlockItem's
     * normal placement rules using a fabricated valid support-face hit.
     */
    public boolean placeSelectedBlock(BlockPos target) {
        if (!canReach(target)) return false;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        BlockState current = player.level().getBlockState(target);
        if (!current.canBeReplaced()) return false;
        if (useSelectedAt(target)
                && !player.level().getBlockState(target).canBeReplaced()) {
            baritone.getCleanProcess().recordPlacedSupport(target);
            return true;
        }
        return placeFullBlockDirect(target, blockItem, stack);
    }

    /** Printer water transaction for schematic source-fluid cells. */
    public boolean placeFluid(BlockPos target, FluidState desired) {
        BlockState current = player.level().getBlockState(target);
        boolean replaceableFlow = !current.getFluidState().isEmpty()
                && current.getFluidState().getType() == desired.getType()
                && !current.getFluidState().isSource();
        if (!canReach(target)
                || desired.isEmpty()
                || !desired.isSource()
                || !current.canBeReplaced() && !replaceableFlow) {
            return false;
        }
        if (!printerQueue.ready(target)) return true;
        ItemStack required;
        if (desired.is(Fluids.WATER)) {
            required = new ItemStack(Items.WATER_BUCKET);
        } else if (desired.is(Fluids.LAVA)) {
            required = new ItemStack(Items.LAVA_BUCKET);
        } else {
            return false;
        }
        int slot = findInventoryItem(required);
        if (slot < 0) return false;
        if (!player.level().setBlockAndUpdate(
                target, desired.createLegacyBlock())) return false;
        consumeBucket(slot, Items.BUCKET);
        printerQueue.record(target);
        lookAt(target);
        return true;
    }

    /** Removes a source fluid using an empty bucket, preventing refill loops. */
    public boolean pickupFluid(BlockPos target) {
        if (!canReach(target)) return false;
        FluidState fluid = player.level().getFluidState(target);
        if (!fluid.isSource()) return false;
        int slot = findInventoryItem(new ItemStack(Items.BUCKET));
        if (slot < 0) return false;
        if (!printerQueue.ready(target)) return true;
        ItemStack filled = fluid.is(Fluids.WATER)
                ? new ItemStack(Items.WATER_BUCKET)
                : fluid.is(Fluids.LAVA)
                ? new ItemStack(Items.LAVA_BUCKET) : ItemStack.EMPTY;
        if (filled.isEmpty()) return false;
        if (!player.level().setBlockAndUpdate(
                target, Blocks.AIR.defaultBlockState())) return false;
        consumeBucket(slot, filled.getItem());
        printerQueue.record(target);
        lookAt(target);
        return true;
    }

    private int findInventoryItem(ItemStack wanted) {
        for (int slot = 0;
             slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(wanted.getItem())) return slot;
        }
        return -1;
    }

    private void consumeBucket(int slot, net.minecraft.world.item.Item result) {
        if (player.getAbilities().instabuild) return;
        ItemStack stack = player.getInventory().getItem(slot);
        stack.shrink(1);
        ItemStack replacement = new ItemStack(result);
        if (stack.isEmpty()) {
            player.getInventory().setItem(slot, replacement);
        } else if (!player.getInventory().add(replacement)) {
            player.drop(replacement, false);
        }
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * Server-side fallback for ordinary throwaway blocks. It is only used
     * after vanilla fabricated-face placement failed and still enforces
     * replaceability, survival, collision, reach and inventory consumption.
     */
    private boolean placeFullBlockDirect(
            BlockPos target, BlockItem blockItem, ItemStack stack) {
        BlockState current = player.level().getBlockState(target);
        if (!current.canBeReplaced()) return true;
        BlockState placed = blockItem.getBlock().defaultBlockState();
        if (placed.getCollisionShape(player.level(), target).isEmpty()
                || !placed.canSurvive(player.level(), target)
                || !player.level().isUnobstructed(
                        null, placed.getCollisionShape(
                                player.level(), target).move(target))) {
            return false;
        }
        if (!player.level().setBlockAndUpdate(target, placed)) {
            return false;
        }
        baritone.getCleanProcess().recordPlacedSupport(target);
        lookAt(target);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.inventoryMenu.broadcastChanges();
        if (player.level() instanceof ServerLevel level
                && Baritone.settings().repackOnAnyBlockChange.value) {
            ServerWorldCache.get(level).invalidateChunk(
                    target.getX() >> 4, target.getZ() >> 4);
        }
        return true;
    }

    /**
     * Finds a legal fabricated click whose vanilla placement preview matches
     * the requested schematic state, then performs that exact interaction.
     * This ports BuilderProcess#possibleToPlace without requiring a client
     * ray trace or crosshair alignment.
     */
    public boolean placeSelectedBlockMatching(
            BlockPos target, BlockState desired) {
        return placeSelectedBlockMatching(
                target, desired, desired::equals);
    }

    public boolean placeSelectedBlockMatching(
            BlockPos target, BlockState desired,
            Predicate<BlockState> acceptableState) {
        return matchingPlacement(
                target, desired, acceptableState, true);
    }

    public boolean canPlaceSelectedBlockMatching(
            BlockPos target, BlockState desired,
            Predicate<BlockState> acceptableState) {
        return matchingPlacement(
                target, desired, acceptableState, false);
    }

    private boolean matchingPlacement(
            BlockPos target, BlockState desired,
            Predicate<BlockState> acceptableState,
            boolean execute) {
        if (!canReach(target)) return false;
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() != desired.getBlock().asItem()
                && (!(stack.getItem() instanceof BlockItem blockItem)
                    || blockItem.getBlock() != desired.getBlock())) {
            return false;
        }
        BlockState current = player.level().getBlockState(target);
        if (!current.canBeReplaced()) return false;
        if (!(stack.getItem() instanceof BlockItem)) {
            return printerExactPlacement(
                    target, desired, acceptableState, stack, execute);
        }
        float originalYaw = player.getYRot();
        float originalPitch = player.getXRot();
        try {
            for (PrinterPlacementAction action
                    : printerGuide.actions(target)) {
                BlockPos support = action.support();
                Direction clickedFace = action.face();
                Vec3 hitPoint = action.hit();
                    Rotation look = RotationUtils.calcRotationFromVec3d(
                            player.getEyePosition(), hitPoint,
                            baritone.getPlayerContext().playerRotations());
                    for (int quarterTurn = 0;
                         quarterTurn < 4; quarterTurn++) {
                        player.setYRot(look.getYaw() + quarterTurn * 90.0F);
                        player.setXRot(look.getPitch());
                        BlockHitResult hit = new BlockHitResult(
                                hitPoint, clickedFace, support, false);
                        BlockPlaceContext context = new BlockPlaceContext(
                                player, InteractionHand.MAIN_HAND,
                                stack, hit);
                        BlockState preview = ((BlockItem) stack.getItem())
                                .getBlock()
                                .getStateForPlacement(context);
                        if (preview == null
                                || !acceptableState.test(preview)
                                || !context.canPlace()) {
                            continue;
                        }
                        if (!execute) return true;
                        baritone.getLookBehavior().updateTarget(
                                new Rotation(player.getYRot(),
                                        player.getXRot()), true);
                        return printerQueue.send(stack, context);
                    }
            }
            return printerExactPlacement(
                    target, desired, acceptableState,
                    stack, execute);
        } finally {
            player.setYRot(originalYaw);
            player.setXRot(originalPitch);
        }
    }

    /**
     * Direct server port of Printer's PlacementGuide.Action model. It emits
     * every legal support face and the same center/offset hit variants needed
     * by slabs, stairs, trapdoors, logs, observers and directional blocks.
     */
    private final class PrinterPlacementGuide {
        private List<PrinterPlacementAction> actions(BlockPos target) {
            List<PrinterPlacementAction> result = new ArrayList<>();
            for (Direction fromTarget : Direction.values()) {
                BlockPos support = target.relative(fromTarget);
                BlockState supportState =
                        player.level().getBlockState(support);
                VoxelShape shape = supportState.getShape(
                        player.level(), support);
                if (supportState.canBeReplaced() || shape.isEmpty()) continue;
                Direction face = fromTarget.getOpposite();
                for (Vec3 hit : faceSamples(
                        support, shape.bounds(), face)) {
                    result.add(new PrinterPlacementAction(
                            support, face, hit, true));
                }
            }
            return result;
        }
    }

    /**
     * Direct server port of Printer.Queue. Fake players do not send a client
     * packet, so queue submission invokes the equivalent ItemStack use on the
     * server and publishes inventory changes immediately.
     */
    private final class PrinterPlacementQueue {
        private final Map<BlockPos, Long> lastActionAt = new HashMap<>();
        private long lastGlobalAction = Long.MIN_VALUE;

        private boolean send(ItemStack stack, BlockPlaceContext context) {
            BlockPos target = context.getClickedPos()
                    .relative(context.getClickedFace());
            if (!ready(target)) {
                // Queue accepted the action but has not committed it yet.
                // Builder keeps the same target and retries next tick.
                return true;
            }
            InteractionResult result = stack.useOn(context);
            player.inventoryMenu.broadcastChanges();
            if (result.consumesAction()) {
                record(target);
            }
            return result.consumesAction();
        }

        private boolean ready(BlockPos target) {
            long tick = player.level().getGameTime();
            int interval = Math.max(0,
                    Baritone.settings().printerActionIntervalTicks.value);
            int positionCooldown = Math.max(0, Baritone.settings()
                    .printerSamePositionCooldownTicks.value);
            return (lastGlobalAction == Long.MIN_VALUE
                    || tick - lastGlobalAction >= interval)
                    && tick - lastActionAt.getOrDefault(
                            target, Long.MIN_VALUE / 2L)
                    >= positionCooldown;
        }

        private void record(BlockPos target) {
            long tick = player.level().getGameTime();
            lastGlobalAction = tick;
            lastActionAt.put(target.immutable(), tick);
            if (lastActionAt.size() > 2048) {
                lastActionAt.entrySet().removeIf(entry ->
                        tick - entry.getValue() > 200L);
            }
        }
    }

    private record PrinterPlacementAction(
            BlockPos support, Direction face, Vec3 hit, boolean crouch) { }

    /**
     * Server-side counterpart of Printer's Easy Place protocol. The original
     * mod encodes the desired orientation in a client hit vector and relies on
     * server protocol support. A fake player is already server-authoritative,
     * so after validating reach, support, item, collision and survival we can
     * apply the exact requested state without manufacturing a client packet.
     */
    private boolean printerExactPlacement(
            BlockPos target, BlockState desired,
            Predicate<BlockState> acceptableState,
            ItemStack stack, boolean execute) {
        if (stack.getItem() != desired.getBlock().asItem()
                && (!(stack.getItem() instanceof BlockItem blockItem)
                    || blockItem.getBlock() != desired.getBlock())
                || !acceptableState.test(desired)
                || !desired.canSurvive(player.level(), target)
                || !player.level().isUnobstructed(
                        null, desired.getCollisionShape(
                                player.level(), target).move(target))) {
            return false;
        }
        boolean supported = false;
        for (Direction direction : Direction.values()) {
            BlockPos support = target.relative(direction);
            BlockState state = player.level().getBlockState(support);
            if (!state.canBeReplaced()
                    && !state.getCollisionShape(
                    player.level(), support).isEmpty()) {
                supported = true;
                break;
            }
        }
        // "Print in air" is an explicit Printer mode. Gravity blocks remain
        // support-bound because otherwise the accepted placement immediately
        // becomes an entity and the schematic cell stays incorrect.
        if (!supported
                && (!Baritone.settings().printerPrintInAir.value
                || desired.getBlock() instanceof FallingBlock)) {
            return false;
        }
        boolean needsWater = Baritone.settings().printerPlaceWaterlogged.value
                && desired.hasProperty(BlockStateProperties.WATERLOGGED)
                && desired.getValue(BlockStateProperties.WATERLOGGED)
                && player.level().getFluidState(target).isEmpty();
        int waterSlot = needsWater
                ? findInventoryItem(new ItemStack(Items.WATER_BUCKET)) : -1;
        if (needsWater && waterSlot < 0
                && !player.getAbilities().instabuild) {
            return false;
        }
        boolean needsEnderEye = desired.getBlock()
                instanceof EndPortalFrameBlock
                && desired.getValue(EndPortalFrameBlock.HAS_EYE);
        int enderEyeSlot = needsEnderEye
                ? findInventoryItem(new ItemStack(Items.ENDER_EYE)) : -1;
        if (needsEnderEye && enderEyeSlot < 0
                && !player.getAbilities().instabuild) {
            return false;
        }
        int requiredItems = printerPlacementItemCount(desired);
        if (!player.getAbilities().instabuild
                && stack.getCount() < requiredItems) {
            return false;
        }
        PrinterCompanion companion = printerCompanion(target, desired);
        if (companion != null) {
            BlockState companionCurrent = player.level()
                    .getBlockState(companion.pos());
            if (!companionCurrent.canBeReplaced()
                    || !player.level().isUnobstructed(
                            null, companion.state().getCollisionShape(
                                    player.level(), companion.pos())
                                    .move(companion.pos()))) {
                return false;
            }
        }
        if (!execute) return true;
        if (!printerQueue.ready(target)) return true;
        BlockState previous = player.level().getBlockState(target);
        boolean staged = companion != null;
        if (!(staged
                ? player.level().setBlock(target, desired, 2)
                : player.level().setBlockAndUpdate(target, desired))) {
            return false;
        }
        if (companion != null
                && (!companion.state().canSurvive(
                        player.level(), companion.pos())
                    || !player.level().setBlockAndUpdate(
                            companion.pos(), companion.state()))) {
            player.level().setBlockAndUpdate(target, previous);
            return false;
        }
        lookAt(target);
        if (!player.getAbilities().instabuild) {
            stack.shrink(requiredItems);
        }
        if (needsWater) consumeBucket(waterSlot, Items.BUCKET);
        if (needsEnderEye && !player.getAbilities().instabuild) {
            ItemStack eye = player.getInventory().getItem(enderEyeSlot);
            eye.shrink(1);
        }
        printerQueue.record(target);
        player.inventoryMenu.broadcastChanges();
        if (player.level() instanceof ServerLevel level
                && Baritone.settings().repackOnAnyBlockChange.value) {
            ServerWorldCache.get(level).invalidateChunk(
                    target.getX() >> 4, target.getZ() >> 4);
            if (companion != null) {
                ServerWorldCache.get(level).invalidateChunk(
                        companion.pos().getX() >> 4,
                        companion.pos().getZ() >> 4);
            }
        }
        return true;
    }

    private static PrinterCompanion printerCompanion(
            BlockPos target, BlockState desired) {
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(
                        BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == DoubleBlockHalf.LOWER) {
            return new PrinterCompanion(target.above(),
                    desired.setValue(
                            BlockStateProperties.DOUBLE_BLOCK_HALF,
                            DoubleBlockHalf.UPPER));
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.hasProperty(
                        BlockStateProperties.HORIZONTAL_FACING)
                && desired.getValue(BlockStateProperties.BED_PART)
                        == BedPart.FOOT) {
            Direction facing = desired.getValue(
                    BlockStateProperties.HORIZONTAL_FACING);
            return new PrinterCompanion(target.relative(facing),
                    desired.setValue(BlockStateProperties.BED_PART,
                            BedPart.HEAD));
        }
        return null;
    }

    private record PrinterCompanion(
            BlockPos pos, BlockState state) { }

    /** Whether a queued special interaction may commit on this tick. */
    public boolean printerActionReady(BlockPos target) {
        return printerQueue.ready(target);
    }

    /** Records a successful special interaction in the shared queue. */
    public void recordPrinterAction(BlockPos target) {
        printerQueue.record(target);
    }

    /**
     * Exact placement bypasses the repeated vanilla clicks used for stacked
     * states, so it must preserve Printer's material accounting explicitly.
     */
    static int printerPlacementItemCount(BlockState desired) {
        int count = 1;
        for (String name : List.of(
                "candles", "pickles", "eggs", "layers",
                "flower_amount", "segment_amount")) {
            for (net.minecraft.world.level.block.state.properties.Property<?>
                    property : desired.getProperties()) {
                if (!property.getName().equals(name)) continue;
                try {
                    count = Math.max(count, Integer.parseInt(
                            String.valueOf(desired.getValue(property))));
                } catch (NumberFormatException ignored) {
                    // Non-integer properties are not repeated item counts.
                }
            }
        }
        // A double slab is produced by two uses of the slab item.
        for (net.minecraft.world.level.block.state.properties.Property<?>
                property : desired.getProperties()) {
            if (property.getName().equals("type")
                    && String.valueOf(desired.getValue(property))
                            .equals("double")) {
                count = Math.max(count, 2);
            }
        }
        return count;
    }

    private static List<Vec3> faceSamples(
            BlockPos support, AABB bounds, Direction face) {
        double minX = support.getX() + bounds.minX;
        double minY = support.getY() + bounds.minY;
        double minZ = support.getZ() + bounds.minZ;
        double maxX = support.getX() + bounds.maxX;
        double maxY = support.getY() + bounds.maxY;
        double maxZ = support.getZ() + bounds.maxZ;
        double centerX = (minX + maxX) * 0.5D;
        double centerY = (minY + maxY) * 0.5D;
        double centerZ = (minZ + maxZ) * 0.5D;
        double lowX = minX * 0.9D + maxX * 0.1D;
        double highX = minX * 0.1D + maxX * 0.9D;
        double lowY = minY * 0.75D + maxY * 0.25D;
        double highY = minY * 0.25D + maxY * 0.75D;
        double lowZ = minZ * 0.9D + maxZ * 0.1D;
        double highZ = minZ * 0.1D + maxZ * 0.9D;
        List<Vec3> result = new ArrayList<>(5);
        switch (face) {
            case UP, DOWN -> {
                double y = face == Direction.UP ? maxY : minY;
                result.add(new Vec3(centerX, y, centerZ));
                result.add(new Vec3(lowX, y, centerZ));
                result.add(new Vec3(highX, y, centerZ));
                result.add(new Vec3(centerX, y, lowZ));
                result.add(new Vec3(centerX, y, highZ));
            }
            case EAST, WEST -> {
                double x = face == Direction.EAST ? maxX : minX;
                result.add(new Vec3(x, centerY, centerZ));
                result.add(new Vec3(x, lowY, centerZ));
                result.add(new Vec3(x, highY, centerZ));
            }
            case SOUTH, NORTH -> {
                double z = face == Direction.SOUTH ? maxZ : minZ;
                result.add(new Vec3(centerX, centerY, z));
                result.add(new Vec3(centerX, lowY, z));
                result.add(new Vec3(centerX, highY, z));
            }
        }
        return result;
    }

    /**
     * Replaces a water or lava cell with the selected full block. Clean uses
     * this server-authoritative operation because the middle of a fluid pool
     * may have no dry support face for vanilla UseOnContext placement.
     */
    public boolean fillFluidWithSelectedBlock(BlockPos target) {
        if (!canReach(target)) return false;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        BlockState current = player.level().getBlockState(target);
        if (current.getFluidState().isEmpty()
                || !current.canBeReplaced()) return false;
        BlockState placed = blockItem.getBlock().defaultBlockState();
        if (placed.getCollisionShape(player.level(), target).isEmpty()
                || !player.level().setBlockAndUpdate(target, placed)) {
            return false;
        }
        baritone.getCleanProcess().recordPlacedSupport(target);
        lookAt(target);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    /**
     * Builder variant of fluid replacement. It preserves the schematic's
     * exact block state (facing, slab half, waterlogging and similar
     * properties) while retaining the same reach, survival, collision and
     * inventory-consumption rules as an ordinary fake placement.
     */
    public boolean fillFluidWithSelectedBlockMatching(
            BlockPos target, BlockState desired,
            Predicate<BlockState> acceptableState) {
        if (!canReach(target) || desired == null
                || !acceptableState.test(desired)) return false;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || blockItem.getBlock() != desired.getBlock()) {
            return false;
        }
        BlockState current = player.level().getBlockState(target);
        if (current.getFluidState().isEmpty()
                || !current.canBeReplaced()
                || !desired.canSurvive(player.level(), target)
                || !player.level().isUnobstructed(
                        null, desired.getCollisionShape(
                                player.level(), target).move(target))) {
            return false;
        }
        if (!player.level().setBlockAndUpdate(target, desired)) {
            return false;
        }
        baritone.getCleanProcess().recordPlacedSupport(target);
        lookAt(target);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.inventoryMenu.broadcastChanges();
        if (player.level() instanceof ServerLevel level
                && Baritone.settings().repackOnAnyBlockChange.value) {
            ServerWorldCache.get(level).invalidateChunk(
                    target.getX() >> 4, target.getZ() >> 4);
        }
        return true;
    }

    /** Uses the selected item against a fabricated legal support face. */
    public boolean useSelectedAt(BlockPos target) {
        if (!canReach(target)) return false;
        ItemStack stack = player.getMainHandItem();
        for (Direction fromTarget : Direction.values()) {
            BlockPos support = target.relative(fromTarget);
            BlockState supportState = player.level().getBlockState(support);
            if (supportState.isAir()
                    || !supportState.getFluidState().isEmpty()) continue;
            Direction clickedFace = fromTarget.getOpposite();
            Vec3 faceCenter = support.getCenter().add(
                    clickedFace.getUnitVec3().scale(0.5D));
            BlockHitResult hit = new BlockHitResult(
                    faceCenter, clickedFace, support, false);
            lookAt(target);
            InteractionResult result = stack.useOn(new UseOnContext(
                    player, InteractionHand.MAIN_HAND, hit));
            if (result.consumesAction()) {
                player.inventoryMenu.broadcastChanges();
                return true;
            }
        }
        return false;
    }

    public boolean pickup(ItemEntity entity) {
        if (entity == null || !entity.isAlive()
                || player.getEyePosition().distanceToSqr(
                        entity.position())
                        > RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE
                        * RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE) {
            return false;
        }
        lookAt(entity.blockPosition());
        entity.setPickUpDelay(0);
        entity.playerTouch(player);
        return !entity.isAlive() || entity.getItem().isEmpty();
    }

    public boolean useSelectedInAir() {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return false;
        InteractionResult result = player.gameMode.useItem(
                player, (ServerLevel) player.level(), stack,
                InteractionHand.MAIN_HAND);
        player.inventoryMenu.broadcastChanges();
        return result.consumesAction();
    }

    public boolean interactBlock(BlockPos pos) {
        if (!canReach(pos)) return false;
        lookAt(pos);
        BlockHitResult hit = new BlockHitResult(
                pos.getCenter(), Direction.UP, pos, false);
        InteractionResult result = player.gameMode.useItemOn(
                player, (ServerLevel) player.level(),
                player.getMainHandItem(), InteractionHand.MAIN_HAND,
                hit);
        player.inventoryMenu.broadcastChanges();
        return result.consumesAction();
    }

    /**
     * Uses the selected item on the target block itself. This is the
     * server-side equivalent of upstream's reachable ray-trace interaction
     * and is used for bone meal and similar item-on-block actions.
     */
    public boolean useSelectedOnBlock(BlockPos pos) {
        if (!canReach(pos)) return false;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return false;
        lookAt(pos);
        BlockHitResult hit = new BlockHitResult(
                pos.getCenter(), Direction.UP, pos, false);
        InteractionResult result = stack.useOn(new UseOnContext(
                player, InteractionHand.MAIN_HAND, hit));
        player.inventoryMenu.broadcastChanges();
        return result.consumesAction();
    }

    /**
     * Printer portal action: ignite an obsidian face adjacent to the desired
     * portal cell. Successful vanilla ignition creates the whole portal, so
     * the remaining schematic cells become correct without extra actions.
     */
    public boolean ignitePortalAt(BlockPos target) {
        if (!canReach(target)) return false;
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Items.FLINT_AND_STEEL)
                && !stack.is(Items.FIRE_CHARGE)) {
            return false;
        }
        for (Direction fromTarget : Direction.values()) {
            BlockPos support = target.relative(fromTarget);
            if (!player.level().getBlockState(support)
                    .is(Blocks.OBSIDIAN)) continue;
            Direction face = fromTarget.getOpposite();
            BlockHitResult hit = new BlockHitResult(
                    support.getCenter().add(
                            face.getUnitVec3().scale(0.5D)),
                    face, support, false);
            lookAt(target);
            InteractionResult result = stack.useOn(new UseOnContext(
                    player, InteractionHand.MAIN_HAND, hit));
            player.inventoryMenu.broadcastChanges();
            if (result.consumesAction()) return true;
        }
        return false;
    }

    public boolean placeBucketFluid(BlockPos target) {
        if (!canReach(target)) return false;
        ItemStack selected = player.getMainHandItem();
        BlockState state = player.level().getBlockState(target);
        if (!state.canBeReplaced()) return false;
        BlockState fluid;
        if (selected.is(Items.WATER_BUCKET)) {
            fluid = Blocks.WATER.defaultBlockState();
        } else if (selected.is(Items.LAVA_BUCKET)) {
            fluid = Blocks.LAVA.defaultBlockState();
        } else {
            return false;
        }
        lookAt(target);
        if (!player.level().setBlockAndUpdate(target, fluid)) return false;
        if (!player.getAbilities().instabuild) {
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.BUCKET));
        }
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    public boolean pickupBucketFluid(BlockPos target) {
        if (!canReach(target)
                || !player.getMainHandItem().is(Items.BUCKET)) return false;
        var fluid = player.level().getFluidState(target);
        ItemStack filled;
        if (fluid.isSourceOfType(Fluids.WATER)) {
            filled = new ItemStack(Items.WATER_BUCKET);
        } else if (fluid.isSourceOfType(Fluids.LAVA)) {
            filled = new ItemStack(Items.LAVA_BUCKET);
        } else {
            return false;
        }
        lookAt(target);
        if (!player.level().setBlockAndUpdate(
                target, Blocks.AIR.defaultBlockState())) return false;
        if (!player.getAbilities().instabuild) {
            player.setItemInHand(InteractionHand.MAIN_HAND, filled);
        }
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    public boolean canReach(Container ignored, BlockPos pos) {
        return canReach(pos);
    }
}
