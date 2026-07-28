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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Server-authoritative fake interactions. Looking is retained as visual
 * feedback, but success never depends on the player's crosshair or a queued
 * Carpet mouse action.
 */
public final class ServerFakeInteractionController {
    private final Baritone baritone;
    private final ServerPlayer player;
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
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || blockItem.getBlock() != desired.getBlock()) {
            return false;
        }
        BlockState current = player.level().getBlockState(target);
        if (!current.canBeReplaced()) return false;
        float originalYaw = player.getYRot();
        float originalPitch = player.getXRot();
        try {
            for (Direction fromTarget : Direction.values()) {
                BlockPos support = target.relative(fromTarget);
                BlockState supportState =
                        player.level().getBlockState(support);
                VoxelShape shape = supportState.getShape(
                        player.level(), support);
                if (supportState.canBeReplaced() || shape.isEmpty()) continue;
                Direction clickedFace = fromTarget.getOpposite();
                for (Vec3 hitPoint : faceSamples(
                        support, shape.bounds(), clickedFace)) {
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
                        BlockState preview = blockItem.getBlock()
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
                        InteractionResult result = stack.useOn(context);
                        player.inventoryMenu.broadcastChanges();
                        return result.consumesAction();
                    }
                }
            }
            return false;
        } finally {
            player.setYRot(originalYaw);
            player.setXRot(originalPitch);
        }
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
