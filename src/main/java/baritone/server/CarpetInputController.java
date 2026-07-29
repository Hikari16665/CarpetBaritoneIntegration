/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.api.utils.Rotation;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import baritone.pathing.movement.MovementState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import baritone.utils.ToolSet;
import baritone.Baritone;
import baritone.cache.ServerWorldCache;
import net.minecraft.server.level.ServerLevel;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts Baritone's virtual key state into Carpet fake-player actions.
 */
public final class CarpetInputController implements IInputOverrideHandler {

    private final ServerPlayer player;
    private final EntityPlayerActionPack actionPack;
    private final Map<Input, Boolean> requested = new EnumMap<>(Input.class);
    private final Map<Input, Boolean> applied = new EnumMap<>(Input.class);
    private Rotation targetRotation;
    private BlockPos blockBreakTarget;
    private BlockPos activeBreakTarget;
    private double blockBreakProgress;

    public CarpetInputController(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
        if (!(player instanceof ServerPlayerInterface carpetPlayer)) {
            throw new IllegalArgumentException("Player is not enhanced by Carpet: " + player.getScoreboardName());
        }
        this.actionPack = carpetPlayer.getActionPack();
    }

    @Override
    public boolean isInputForcedDown(Input input) {
        return requested.getOrDefault(input, false);
    }

    @Override
    public void setInputForceState(Input input, boolean forced) {
        requested.put(Objects.requireNonNull(input, "input"), forced);
    }

    public void setTargetRotation(Rotation targetRotation) {
        this.targetRotation = targetRotation == null ? null : targetRotation.normalizeAndClamp();
    }

    public void setBlockBreakTarget(BlockPos target) {
        this.blockBreakTarget = target == null ? null : target.immutable();
    }

    public boolean hasActiveBreakTarget() {
        return blockBreakTarget != null || activeBreakTarget != null;
    }

    /**
     * Applies one Baritone movement result and immediately forwards it to
     * Carpet's action pack.
     */
    public void apply(MovementState movementState) {
        for (Input input : Input.values()) {
            setInputForceState(input, movementState.getInputStates().getOrDefault(input, false));
        }
        setTargetRotation(movementState.getTarget().getRotation().orElse(null));
        tick();
    }

    /** Applies the current Baritone input state to Carpet for this server tick. */
    public void tick() {
        float forward = axis(Input.MOVE_FORWARD, Input.MOVE_BACK);
        float strafing = axis(Input.MOVE_LEFT, Input.MOVE_RIGHT);

        actionPack.setForward(forward);
        actionPack.setStrafing(strafing);
        actionPack.setSneaking(isInputForcedDown(Input.SNEAK));
        actionPack.setSprinting(isInputForcedDown(Input.SPRINT));

        if (targetRotation != null) {
            actionPack.look(targetRotation.getYaw(), targetRotation.getPitch());
        }

        applyAction(Input.JUMP, EntityPlayerActionPack.ActionType.JUMP);
        /*
         * All block/item interactions are server-authoritative fake
         * interactions. Never forward attack/use to Carpet's real action
         * pack, even if an upstream movement still raises a legacy key.
         */
        resetServerBlockBreak();
        stopCarpetAttack();
        stopCarpetUse();
        blockBreakTarget = null;
    }

    @Override
    public void clearAllKeys() {
        requested.clear();
    }

    private float axis(Input positive, Input negative) {
        return (isInputForcedDown(positive) ? 1.0F : 0.0F)
                - (isInputForcedDown(negative) ? 1.0F : 0.0F);
    }

    private void applyAction(Input input, EntityPlayerActionPack.ActionType actionType) {
        boolean shouldRun = isInputForcedDown(input);
        boolean wasRunning = applied.getOrDefault(input, false);
        if (shouldRun == wasRunning) {
            return;
        }
        actionPack.start(
                actionType,
                shouldRun ? EntityPlayerActionPack.Action.continuous() : null
        );
        applied.put(input, shouldRun);
    }

    private void stopCarpetAttack() {
        if (applied.getOrDefault(Input.CLICK_LEFT, false)) {
            actionPack.start(EntityPlayerActionPack.ActionType.ATTACK, null);
            applied.put(Input.CLICK_LEFT, false);
        }
    }

    private void stopCarpetUse() {
        if (applied.getOrDefault(Input.CLICK_RIGHT, false)) {
            actionPack.start(EntityPlayerActionPack.ActionType.USE, null);
            applied.put(Input.CLICK_RIGHT, false);
        }
    }

    private void tickServerBlockBreak() {
        if (!blockBreakTarget.equals(activeBreakTarget)) {
            resetServerBlockBreak();
            activeBreakTarget = blockBreakTarget;
        }
        BlockState state = player.level().getBlockState(blockBreakTarget);
        if (state.isAir()) {
            resetServerBlockBreak();
            return;
        }
        if (Baritone.settings().autoTool.value
                && !Baritone.settings().assumeExternalAutoTool.value) {
            if (player.getServer() != null) {
                // Mirrors the part of upstream InventoryBehavior that keeps
                // the best mining tool available on the hotbar.
                new ServerInventoryController(player).ensureBestToolOnHotbar(state);
            }
            ToolSet tools = new ToolSet(player);
            int bestSlot = tools.getBestSlot(
                    state.getBlock(),
                    Baritone.settings().preferSilkTouch.value
            );
            if (player.getInventory().selected != bestSlot) {
                player.getInventory().selected = bestSlot;
            }
        }
        if (player.getAbilities().instabuild) {
            if (player.gameMode.destroyBlock(blockBreakTarget)) {
                invalidateCachedChunk(blockBreakTarget);
            }
            resetServerBlockBreak();
            return;
        }
        double increment = ToolSet.calculateSpeedVsBlock(
                player.getMainHandItem(),
                state
        );
        if (increment <= 0) {
            return;
        }
        blockBreakProgress += increment;
        player.level().destroyBlockProgress(
                player.getId(),
                blockBreakTarget,
                Math.min(9, (int) (blockBreakProgress * 10.0D))
        );
        if (blockBreakProgress >= 1.0D) {
            if (player.gameMode.destroyBlock(blockBreakTarget)) {
                invalidateCachedChunk(blockBreakTarget);
            }
            resetServerBlockBreak();
        }
    }

    private void invalidateCachedChunk(BlockPos position) {
        if (Baritone.settings().repackOnAnyBlockChange.value
                && player.level() instanceof ServerLevel level) {
            ServerWorldCache.get(level).invalidateChunk(
                    position.getX() >> 4, position.getZ() >> 4);
        }
    }

    private void resetServerBlockBreak() {
        if (activeBreakTarget != null) {
            player.level().destroyBlockProgress(player.getId(), activeBreakTarget, -1);
        }
        activeBreakTarget = null;
        blockBreakProgress = 0.0D;
    }
}
