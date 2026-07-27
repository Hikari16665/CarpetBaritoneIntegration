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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
    public enum State {
        START_FLYING,
        INITIAL_CLIMB,
        GLIDE_DOWN,
        CLIMB_BACK,
        LANDING
    }

    private final Baritone baritone;
    private BlockPos destination;
    private List<BetterBlockPos> path = Collections.emptyList();
    private State state = State.START_FLYING;
    private int ticks;
    private int lastBoostTick = Integer.MIN_VALUE / 2;
    private double launchX;
    private double launchZ;
    private BlockPos landingTarget;

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
        boolean hasRocket = baritone.getInventoryController()
                .hasAccessibleItem(
                        stack -> stack.is(Items.FIREWORK_ROCKET));
        if (!hasRocket) {
            throw new IllegalArgumentException(
                    "假人物品栏没有烟花火箭，无法爬升到巡航高度");
        }
        this.destination = destination.immutable();
        this.state = State.START_FLYING;
        this.ticks = 0;
        this.lastBoostTick = Integer.MIN_VALUE / 2;
        this.launchX = baritone.getPlayerContext().player().getX();
        this.launchZ = baritone.getPlayerContext().player().getZ();
        this.landingTarget = null;
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
            boolean swapped = Baritone.settings().elytraAutoSwap.value
                    && baritone.getInventoryController().equipBestElytra(
                            Baritone.settings().elytraMinimumDurability.value);
            if (!swapped) {
                if (Baritone.settings().elytraAllowEmergencyLand.value) {
                    enterLanding();
                } else {
                    onLostControl();
                    return;
                }
            }
        }
        Vec3 position = baritone.getPlayerContext().player().position();
        BlockPos steeringTarget = flightTarget();
        double horizontal = Math.hypot(
                steeringTarget.getX() + 0.5D - position.x,
                steeringTarget.getZ() + 0.5D - position.z);
        if (baritone.getPlayerContext().player().onGround()) {
            if (state == State.LANDING || horizontal <= 4.0D) {
                onLostControl();
                return;
            }
            state = State.START_FLYING;
            // A server fake player has no client to generate the two takeoff
            // jump packets. Always perform the first jump ourselves.
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            baritone.getInputController().tick();
            return;
        }
        if (!baritone.getPlayerContext().player().isFallFlying()) {
            baritone.getInputOverrideHandler().clearAllKeys();
            // This is the server-side equivalent of the second jump packet.
            baritone.getPlayerContext().player().startFallFlying();
            baritone.getInputController().tick();
            return;
        }

        int high = Math.max(401, Baritone.settings().elytraCruiseAltitude.value);
        int low = Math.min(high - 1,
                Baritone.settings().elytraGlideLowAltitude.value);
        double landingRange = Math.max(20,
                Baritone.settings().elytraLandingApproachDistance.value);
        if (state == State.START_FLYING) state = State.INITIAL_CLIMB;
        if (state != State.INITIAL_CLIMB && horizontal <= landingRange) {
            enterLanding();
        } else if (state == State.INITIAL_CLIMB && position.y >= high - 5) {
            if (horizontal <= landingRange) enterLanding();
            else state = State.GLIDE_DOWN;
        } else if (state == State.GLIDE_DOWN && position.y <= low + 5) {
            state = State.CLIMB_BACK;
        } else if (state == State.CLIMB_BACK && position.y >= high - 5) {
            if (horizontal <= landingRange) enterLanding();
            else state = State.GLIDE_DOWN;
        }

        Rotation rotation = rotationForState(position);
        baritone.getLookBehavior().updateTarget(rotation, true);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (usesBoost(state) && shouldBoost()
                && baritone.getInventoryController().selectItem(
                stack -> stack.is(Items.FIREWORK_ROCKET))) {
            if (baritone.getFakeInteractionController()
                    .useSelectedInAir()) {
                lastBoostTick = ticks;
            }
        }
        baritone.getInputController().tick();
    }

    private Rotation rotationForState(Vec3 position) {
        BlockPos steeringTarget = flightTarget();
        Rotation base = RotationUtils.calcRotationFromVec3d(
                baritone.getPlayerContext().playerHead(),
                new Vec3(steeringTarget.getX() + 0.5D, position.y,
                        steeringTarget.getZ() + 0.5D),
                baritone.getPlayerContext().playerRotations());
        return switch (state) {
            case INITIAL_CLIMB -> {
                // Rotate around the launch column so the rocket-assisted
                // ascent stays local instead of spending hundreds of blocks
                // travelling horizontally before cruise altitude.
                double fromLaunch = Math.hypot(
                        position.x - launchX, position.z - launchZ);
                float yaw = fromLaunch > 24.0D
                        ? RotationUtils.calcRotationFromVec3d(
                        baritone.getPlayerContext().playerHead(),
                        new Vec3(launchX, position.y + 64.0D, launchZ),
                        baritone.getPlayerContext().playerRotations()).getYaw()
                        : baritone.getPlayerContext().playerRotations().getYaw()
                        + 10.0F;
                yield new Rotation(yaw, -68.0F);
            }
            case CLIMB_BACK -> new Rotation(base.getYaw(), -48.0F);
            case GLIDE_DOWN -> new Rotation(base.getYaw(), 8.0F);
            case LANDING -> {
                double height = position.y - destination.getY();
                float pitch;
                if (height > 80.0D) pitch = 10.0F;
                else if (height > 25.0D) pitch = 4.0F;
                else pitch = -8.0F; // flare and bleed speed before touchdown
                yield new Rotation(base.getYaw(), pitch);
            }
            case START_FLYING -> new Rotation(base.getYaw(), -68.0F);
        };
    }

    private boolean shouldBoost() {
        return ticks - lastBoostTick >= Math.max(5,
                Baritone.settings().elytraBoostIntervalTicks.value);
    }

    private static boolean usesBoost(State state) {
        // Both ascent legs need propulsion. GLIDE_DOWN deliberately saves
        // rockets, while CLIMB_BACK must boost or pitching upward at the low
        // altitude merely stalls the fake player.
        return state == State.INITIAL_CLIMB
                || state == State.CLIMB_BACK;
    }

    private void enterLanding() {
        if (state != State.LANDING || landingTarget == null) {
            landingTarget = findSafeLandingSpot();
        }
        state = State.LANDING;
    }

    private BlockPos flightTarget() {
        return state == State.LANDING && landingTarget != null
                ? landingTarget : destination;
    }

    /**
     * Server adaptation of upstream's landing search. It only inspects
     * already loaded chunks and requires a solid, dry support with two clear
     * blocks for the fake player's body.
     */
    private BlockPos findSafeLandingSpot() {
        var world = baritone.getPlayerContext().world();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int radius = 0; radius <= 24; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius != 0
                            && Math.max(Math.abs(dx), Math.abs(dz))
                                    != radius) continue;
                    int x = destination.getX() + dx;
                    int z = destination.getZ() + dz;
                    BlockPos probe = new BlockPos(x,
                            destination.getY(), z);
                    if (!world.hasChunkAt(probe)) continue;
                    int y = world.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!safeLandingColumn(feet)) continue;
                    double distance = feet.distSqr(destination);
                    if (distance < bestDistance) {
                        best = feet;
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) return best;
        }
        return destination;
    }

    private boolean safeLandingColumn(BlockPos feet) {
        var world = baritone.getPlayerContext().world();
        BlockState support = world.getBlockState(feet.below());
        if (support.isAir()
                || !support.getFluidState().isEmpty()
                || support.is(Blocks.MAGMA_BLOCK)
                || support.is(Blocks.CACTUS)
                || support.is(Blocks.FIRE)
                || support.is(Blocks.SOUL_FIRE)) {
            return false;
        }
        return support.isCollisionShapeFullBlock(world, feet.below())
                && world.getBlockState(feet).getCollisionShape(
                        world, feet).isEmpty()
                && world.getBlockState(feet.above()).getCollisionShape(
                        world, feet.above()).isEmpty()
                && world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.above()).isEmpty();
    }

    private void rebuildDirectPath() {
        BetterBlockPos start = baritone.getPlayerContext().playerFeet();
        List<BetterBlockPos> result = new ArrayList<>();
        int high = Baritone.settings().elytraCruiseAltitude.value;
        int low = Baritone.settings().elytraGlideLowAltitude.value;
        result.add(start);
        result.add(new BetterBlockPos(start.getX(), high, start.getZ()));
        double horizontal = Math.hypot(
                destination.getX() - start.getX(),
                destination.getZ() - start.getZ());
        int waves = Math.max(1, (int) Math.ceil(horizontal / 512.0D));
        for (int i = 1; i <= waves; i++) {
            double t = i / (double) (waves + 1);
            result.add(new BetterBlockPos(
                    start.getX() + (destination.getX() - start.getX()) * t,
                    (i & 1) == 1 ? low : high,
                    start.getZ() + (destination.getZ() - start.getZ()) * t));
        }
        result.add(new BetterBlockPos(destination));
        path = List.copyOf(result);
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    @Override public void repackChunks() { rebuildDirectPath(); }
    @Override public BlockPos currentDestination() { return destination; }
    @Override public List<BetterBlockPos> getPath() { return path; }
    @Override public void resetState() {
        state = State.START_FLYING;
        ticks = 0;
        lastBoostTick = Integer.MIN_VALUE / 2;
        landingTarget = null;
    }
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
        destination = null;
        landingTarget = null;
        path = Collections.emptyList();
        state = State.START_FLYING;
    }
    @Override public String displayName0() { return "Elytra to " + destination; }
}
