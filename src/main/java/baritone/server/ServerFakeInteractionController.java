package baritone.server;

import baritone.Baritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.MovementHelper;
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
        if (!canReach(pos)) return false;
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;
        baritone.getInventoryController().ensureBestToolOnHotbar(state);
        MovementHelper.switchToBestToolFor(
                baritone.getPlayerContext(), state);
        lookAt(pos);
        return player.gameMode.destroyBlock(pos);
    }

    /**
     * Places the selected BlockItem at {@code target} through BlockItem's
     * normal placement rules using a fabricated valid support-face hit.
     */
    public boolean placeSelectedBlock(BlockPos target) {
        if (!canReach(target)) return false;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem)) return false;
        BlockState current = player.level().getBlockState(target);
        if (!current.canBeReplaced()) return false;
        return useSelectedAt(target);
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
        lookAt(target);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.inventoryMenu.broadcastChanges();
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
