package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.MirroredSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.SubstituteSchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.MapArtSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Pure-server schematic builder process. */
public final class BuilderProcess implements IBuilderProcess {
    private final Baritone baritone;
    private String name;
    private ISchematic schematic;
    private BlockPos origin;
    private boolean paused;
    private BlockPos target;
    private BlockState desired;
    private List<BlockState> approxPlaceable = Collections.emptyList();
    private int layer;

    public BuilderProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        ISchematic configured = schematic;
        if (Baritone.settings().buildOnlySelection.value
                && !(configured instanceof SelectionSchematic)) {
            configured = new SelectionSchematic(configured, origin,
                    baritone.getSelectionManager().getSelections());
        }
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            configured = new SubstituteSchematic(
                    configured, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value
                != net.minecraft.world.level.block.Mirror.NONE) {
            configured = new MirroredSchematic(
                    configured, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value
                != net.minecraft.world.level.block.Rotation.NONE) {
            configured = new RotatedSchematic(
                    configured, Baritone.settings().buildSchematicRotation.value);
        }
        ISchematic decorated = configured;
        configured = new MaskSchematic(decorated) {
            @Override protected boolean partOfMask(
                    int x, int y, int z, BlockState current) {
                BlockState wanted = desiredState(
                        x, y, z, current, Collections.emptyList());
                return wanted != null
                        && !Baritone.settings().buildSkipBlocks.value
                                .contains(wanted.getBlock());
            }
        };
        this.schematic = configured;
        int originX = origin.getX()
                + (Baritone.settings().schematicOrientationX.value
                        ? schematic.widthX() : 0);
        int originY = origin.getY()
                + (Baritone.settings().schematicOrientationY.value
                        ? schematic.heightY() : 0);
        int originZ = origin.getZ()
                + (Baritone.settings().schematicOrientationZ.value
                        ? schematic.lengthZ() : 0);
        this.origin = new BlockPos(originX, originY, originZ);
        this.paused = false;
        this.target = null;
        this.layer = Math.max(0, Baritone.settings().startAtLayer.value);
        configured.reset();
    }

    public void serverTick() {
        if (!isActive() || paused || baritone.getPathExecutor() != null) return;
        updateApproxPlaceable();
        if (target == null || positionComplete(target, desired)) {
            findNextIncorrect();
        }
        if (target == null) {
            onLostControl();
            return;
        }
        BlockState current = baritone.getPlayerContext().world().getBlockState(target);
        // Matching only the block type silently accepts wrong facing, slab half,
        // stair shape, waterlogging, etc. Upstream builder treats a differing
        // state as incorrect and replaces it.
        boolean mustBreak = !current.isAir() && !sameEnough(current, desired);
        if (!withinReach(target)) {
            baritone.pathToGoal(new GoalGetToBlock(target), 2_000L, 8_000L);
            return;
        }
        if (mustBreak) {
            breakTarget(current);
        } else if (!desired.isAir() && current.isAir()) {
            placeTarget();
        } else {
            target = null;
        }
    }

    private void findNextIncorrect() {
        target = null;
        desired = null;
        while (isActive()) {
            int minY = currentMinLayer();
            int maxY = currentMaxLayer();
            scan:
            for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    BlockPos worldPos = origin.offset(x, y, z);
                    BlockState current = baritone.getPlayerContext().world().getBlockState(worldPos);
                    if (!schematic.inSchematic(x, y, z, current)) continue;
                    BlockState wanted = schematic.desiredState(
                            x, y, z, current, approxPlaceable);
                    if (wanted != null && !sameEnough(current, wanted)) {
                        target = worldPos;
                        desired = wanted;
                        break scan;
                    }
                }
            }
        }
            if (target != null || !Baritone.settings().buildInLayers.value) {
                return;
            }
            layer++;
            if (layer * effectiveLayerHeight() >= schematic.heightY()) {
                return;
            }
        }
    }

    private int effectiveLayerHeight() {
        return Math.max(1, Baritone.settings().layerHeight.value);
    }

    private int currentMinLayer() {
        if (!Baritone.settings().buildInLayers.value) return 0;
        int height = effectiveLayerHeight();
        if (Baritone.settings().layerOrder.value) {
            return Math.max(0, schematic.heightY() - (layer + 1) * height);
        }
        return Math.min(schematic.heightY() - 1, layer * height);
    }

    private int currentMaxLayer() {
        if (!Baritone.settings().buildInLayers.value) return schematic.heightY() - 1;
        int height = effectiveLayerHeight();
        if (Baritone.settings().layerOrder.value) {
            return Math.max(0, schematic.heightY() - layer * height - 1);
        }
        return Math.min(schematic.heightY() - 1, (layer + 1) * height - 1);
    }

    private boolean positionComplete(BlockPos pos, BlockState wanted) {
        return wanted != null && sameEnough(
                baritone.getPlayerContext().world().getBlockState(pos), wanted);
    }

    private static boolean sameEnough(BlockState current, BlockState wanted) {
        return current.equals(wanted)
                || (current.isAir() && wanted.isAir());
    }

    private void breakTarget(BlockState current) {
        Optional<Rotation> rotation = RotationUtils.reachable(
                baritone.getPlayerContext(), target, RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE);
        if (rotation.isEmpty()) {
            baritone.pathToGoal(new GoalGetToBlock(target), 2_000L, 8_000L);
            return;
        }
        MovementHelper.switchToBestToolFor(
                baritone.getPlayerContext(),
                BlockStateInterface.get(baritone.getPlayerContext(), target));
        baritone.getInputController().setBlockBreakTarget(target);
        baritone.getLookBehavior().updateTarget(rotation.get(), true);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        baritone.getInputController().tick();
    }

    private void placeTarget() {
        if (!(desired.getBlock().asItem() instanceof BlockItem)
                || !desired.canSurvive(baritone.getPlayerContext().world(), target)
                || !baritone.getInventoryController().selectBlock(desired.getBlock())) {
            paused = true;
            return;
        }
        for (Direction direction : Direction.values()) {
            BlockPos support = target.relative(direction);
            if (baritone.getPlayerContext().world().getBlockState(support).isAir()) continue;
            Optional<Rotation> rotation = RotationUtils.reachable(
                    baritone.getPlayerContext(), support,
                    RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE);
            if (rotation.isPresent()) {
                baritone.getLookBehavior().updateTarget(rotation.get(), true);
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                baritone.getInputController().tick();
                return;
            }
        }
        baritone.pathToGoal(new GoalGetToBlock(target), 2_000L, 8_000L);
    }

    private boolean withinReach(BlockPos pos) {
        return baritone.getPlayerContext().player().getEyePosition()
                .distanceToSqr(pos.getCenter())
                <= RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE
                * RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
    }

    private void updateApproxPlaceable() {
        List<BlockState> states = new ArrayList<>();
        for (ItemStack stack : baritone.getPlayerContext().player()
                .getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof BlockItem blockItem) {
                states.add(blockItem.getBlock().defaultBlockState());
            }
        }
        approxPlaceable = List.copyOf(states);
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        return state.canSurvive(baritone.getPlayerContext().world(), pos)
                && baritone.getPlayerContext().world().isUnobstructed(
                        null, state.getCollisionShape(
                                baritone.getPlayerContext().world(), pos).move(
                                pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    public boolean build(String name, File file, Vec3i origin) {
        DefaultSchematicFormats format = DefaultSchematicFormats.detect(file);
        if (format == null || !file.isFile()) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            IStaticSchematic parsed = format.parse(input);
            ISchematic configured = Baritone.settings().mapArtMode.value
                    ? new MapArtSchematic(parsed) : parsed;
            build(name, configured, origin);
            return true;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Baritone] Failed to load schematic "
                    + file.getAbsolutePath() + ": " + exception.getMessage());
            return false;
        }
    }

    @Override
    public void buildOpenSchematic() {
        throw new UnsupportedOperationException(
                "Client schematic selection is unavailable on a dedicated server; pass a file to build()");
    }

    @Override
    public void buildOpenLitematic(int index) {
        throw new UnsupportedOperationException(
                "Litematica client state is unavailable on a dedicated server; pass a file to build()");
    }
    @Override public void pause() { paused = true; }
    @Override public boolean isPaused() { return paused; }
    @Override public void resume() { paused = false; }
    @Override
    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos min = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ()));
        int width = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int height = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int length = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(
                width, height, length, Blocks.AIR.defaultBlockState()), min);
    }
    @Override public List<BlockState> getApproxPlaceable() { return approxPlaceable; }
    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) return null;
        int relativeX = x - origin.getX();
        int relativeY = y - origin.getY();
        int relativeZ = z - origin.getZ();
        if (!schematic.inSchematic(relativeX, relativeY, relativeZ, current)) return null;
        BlockState wanted = schematic.desiredState(
                relativeX, relativeY, relativeZ, current, approxPlaceable);
        return wanted == null || wanted.isAir() ? null : wanted;
    }
    @Override public Optional<Integer> getMinLayer() {
        return isActive() && Baritone.settings().buildInLayers.value
                ? Optional.of(currentMinLayer()) : Optional.empty();
    }
    @Override public Optional<Integer> getMaxLayer() {
        return isActive() && Baritone.settings().buildInLayers.value
                ? Optional.of(currentMaxLayer()) : Optional.empty();
    }
    @Override public boolean isActive() { return schematic != null; }
    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return new PathingCommand(
                target == null ? null : new GoalGetToBlock(target),
                paused ? PathingCommandType.REQUEST_PAUSE : PathingCommandType.SET_GOAL_AND_PATH);
    }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        schematic = null; target = null; desired = null; name = null;
    }
    @Override public String displayName0() { return "Building " + name; }

    public static class GoalBreak extends GoalGetToBlock {
        public GoalBreak(BlockPos pos) { super(pos); }
        @Override public boolean isInGoal(int x, int y, int z) {
            return y <= this.y && super.isInGoal(x, y, z);
        }
    }
}
