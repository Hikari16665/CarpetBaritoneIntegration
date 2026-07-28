package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
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
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.MapArtSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/** Pure-server schematic builder process. */
public final class BuilderProcess implements IBuilderProcess {
    private static final int SCHEMATIC_SCAN_BUDGET_PER_TICK = 8192;

    private enum ScanResult { FOUND, PENDING, COMPLETE }

    private final Baritone baritone;
    private String name;
    private ISchematic schematic;
    private BlockPos origin;
    private boolean paused;
    private BlockPos target;
    private BlockState desired;
    private List<BlockState> approxPlaceable = Collections.emptyList();
    private int layer;
    private int scanCursor;
    private int tickCount;
    private boolean missingInScan;
    private int completedBuilds;
    private final Map<BlockPos, Integer> failedUntil = new HashMap<>();
    private final Set<BlockPos> incorrectPositions = new LinkedHashSet<>();
    private final Set<BlockPos> observedCompleted = new HashSet<>();
    private Consumer<String> feedback = ignored -> { };
    private boolean missingReported;

    public BuilderProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        buildConfigured(name, schematic, origin,
                Baritone.settings().buildSchematicMirror.value,
                Baritone.settings().buildSchematicRotation.value);
    }

    private void buildConfigured(
            String name, ISchematic schematic, Vec3i origin,
            net.minecraft.world.level.block.Mirror mirror,
            net.minecraft.world.level.block.Rotation rotation) {
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
        if (mirror != net.minecraft.world.level.block.Mirror.NONE) {
            configured = new MirroredSchematic(configured, mirror);
        }
        if (rotation != net.minecraft.world.level.block.Rotation.NONE) {
            configured = new RotatedSchematic(configured, rotation);
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
        this.scanCursor = 0;
        this.tickCount = 0;
        this.missingInScan = false;
        this.completedBuilds = 0;
        this.failedUntil.clear();
        this.incorrectPositions.clear();
        this.observedCompleted.clear();
        this.missingReported = false;
        configured.reset();
    }

    public void serverTick() {
        tickCount++;
        failedUntil.entrySet().removeIf(
                entry -> entry.getValue() <= tickCount);
        if (!isActive() || paused) return;
        updateApproxPlaceable();
        recalcNearby();
        if (baritone.getPathExecutor() != null) {
            if (!baritone.getPathExecutor().isSafeToCancel()
                    || !baritone.getPlayerContext().player().onGround()
                    || !selectImmediateIncorrect()) {
                return;
            }
            baritone.cancelPath();
        }
        if (target != null && positionComplete(target, desired)) {
            incorrectPositions.remove(target);
            observedCompleted.add(target.immutable());
            target = null;
            desired = null;
        }
        if (target == null) {
            ScanResult scan = findNextIncorrect();
            if (scan == ScanResult.PENDING) return;
            if (scan == ScanResult.COMPLETE) {
                if (!repeatBuild()) {
                    feedback.accept("蓝图建造完成"
                            + (name == null ? "" : "：" + name));
                    onLostControl();
                }
                return;
            }
        }
        if (!withinReach(target)) {
            // A completed path may have ended beside a different member of
            // the composite. Re-select from the player's new position before
            // issuing the next calculation.
            selectNextIncorrect();
            Goal approach = assembleApproachGoal();
            if (approach == null) {
                deferFailedTarget(target);
                return;
            }
            if (!baritone.pathToGoal(
                    approach, 2_000L, 8_000L)) {
                deferFailedTarget(target);
            }
            return;
        }
        if (!baritone.getPlayerContext().world().hasChunkAt(target)) {
            deferFailedTarget(target);
            return;
        }
        BlockState current = baritone.getPlayerContext().world()
                .getBlockState(target);
        if (current.getBlock() instanceof LiquidBlock) {
            handleLiquidTarget(current);
            return;
        }
        // Matching only the block type silently accepts wrong facing, slab half,
        // stair shape, waterlogging, etc. Upstream builder treats a differing
        // state as incorrect and replaces it.
        boolean mustBreak = !current.isAir() && !sameEnough(current, desired);
        if (mustBreak) {
            breakTarget(current);
        } else if (!desired.isAir() && current.isAir()) {
            placeTarget();
        } else {
            target = null;
        }
    }

    private ScanResult findNextIncorrect() {
        target = null;
        desired = null;
        if (selectNextIncorrect()) return ScanResult.FOUND;
        while (isActive()) {
            int minY = currentMinLayer();
            int maxY = currentMaxLayer();
            int width = schematic.widthX();
            int length = schematic.lengthZ();
            int layerHeight = maxY - minY + 1;
            int layerVolume = width * length * layerHeight;
            int checked = 0;
            while (scanCursor < layerVolume
                    && checked++ < SCHEMATIC_SCAN_BUDGET_PER_TICK) {
                int index = scanCursor++;
                int x = index % width;
                int yz = index / width;
                int z = yz % length;
                int y = minY + yz / length;
                BlockPos worldPos = origin.offset(x, y, z);
                if (failedUntil.containsKey(worldPos)) continue;
                if (!baritone.getPlayerContext().world()
                        .hasChunkAt(worldPos)) {
                    if (!observedCompleted.contains(worldPos)) {
                        incorrectPositions.add(worldPos.immutable());
                    }
                    if (incorrectPositions.size()
                            >= Baritone.settings().incorrectSize.value) {
                        return selectNextIncorrect()
                                ? ScanResult.FOUND : ScanResult.PENDING;
                    }
                    continue;
                }
                BlockState current = baritone.getPlayerContext().world()
                        .getBlockState(worldPos);
                if (!schematic.inSchematic(x, y, z, current)) continue;
                BlockState wanted = schematic.desiredState(
                        x, y, z, current, approxPlaceable);
                if (wanted != null && !sameEnough(current, wanted)) {
                    incorrectPositions.add(worldPos.immutable());
                    observedCompleted.remove(worldPos);
                    if (!wanted.isAir() && !canPlace(wanted)) {
                        missingInScan = true;
                    } else if (selectNextIncorrect()) {
                        return ScanResult.FOUND;
                    }
                } else {
                    incorrectPositions.remove(worldPos);
                    observedCompleted.add(worldPos.immutable());
                }
            }
            if (scanCursor < layerVolume) {
                return selectNextIncorrect()
                        ? ScanResult.FOUND : ScanResult.PENDING;
            }
            scanCursor = 0;
            if (selectNextIncorrect()) return ScanResult.FOUND;
            if (incorrectPositions.stream()
                    .anyMatch(failedUntil::containsKey)) {
                // A temporarily occluded placement, flowing liquid, or failed
                // route is still known incorrect. Do not advance the layer or
                // announce completion while its retry timer is active.
                return ScanResult.PENDING;
            }
            if (missingInScan
                    && !Baritone.settings().skipFailedLayers.value) {
                reportMissingMaterials();
                missingInScan = false;
                paused = true;
                return ScanResult.PENDING;
            }
            missingInScan = false;
            if (!Baritone.settings().buildInLayers.value) {
                return ScanResult.COMPLETE;
            }
            if (layer * effectiveLayerHeight()
                    >= schematic.heightY()) {
                return ScanResult.COMPLETE;
            }
            layer++;
            incorrectPositions.clear();
        }
        return ScanResult.COMPLETE;
    }

    private boolean selectNextIncorrect() {
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        BlockPos best = null;
        BlockState bestDesired = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        List<BlockPos> nowCorrect = new ArrayList<>();
        for (BlockPos pos : incorrectPositions) {
            if (failedUntil.containsKey(pos)) continue;
            int x = pos.getX() - origin.getX();
            int y = pos.getY() - origin.getY();
            int z = pos.getZ() - origin.getZ();
            boolean loaded = baritone.getPlayerContext().world()
                    .hasChunkAt(pos);
            BlockState current = loaded
                    ? baritone.getPlayerContext().world().getBlockState(pos)
                    : null;
            if (!schematic.inSchematic(x, y, z, current)) {
                nowCorrect.add(pos);
                continue;
            }
            BlockState wanted = schematic.desiredState(
                    x, y, z, current, approxPlaceable);
            if (wanted == null
                    || loaded && sameEnough(current, wanted)) {
                nowCorrect.add(pos);
                observedCompleted.add(pos.immutable());
                continue;
            }
            if (!wanted.isAir() && !canPlace(wanted)) {
                missingInScan = true;
                continue;
            }
            double distance = feet.distSqr(pos);
            double score = distance;
            if (Baritone.settings().distanceTrim.value
                    && distance > 200D) {
                // Same intent as upstream trim(): once nearby work exists,
                // avoid a distant schematic position dominating the goal.
                score += 1.0E9D;
            }
            if (loaded && withinReach(pos)) {
                if (!current.isAir()
                        && !(current.getBlock() instanceof LiquidBlock)
                        && (Baritone.settings().breakFromAbove.value
                                || pos.getY() >= feet.getY())
                        && baritone.getFakeInteractionController()
                                .canBreakFromHere(pos)) {
                    score -= 3.0E9D;
                } else if (current.getBlock() instanceof LiquidBlock
                        && current.getFluidState().isSource()) {
                    score -= 2.0E9D;
                } else if (current.isAir()) {
                    score -= 1.0E9D;
                }
            }
            if (score < bestDistance) {
                best = pos;
                bestDesired = wanted;
                bestDistance = score;
            }
        }
        incorrectPositions.removeAll(nowCorrect);
        if (best == null) return false;
        target = best;
        desired = bestDesired;
        return true;
    }

    /**
     * Ports upstream's toBreakNearPlayer/searchForPlacables pass. A safe
     * movement may be interrupted only for an interaction that has already
     * passed reach, visibility and exact placement-state checks.
     */
    private boolean selectImmediateIncorrect() {
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        BlockPos best = null;
        BlockState bestDesired = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int maximum = Math.max(1,
                Baritone.settings().builderGoalBatchSize.value);
        for (BlockPos pos : nearestCandidates(feet, maximum)) {
            if (!baritone.getPlayerContext().world()
                            .hasChunkAt(pos)
                    || !withinReach(pos)) {
                continue;
            }
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(pos);
            int x = pos.getX() - origin.getX();
            int y = pos.getY() - origin.getY();
            int z = pos.getZ() - origin.getZ();
            if (!schematic.inSchematic(x, y, z, current)) continue;
            BlockState wanted = schematic.desiredState(
                    x, y, z, current, approxPlaceable);
            if (wanted == null || sameEnough(current, wanted)) continue;
            boolean actionable;
            if (current.getBlock() instanceof LiquidBlock) {
                actionable = current.getFluidState().isSource()
                        && ((!wanted.isAir() && canPlace(wanted))
                        || wanted.isAir() && baritone
                                .getInventoryController()
                                .hasGenericThrowaway());
            } else if (!current.isAir()) {
                actionable = (Baritone.settings().breakFromAbove.value
                        || pos.getY() >= feet.getY())
                        && baritone.getFakeInteractionController()
                                .canBreakFromHere(pos);
            } else {
                actionable = !wanted.isAir()
                        && baritone.getInventoryController()
                                .selectBlock(wanted.getBlock())
                        && baritone.getFakeInteractionController()
                                .canPlaceSelectedBlockMatching(
                                        pos, wanted,
                                        preview -> sameEnough(
                                                preview, wanted));
            }
            double distance = feet.distSqr(pos);
            if (actionable && distance < bestDistance) {
                best = pos;
                bestDesired = wanted;
                bestDistance = distance;
            }
        }
        if (best == null) return false;
        target = best;
        desired = bestDesired;
        return true;
    }

    private void recalcNearby() {
        if (!isActive()) return;
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        int radius = Math.max(1,
                Baritone.settings().builderTickScanRadius.value);
        int minLayer = currentMinLayer();
        int maxLayer = currentMaxLayer();
        for (int x = feet.getX() - radius;
             x <= feet.getX() + radius; x++) {
            for (int y = feet.getY() - radius;
                 y <= feet.getY() + radius; y++) {
                int localY = y - origin.getY();
                if (localY < minLayer || localY > maxLayer) continue;
                for (int z = feet.getZ() - radius;
                     z <= feet.getZ() + radius; z++) {
                    int localX = x - origin.getX();
                    int localZ = z - origin.getZ();
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!baritone.getPlayerContext().world()
                            .hasChunkAt(pos)) continue;
                    BlockState current = baritone.getPlayerContext()
                            .world().getBlockState(pos);
                    if (!schematic.inSchematic(
                            localX, localY, localZ, current)) continue;
                    BlockState wanted = schematic.desiredState(
                            localX, localY, localZ,
                            current, approxPlaceable);
                    if (wanted != null
                            && !sameEnough(current, wanted)) {
                        incorrectPositions.add(pos.immutable());
                        observedCompleted.remove(pos);
                    } else {
                        incorrectPositions.remove(pos);
                        observedCompleted.add(pos.immutable());
                    }
                }
            }
        }
    }

    public boolean isPathingGoal(baritone.api.pathing.goals.Goal goal) {
        Goal assembled = isActive() && !paused && target != null
                ? assembleApproachGoal() : null;
        return Objects.equals(assembled, goal);
    }

    /**
     * Mirrors upstream Builder's distinction between standing somewhere that
     * can break a block, standing on a usable placement face, and pillaring
     * directly onto an unsupported position.
     */
    private Goal approachGoal(BlockPos pos) {
        return approachGoal(pos, desired);
    }

    private Goal approachGoal(BlockPos pos, BlockState wanted) {
        if (!baritone.getPlayerContext().world().hasChunkAt(pos)) {
            return new GoalGetToBlock(pos);
        }
        BlockState current = baritone.getPlayerContext().world()
                .getBlockState(pos);
        if (!current.isAir()
                && (wanted == null || !sameEnough(current, wanted))) {
            return breakGoal(pos);
        }
        boolean allowSameLevel = !baritone.getPlayerContext().world()
                .getBlockState(pos.above()).isAir();
        for (Direction direction : Direction.values()) {
            if (direction == Direction.UP) continue;
            BlockPos support = pos.relative(direction);
            BlockState supportState = baritone.getPlayerContext().world()
                    .getBlockState(support);
            if (!supportState.isAir()
                    && !supportState.getCollisionShape(
                            baritone.getPlayerContext().world(), support)
                            .isEmpty()) {
                return new GoalAdjacent(
                        pos, support, allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    /**
     * Upstream Builder paths toward many currently actionable positions at
     * once. Placement goals are primary because arriving at one often unlocks
     * several later placements; break goals remain a fallback.
     */
    private Goal assembleApproachGoal() {
        List<Goal> placements = new ArrayList<>();
        List<Goal> breaks = new ArrayList<>();
        int limit = Math.max(1,
                Baritone.settings().builderGoalBatchSize.value);
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        List<BlockPos> nearest = nearestCandidates(feet, limit);
        for (BlockPos pos : nearest) {
            if (!baritone.getPlayerContext().world().hasChunkAt(pos)) {
                placements.add(new GoalGetToBlock(pos));
                continue;
            }
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(pos);
            int localX = pos.getX() - origin.getX();
            int localY = pos.getY() - origin.getY();
            int localZ = pos.getZ() - origin.getZ();
            if (!schematic.inSchematic(
                    localX, localY, localZ, current)) continue;
            BlockState wanted = schematic.desiredState(
                    localX, localY, localZ, current, approxPlaceable);
            if (wanted == null || sameEnough(current, wanted)) continue;
            if (current.isAir()) {
                if (canPlace(wanted)) {
                    placements.add(approachGoal(pos, wanted));
                }
            } else if (!(current.getBlock() instanceof LiquidBlock)) {
                breaks.add(approachGoal(pos, wanted));
            } else if (current.getFluidState().isSource()
                    && ((!wanted.isAir() && canPlace(wanted))
                    || wanted.isAir() && baritone
                            .getInventoryController()
                            .hasGenericThrowaway())) {
                placements.add(new GoalBlock(pos.above()));
            }
        }
        Goal placementGoal = composite(placements);
        Goal breakGoal = composite(breaks);
        if (placementGoal != null && breakGoal != null) {
            return new JankyGoalComposite(placementGoal, breakGoal);
        }
        if (placementGoal != null) return placementGoal;
        if (breakGoal != null) return breakGoal;
        return target == null ? null : approachGoal(target);
    }

    private List<BlockPos> nearestCandidates(
            BlockPos feet, int maximum) {
        Comparator<BlockPos> farthestFirst = Comparator
                .comparingDouble((BlockPos pos) -> feet.distSqr(pos))
                .reversed();
        PriorityQueue<BlockPos> nearest =
                new PriorityQueue<>(maximum, farthestFirst);
        for (BlockPos pos : incorrectPositions) {
            if (failedUntil.containsKey(pos)) continue;
            if (nearest.size() < maximum) {
                nearest.add(pos);
            } else if (feet.distSqr(pos)
                    < feet.distSqr(Objects.requireNonNull(
                            nearest.peek()))) {
                nearest.poll();
                nearest.add(pos);
            }
        }
        List<BlockPos> result = new ArrayList<>(nearest);
        result.sort(Comparator.comparingDouble(feet::distSqr));
        return result;
    }

    private static Goal composite(List<Goal> goals) {
        if (goals.isEmpty()) return null;
        if (goals.size() == 1) return goals.getFirst();
        return new GoalComposite(goals.toArray(Goal[]::new));
    }

    private Goal breakGoal(BlockPos pos) {
        if (Baritone.settings().goalBreakFromAbove.value
                && baritone.getPlayerContext().world()
                        .getBlockState(pos.above()).isAir()
                && baritone.getPlayerContext().world()
                        .getBlockState(pos.above(2)).isAir()) {
            return new JankyGoalComposite(
                    new GoalBreak(pos),
                    new GoalBreakFromAboveFallback(pos));
        }
        return new GoalBreak(pos);
    }

    public CalculationContext calculationContext(
            baritone.api.pathing.goals.Goal goal) {
        return new BuilderCalculationContext(goal);
    }

    private final class BuilderCalculationContext
            extends CalculationContext {
        private final List<BlockState> placeable;
        private final ISchematic buildSchematic;
        private final BlockPos buildOrigin;

        private BuilderCalculationContext(
                baritone.api.pathing.goals.Goal goal) {
            super(BuilderProcess.this.baritone, true, goal);
            this.placeable = List.copyOf(approxPlaceable);
            this.buildSchematic = schematic;
            this.buildOrigin = origin;
            this.jumpPenalty += 10D;
            this.backtrackCostFavoringCoefficient = 1D;
        }

        private BlockState schematicAt(
                int x, int y, int z, BlockState current) {
            int localX = x - buildOrigin.getX();
            int localY = y - buildOrigin.getY();
            int localZ = z - buildOrigin.getZ();
            if (!buildSchematic.inSchematic(
                    localX, localY, localZ, current)) {
                return null;
            }
            return buildSchematic.desiredState(
                    localX, localY, localZ, current, placeable);
        }

        @Override
        public double costOfPlacingAt(
                int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z)
                    || !worldBorder.canPlaceAt(x, z)) {
                return COST_INF;
            }
            BlockState wanted = schematicAt(x, y, z, current);
            if (wanted == null) {
                return hasThrowaway ? placeBlockCost : COST_INF;
            }
            if (wanted.isAir()) {
                return hasThrowaway
                        ? placeBlockCost * Baritone.settings()
                                .placeIncorrectBlockPenaltyMultiplier.value
                        : COST_INF;
            }
            if (placeable.stream().anyMatch(
                    state -> sameEnough(state, wanted))) {
                return 0D;
            }
            return hasThrowaway
                    ? placeBlockCost * 1.5D * Baritone.settings()
                            .placeIncorrectBlockPenaltyMultiplier.value
                    : COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(
                int x, int y, int z, BlockState current) {
            if ((!allowBreak
                    && !allowBreakAnyway.contains(current.getBlock()))
                    || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState wanted = schematicAt(x, y, z, current);
            if (wanted != null && !wanted.isAir()
                    && sameEnough(current, wanted)) {
                return Baritone.settings()
                        .breakCorrectBlockPenaltyMultiplier.value;
            }
            return 1D;
        }
    }

    private int effectiveLayerHeight() {
        return Math.max(1, Baritone.settings().layerHeight.value);
    }

    private boolean repeatBuild() {
        Vec3i repeat = Baritone.settings().buildRepeat.value;
        completedBuilds++;
        int maximum = Baritone.settings().buildRepeatCount.value;
        if (repeat.equals(Vec3i.ZERO)
                || maximum != -1 && completedBuilds >= maximum) {
            return false;
        }
        origin = origin.offset(repeat);
        layer = Math.max(0, Baritone.settings().startAtLayer.value);
        scanCursor = 0;
        missingInScan = false;
        missingReported = false;
        target = null;
        desired = null;
        failedUntil.clear();
        incorrectPositions.clear();
        observedCompleted.clear();
        if (!Baritone.settings().buildRepeatSneaky.value) {
            schematic.reset();
        }
        return true;
    }

    private int currentMinLayer() {
        if (!Baritone.settings().buildInLayers.value) return 0;
        return layerBounds(schematic.heightY(), layer,
                effectiveLayerHeight(),
                Baritone.settings().layerOrder.value)[0];
    }

    private int currentMaxLayer() {
        if (!Baritone.settings().buildInLayers.value) return schematic.heightY() - 1;
        return layerBounds(schematic.heightY(), layer,
                effectiveLayerHeight(),
                Baritone.settings().layerOrder.value)[1];
    }

    static int[] layerBounds(
            int schematicHeight, int layer,
            int layerHeight, boolean topDown) {
        int safeHeight = Math.max(0, schematicHeight);
        int completedHeight = Math.max(0, layer)
                * Math.max(1, layerHeight);
        if (topDown) {
            return new int[] {
                    Math.max(0, safeHeight - completedHeight),
                    safeHeight - 1
            };
        }
        return new int[] {
                0,
                Math.min(safeHeight - 1, completedHeight - 1)
        };
    }

    private boolean positionComplete(BlockPos pos, BlockState wanted) {
        return wanted != null
                && baritone.getPlayerContext().world().hasChunkAt(pos)
                && sameEnough(
                baritone.getPlayerContext().world().getBlockState(pos), wanted);
    }

    private static boolean sameEnough(BlockState current, BlockState wanted) {
        if (current.equals(wanted)
                || current.isAir() && wanted.isAir()) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock
                && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.isAir() && Baritone.settings().okIfAir.value
                .contains(wanted.getBlock())) {
            return true;
        }
        if (wanted.isAir() && Baritone.settings().buildIgnoreBlocks.value
                .contains(current.getBlock())) {
            return true;
        }
        if (!current.isAir()
                && Baritone.settings().buildIgnoreExisting.value) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value
                .getOrDefault(wanted.getBlock(), Collections.emptyList())
                .contains(current.getBlock())) {
            return true;
        }
        if (current.getBlock() != wanted.getBlock()
                || !current.getProperties()
                        .equals(wanted.getProperties())) {
            return false;
        }
        boolean ignoreDirection =
                Baritone.settings().buildIgnoreDirection.value;
        List<String> ignored =
                Baritone.settings().buildIgnoreProperties.value;
        for (Property<?> property : current.getProperties()) {
            if (ignored.contains(property.getName())
                    || ignoreDirection
                    && isOrientationProperty(property.getName())) {
                continue;
            }
            if (!current.getValue(property)
                    .equals(wanted.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOrientationProperty(String name) {
        return name.equals("axis") || name.equals("facing")
                || name.equals("half") || name.equals("shape")
                || name.equals("north") || name.equals("east")
                || name.equals("south") || name.equals("west")
                || name.equals("up") || name.equals("open");
    }

    private void breakTarget(BlockState current) {
        if (!baritone.getFakeInteractionController().canReach(target)) {
            baritone.pathToGoal(new GoalGetToBlock(target), 2_000L, 8_000L);
            return;
        }
        if (!baritone.getFakeInteractionController()
                .canBreakFromHere(target)) {
            // Fake interaction removes crosshair alignment, not solid
            // occlusion. Let another member of the Builder composite proceed
            // instead of retrying an occluded block forever.
            deferFailedTarget(target, 20);
            return;
        }
        baritone.getFakeInteractionController().breakBlock(target);
    }

    /**
     * A pure fluid block cannot be mined. As in upstream Builder, a source is
     * first replaced by a block; if the schematic wants air, that temporary
     * block is picked up by the normal break pass. Flowing cells are left
     * pending until their source update settles.
     */
    private void handleLiquidTarget(BlockState current) {
        if (!current.getFluidState().isSource()) {
            deferFailedTarget(target, 20);
            return;
        }
        boolean selected;
        if (desired != null && !desired.isAir()) {
            selected = baritone.getInventoryController()
                    .selectBlock(desired.getBlock());
        } else {
            selected = baritone.getInventoryController()
                    .selectThrowawayForLocation(
                            true, target.getX(), target.getY(),
                            target.getZ());
        }
        if (!selected) {
            if (desired != null && !desired.isAir()
                    && canPlace(desired)
                    || desired != null && desired.isAir()
                    && baritone.getInventoryController()
                            .hasGenericThrowaway()) {
                // The material exists but the original inventory scheduler is
                // waiting for its movement delay/stationary condition.
                return;
            }
            missingInScan = true;
            reportMissingMaterials();
            paused = true;
            return;
        }
        boolean placed = desired != null && !desired.isAir()
                ? baritone.getFakeInteractionController()
                        .fillFluidWithSelectedBlockMatching(
                                target, desired,
                                preview -> sameEnough(preview, desired))
                : baritone.getFakeInteractionController()
                        .fillFluidWithSelectedBlock(target);
        if (!placed) {
            deferFailedTarget(target, 20);
        }
    }

    private void placeTarget() {
        if (!(desired.getBlock().asItem() instanceof BlockItem)
                || !desired.canSurvive(
                        baritone.getPlayerContext().world(), target)) {
            missingInScan = true;
            target = null;
            desired = null;
            return;
        }
        if (!baritone.getInventoryController()
                .selectBlock(desired.getBlock())) {
            if (canPlace(desired)) {
                // InventoryBehavior may intentionally defer the hotbar swap
                // until stationary or until ticksBetweenInventoryMoves has
                // elapsed. Keep the target instead of misreporting it missing.
                return;
            }
            missingInScan = true;
            target = null;
            desired = null;
            return;
        }
        if (baritone.getFakeInteractionController()
                .placeSelectedBlockMatching(
                        target, desired,
                        preview -> sameEnough(preview, desired))) {
            return;
        }
        Goal approach = approachGoal(target, desired);
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        if (approach.isInGoal(
                feet.getX(), feet.getY(), feet.getZ())) {
            // We are already at the stance this placement goal requested, so
            // another identical zero-length path cannot create a legal face.
            deferFailedTarget(target, 20);
            return;
        }
        if (!baritone.pathToGoal(approach, 2_000L, 8_000L)) {
            deferFailedTarget(target);
        }
    }

    private boolean canPlace(BlockState wanted) {
        if (!(wanted.getBlock().asItem() instanceof BlockItem)) return false;
        return baritone.getInventoryController().hasAccessibleItem(
                stack -> stack.getItem() instanceof BlockItem blockItem
                        && blockItem.getBlock() == wanted.getBlock());
    }

    private void deferFailedTarget(BlockPos failed) {
        deferFailedTarget(failed, 100);
    }

    private void deferFailedTarget(BlockPos failed, int ticks) {
        failedUntil.put(failed.immutable(),
                tickCount + Math.max(1, ticks));
        target = null;
        desired = null;
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
            addPlaceableState(states, stack);
            ItemContainerContents contents = stack.getOrDefault(
                    DataComponents.CONTAINER,
                    ItemContainerContents.EMPTY);
            contents.nonEmptyStream().forEach(
                    nested -> addPlaceableState(states, nested));
        }
        approxPlaceable = List.copyOf(states);
    }

    private static void addPlaceableState(
            List<BlockState> states, ItemStack stack) {
        if (!stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem) {
            states.add(blockItem.getBlock().defaultBlockState());
        }
    }

    public void setFeedback(Consumer<String> feedback) {
        this.feedback = feedback == null ? ignored -> { } : feedback;
    }

    /**
     * Bounded server snapshot used by the existing nearby-player path
     * renderer. It deliberately reports only loaded positions so producing a
     * visual frame can never load chunks or advance the schematic.
     */
    public List<BlockPos> renderTargets(
            boolean placements, int maximum) {
        if (!isActive() || maximum <= 0) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : incorrectPositions) {
            if (result.size() >= maximum) break;
            if (!baritone.getPlayerContext().world().hasChunkAt(pos)) continue;
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(pos);
            int x = pos.getX() - origin.getX();
            int y = pos.getY() - origin.getY();
            int z = pos.getZ() - origin.getZ();
            if (!schematic.inSchematic(x, y, z, current)) continue;
            BlockState wanted = schematic.desiredState(
                    x, y, z, current, approxPlaceable);
            if (wanted == null || sameEnough(current, wanted)) continue;
            boolean isPlacement = current.isAir()
                    || current.getBlock() instanceof LiquidBlock;
            if (placements == isPlacement) {
                result.add(pos.immutable());
            }
        }
        return List.copyOf(result);
    }

    private void reportMissingMaterials() {
        if (missingReported) return;
        Map<BlockState, Integer> missing = new HashMap<>();
        for (BlockPos pos : incorrectPositions) {
            if (!baritone.getPlayerContext().world().hasChunkAt(pos)) continue;
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(pos);
            int x = pos.getX() - origin.getX();
            int y = pos.getY() - origin.getY();
            int z = pos.getZ() - origin.getZ();
            if (!schematic.inSchematic(x, y, z, current)) continue;
            BlockState wanted = schematic.desiredState(
                    x, y, z, current, approxPlaceable);
            if (wanted != null && current.isAir()
                    && !canPlace(wanted)) {
                missing.merge(wanted, 1, Integer::sum);
            }
        }
        String details = missing.entrySet().stream()
                .limit(12)
                .map(entry -> entry.getValue() + "x "
                        + BuiltInRegistries.BLOCK.getKey(
                                entry.getKey().getBlock()))
                .collect(Collectors.joining("，"));
        feedback.accept(details.isEmpty()
                ? "当前层没有可执行的建造目标，Builder 已暂停"
                : "缺少建材，Builder 已暂停：" + details);
        missingReported = true;
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
        return buildTransformed(name, file, origin,
                Baritone.settings().buildSchematicMirror.value,
                Baritone.settings().buildSchematicRotation.value);
    }

    @Override
    public boolean buildTransformed(
            String name, File file, Vec3i origin,
            net.minecraft.world.level.block.Mirror mirror,
            net.minecraft.world.level.block.Rotation rotation) {
        DefaultSchematicFormats format = DefaultSchematicFormats.detect(file);
        if (format == null || !file.isFile()) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            IStaticSchematic parsed = format.parse(input);
            ISchematic configured = Baritone.settings().mapArtMode.value
                    ? new MapArtSchematic(parsed) : parsed;
            buildConfigured(name, configured, origin, mirror, rotation);
            return true;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Baritone] Failed to load schematic "
                    + file.getAbsolutePath() + ": " + exception.getMessage());
            return false;
        }
    }

    @Override
    public void buildOpenSchematic() {
        List<File> files = serverSchematics(file -> true);
        if (files.isEmpty()) {
            feedback.accept(
                    "服务器 schematics 目录中没有受支持的蓝图");
            return;
        }
        File selected = files.stream()
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(files.getFirst());
        if (!build(selected.getName(), selected,
                baritone.getPlayerContext().playerFeet())) {
            feedback.accept("无法加载服务器蓝图："
                    + selected.getPath());
        }
    }

    @Override
    public void buildOpenLitematic(int index) {
        List<File> files = serverSchematics(file ->
                file.getName().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".litematic"));
        if (index < 0 || index >= files.size()) {
            feedback.accept("服务器 Litematica 蓝图索引不存在："
                    + index + "（当前共 " + files.size() + " 个）");
            return;
        }
        File selected = files.get(index);
        if (!build(selected.getName(), selected,
                baritone.getPlayerContext().playerFeet())) {
            feedback.accept("无法加载 Litematica 蓝图："
                    + selected.getPath());
        }
    }

    private static List<File> serverSchematics(
            Predicate<File> filter) {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.walk(root, 8)) {
            return paths.filter(Files::isRegularFile)
                    .limit(4096)
                    .map(Path::toFile)
                    .filter(file -> DefaultSchematicFormats.detect(file)
                            != null)
                    .filter(filter)
                    .sorted(Comparator.comparing(
                            File::getPath,
                            String.CASE_INSENSITIVE_ORDER))
                    .limit(1024)
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }
    @Override public void pause() { paused = true; }
    @Override public boolean isPaused() { return paused; }
    @Override public void resume() {
        paused = false;
        missingReported = false;
    }
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
        if (paused) {
            return new PathingCommand(
                    null, PathingCommandType.REQUEST_PAUSE);
        }
        Goal goal = target == null ? null : assembleApproachGoal();
        if (goal == null) {
            return new PathingCommand(
                    null,
                    PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }
        return new PathingCommandContext(
                goal,
                PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH,
                calculationContext(goal));
    }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        schematic = null; target = null; desired = null; name = null;
        origin = null;
        paused = false;
        layer = Math.max(0, Baritone.settings().startAtLayer.value);
        scanCursor = 0;
        missingInScan = false;
        completedBuilds = 0;
        failedUntil.clear();
        incorrectPositions.clear();
        observedCompleted.clear();
        approxPlaceable = Collections.emptyList();
        missingReported = false;
        feedback = ignored -> { };
    }
    @Override public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    public static class GoalBreak extends GoalGetToBlock {
        public GoalBreak(BlockPos pos) { super(pos); }
        @Override public boolean isInGoal(int x, int y, int z) {
            return y <= this.y && super.isInGoal(x, y, z);
        }
    }

    public static final class GoalBreakFromAboveFallback
            extends GoalGetToBlock {
        public GoalBreakFromAboveFallback(BlockPos breakAt) {
            super(breakAt.above());
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (y > this.y
                    || x == this.x && y == this.y && z == this.z) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }
    }

    public static final class JankyGoalComposite implements Goal {
        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        public Goal primary() {
            return primary;
        }

        public Goal fallback() {
            return fallback;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z)
                    || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof JankyGoalComposite that)) return false;
            return primary.equals(that.primary)
                    && fallback.equals(that.fallback);
        }

        @Override
        public int hashCode() {
            return Objects.hash(primary, fallback);
        }
    }

    public static final class GoalAdjacent extends GoalGetToBlock {
        private final boolean allowSameLevel;
        private final BlockPos excluded;

        public GoalAdjacent(BlockPos pos, BlockPos excluded,
                            boolean allowSameLevel) {
            super(pos);
            this.excluded = excluded.immutable();
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) return false;
            if (x == excluded.getX() && y == excluded.getY()
                    && z == excluded.getZ()) return false;
            if (!allowSameLevel && y == this.y - 1) return false;
            return y >= this.y - 1 && super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return this.y * 100D + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object other) {
            if (!super.equals(other)) return false;
            GoalAdjacent that = (GoalAdjacent) other;
            return allowSameLevel == that.allowSameLevel
                    && excluded.equals(that.excluded);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), allowSameLevel, excluded);
        }
    }

    public static final class GoalPlace extends GoalBlock {
        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return this.y * 100D + super.heuristic(x, y, z);
        }
    }
}
