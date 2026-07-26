package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-native Elytra process. It uses live entity physics rather than the
 * upstream client/native Nether predictor, while retaining the public process
 * contract, destination state, launch, boost, safety and landing states.
 */
public final class ElytraProcess implements IElytraProcess {
    public enum State { START_FLYING, FLYING, LANDING }

    private final Baritone baritone;
    private BlockPos destination;
    private List<BetterBlockPos> path = Collections.emptyList();
    private State state = State.START_FLYING;
    private int ticks;

    public ElytraProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void pathTo(BlockPos destination) {
        ItemStack chest = baritone.getPlayerContext().player()
                .getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) {
            throw new IllegalArgumentException("假人胸甲栏没有鞘翅");
        }
        this.destination = destination.immutable();
        this.state = State.START_FLYING;
        rebuildDirectPath();
    }

    @Override
    public void pathTo(Goal goal) {
        if (goal instanceof IGoalRenderPos positioned) {
            pathTo(positioned.getGoalPos());
        } else if (goal instanceof GoalXZ xz) {
            pathTo(new BlockPos(xz.getX(),
                    baritone.getPlayerContext().playerFeet().getY(), xz.getZ()));
        } else {
            throw new IllegalArgumentException("该 Goal 没有可解析的鞘翅目的坐标");
        }
    }

    public void serverTick() {
        if (!isActive()) return;
        ticks++;
        ItemStack elytra = baritone.getPlayerContext().player()
                .getItemBySlot(EquipmentSlot.CHEST);
        if (!elytra.is(Items.ELYTRA)
                || remainingDurability(elytra) <= Baritone.settings().elytraMinimumDurability.value) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) state = State.LANDING;
            else onLostControl();
        }
        Vec3 position = baritone.getPlayerContext().player().position();
        double horizontal = Math.hypot(
                destination.getX() + 0.5D - position.x,
                destination.getZ() + 0.5D - position.z);
        if (baritone.getPlayerContext().player().onGround()) {
            if (horizontal <= 4.0D) {
                onLostControl();
                return;
            }
            state = State.START_FLYING;
            if (Baritone.settings().elytraAutoJump.value) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                baritone.getInputController().tick();
            }
            return;
        }
        if (!baritone.getPlayerContext().player().isFallFlying()) {
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            baritone.getInputController().tick();
            return;
        }
        if (horizontal < 20.0D) state = State.LANDING;
        else state = State.FLYING;

        Vec3 aim = new Vec3(
                destination.getX() + 0.5D,
                state == State.LANDING ? destination.getY() : position.y + 2.0D,
                destination.getZ() + 0.5D);
        Rotation base = RotationUtils.calcRotationFromVec3d(
                baritone.getPlayerContext().playerHead(), aim,
                baritone.getPlayerContext().playerRotations());
        float pitch = state == State.LANDING ? 25.0F : Math.max(-15.0F, base.getPitch());
        baritone.getLookBehavior().updateTarget(new Rotation(base.getYaw(), pitch), true);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (state == State.FLYING && ticks % 40 == 0
                && baritone.getInventoryController().selectItem(stack ->
                        stack.is(Items.FIREWORK_ROCKET))) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        }
        baritone.getInputController().tick();
    }

    private void rebuildDirectPath() {
        BetterBlockPos start = baritone.getPlayerContext().playerFeet();
        double distance = start.getCenter().distanceTo(destination.getCenter());
        int segments = Math.max(1, (int) Math.ceil(distance / 16.0D));
        List<BetterBlockPos> result = new ArrayList<>();
        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            result.add(new BetterBlockPos(
                    start.getX() + (destination.getX() - start.getX()) * t,
                    start.getY() + (destination.getY() - start.getY()) * t,
                    start.getZ() + (destination.getZ() - start.getZ()) * t));
        }
        path = List.copyOf(result);
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    @Override public void repackChunks() { rebuildDirectPath(); }
    @Override public BlockPos currentDestination() { return destination; }
    @Override public List<BetterBlockPos> getPath() { return path; }
    @Override public void resetState() { state = State.START_FLYING; ticks = 0; }
    @Override public boolean isLoaded() { return true; }
    @Override public boolean isSafeToCancel() {
        return !baritone.getPlayerContext().player().isFallFlying()
                || baritone.getPlayerContext().player().onGround();
    }
    @Override public boolean isActive() { return destination != null; }
    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return new PathingCommand(
                destination == null ? null : new GoalBlock(destination),
                PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        destination = null; path = Collections.emptyList(); state = State.START_FLYING;
    }
    @Override public String displayName0() { return "Elytra to " + destination; }
}
