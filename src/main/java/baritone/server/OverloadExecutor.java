package baritone.server;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Replays a normally calculated Baritone path in one transaction, then
 * teleports to its end. Mining and support placement are real server world
 * operations; only movement time is removed.
 */
public final class OverloadExecutor {
    public enum Result { WAITING, SUCCESS, FAILED }

    private final Baritone baritone;
    private IPath pendingPath;
    private long readyTick;

    public OverloadExecutor(Baritone baritone) {
        this.baritone = Objects.requireNonNull(baritone);
    }

    public Result tick(IPath path) {
        if (!OverloadModeManager.INSTANCE.isEnabled(baritone)) {
            reset();
            return Result.FAILED;
        }
        ServerPlayer player = baritone.getPlayerContext().player();
        long tick = player.level().getGameTime();
        if (pendingPath == null) {
            pendingPath = Objects.requireNonNull(path);
            readyTick = tick + 1L;
            System.out.println("[CBI-OVERLOAD] path-planned player="
                    + player.getScoreboardName() + " nodes=" + path.length()
                    + " destination=" + path.getDest());
            return Result.WAITING;
        }
        if (pendingPath != path) {
            // A process may revalidate and replace an accepted path during the
            // mandatory one-tick presentation delay. Do not restart that delay
            // for every replacement: a stream of equivalent accepted paths
            // would otherwise keep an overload actor frozen forever.
            pendingPath = Objects.requireNonNull(path);
            System.out.println("[CBI-OVERLOAD] path-replaced player="
                    + player.getScoreboardName() + " nodes=" + path.length()
                    + " destination=" + path.getDest()
                    + " readyTick=" + readyTick);
        }
        if (tick < readyTick) return Result.WAITING;
        Result result = replay(pendingPath);
        pendingPath = null;
        readyTick = 0L;
        return result;
    }

    private Result replay(IPath path) {
        ServerPlayer player = baritone.getPlayerContext().player();
        ServerLevel world = baritone.getPlayerContext().world();
        Set<BlockPos> broken = new HashSet<>();
        Set<BlockPos> placed = new HashSet<>();
        int movementCount = 0;
        BetterBlockPos lastCompleted = path.getSrc();
        for (IMovement raw : path.movements()) {
            if (!(raw instanceof Movement movement)) {
                return partialOrFailed(player, path, "movement", null,
                        lastCompleted, movementCount, broken, placed);
            }
            BetterBlockPos movementDestination = movement.getDest();
            world.getChunk(movementDestination.x >> 4,
                    movementDestination.z >> 4);
            movement.resetBlockCache();
            BlockStateInterface before = new BlockStateInterface(
                    baritone.getPlayerContext());
            for (BlockPos block : movement.toBreak(before)) {
                BlockPos immutable = block.immutable();
                if (!baritone.getFakeInteractionController()
                        .breakPlannedBlock(immutable)) {
                    return partialOrFailed(player, path, "break", immutable,
                            lastCompleted, movementCount, broken, placed);
                }
                broken.add(immutable);
            }
            movement.resetBlockCache();
            BlockStateInterface afterBreak = new BlockStateInterface(
                    baritone.getPlayerContext());
            for (BlockPos block : movement.toWalkInto(afterBreak)) {
                BlockPos immutable = block.immutable();
                baritone.getFakeInteractionController()
                        .openPlannedPassage(immutable);
                BlockStateInterface afterOpen = new BlockStateInterface(
                        baritone.getPlayerContext());
                if (!MovementHelper.canWalkThrough(afterOpen,
                        immutable.getX(), immutable.getY(),
                        immutable.getZ())
                        && !baritone.getFakeInteractionController()
                                .breakPlannedBlock(immutable)) {
                    return partialOrFailed(player, path, "clear", immutable,
                            lastCompleted, movementCount, broken, placed);
                }
                if (MovementHelper.canWalkThrough(
                        new BlockStateInterface(baritone.getPlayerContext()),
                        immutable.getX(), immutable.getY(), immutable.getZ())) {
                    broken.add(immutable);
                }
            }
            movement.resetBlockCache();
            afterBreak = new BlockStateInterface(
                    baritone.getPlayerContext());
            for (BlockPos block : movement.toPlace(afterBreak)) {
                BlockPos immutable = block.immutable();
                if (!baritone.getFakeInteractionController()
                        .placePlannedSupport(immutable)) {
                    return partialOrFailed(player, path, "place", immutable,
                            lastCompleted, movementCount, broken, placed);
                }
                placed.add(immutable);
                baritone.getBuilderProcess()
                        .recordPathingSupport(immutable);
            }
            movementCount++;
            lastCompleted = movementDestination;
        }
        BetterBlockPos destination = path.getDest();
        teleportTo(world, player, destination);
        System.out.println("[CBI-OVERLOAD] path-replayed player="
                + player.getScoreboardName() + " movements=" + movementCount
                + " broken=" + broken.size() + " placed=" + placed.size()
                + " destination=" + destination);
        return Result.SUCCESS;
    }

    /**
     * Keep all successfully replayed movement instead of snapping back to the
     * path source when a later support runs out or the world changes. The next
     * process tick recalculates from this committed node. If the first movement
     * cannot be committed, report a genuine failure and use normal backoff.
     */
    private Result partialOrFailed(
            ServerPlayer player, IPath path, String action, BlockPos pos,
            BetterBlockPos lastCompleted, int movementCount,
            Set<BlockPos> broken, Set<BlockPos> placed) {
        if (movementCount <= 0 && broken.isEmpty() && placed.isEmpty()) {
            failed(player, path, action, pos);
            return Result.FAILED;
        }
        if (movementCount > 0) {
            teleportTo(baritone.getPlayerContext().world(), player,
                    lastCompleted);
        } else {
            player.setDeltaMovement(0.0D, 0.0D, 0.0D);
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        System.out.println("[CBI-OVERLOAD] path-replay-partial player="
                + player.getScoreboardName() + " action=" + action
                + " pos=" + pos + " movements=" + movementCount
                + " broken=" + broken.size() + " placed=" + placed.size()
                + " committed=" + lastCompleted
                + " destination=" + path.getDest());
        return Result.SUCCESS;
    }

    private void teleportTo(
            ServerLevel world, ServerPlayer player,
            BetterBlockPos destination) {
        world.getChunk(destination.x >> 4, destination.z >> 4);
        player.teleportTo(destination.x + 0.5D, destination.y,
                destination.z + 0.5D);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    private static void failed(
            ServerPlayer player, IPath path, String action, BlockPos pos) {
        System.out.println("[CBI-OVERLOAD] path-replay-failed player="
                + player.getScoreboardName() + " action=" + action
                + " pos=" + pos + " destination=" + path.getDest());
    }

    public void reset() {
        pendingPath = null;
        readyTick = 0L;
    }
}
