package baritone.process;

import baritone.Baritone;
import baritone.api.PrinterBuildMode;
import baritone.api.PrinterQueueMode;
import baritone.api.PrinterScanShape;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
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
import baritone.api.utils.interfaces.IGoalRenderPos;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
    private final BuilderMaterialRecovery materialRecovery;
    private String name;
    private ISchematic schematic;
    private BlockPos origin;
    private boolean paused;
    private BlockPos target;
    private BlockState desired;
    /**
     * The approach goal published for {@link #target}. Nearby schematic scans
     * keep discovering positions while a path is executing; rebuilding the
     * composite from that changing set every controller tick makes an
     * otherwise valid route look like a moving goal.
     */
    private BlockPos publishedGoalTarget;
    private Goal publishedApproachGoal;
    private boolean publishedTargetChunkLoaded;
    private List<BlockState> approxPlaceable = Collections.emptyList();
    private int layer;
    private int scanCursor;
    private int tickCount;
    private boolean missingInScan;
    private int completedBuilds;
    private final Map<BlockPos, Integer> failedUntil = new HashMap<>();
    private final Set<BlockPos> incorrectPositions = new LinkedHashSet<>();
    private final Set<BlockPos> observedCompleted = new HashSet<>();
    private final Set<BlockPos> pathingSupports = new LinkedHashSet<>();
    private boolean cleaningPathingSupports;
    private final Set<String> unavailableMaterialKeys = new LinkedHashSet<>();
    private Consumer<String> feedback = ignored -> { };
    private boolean missingReported;

    public BuilderProcess(Baritone baritone) {
        this.baritone = baritone;
        this.materialRecovery = new BuilderMaterialRecovery(baritone);
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
        this.publishedGoalTarget = null;
        this.publishedApproachGoal = null;
        this.publishedTargetChunkLoaded = false;
        this.layer = Math.max(0, Baritone.settings().startAtLayer.value);
        this.scanCursor = 0;
        this.tickCount = 0;
        this.missingInScan = false;
        this.completedBuilds = 0;
        this.failedUntil.clear();
        this.incorrectPositions.clear();
        this.observedCompleted.clear();
        this.pathingSupports.clear();
        this.cleaningPathingSupports = false;
        this.missingReported = false;
        this.unavailableMaterialKeys.clear();
        this.materialRecovery.clear();
        configured.reset();
    }

    public void serverTick() {
        tickCount++;
        failedUntil.entrySet().removeIf(
                entry -> entry.getValue() <= tickCount);
        if (!isActive()) return;
        if (materialRecovery.isActive()) {
            BuilderMaterialRecovery.Result recovery =
                    materialRecovery.tick();
            if (recovery == BuilderMaterialRecovery.Result.WORKING) {
                return;
            }
            if (recovery == BuilderMaterialRecovery.Result.EXHAUSTED) {
                String unavailable = materialRecovery.key();
                if (unavailable != null) {
                    unavailableMaterialKeys.add(unavailable);
                }
                materialRecovery.clear();
                missingInScan = true;
                // Continue scanning the rest of the layer. One unavailable
                // special item must not suppress unrelated build actions.
                return;
            }
            if (recovery == BuilderMaterialRecovery.Result.ACQUIRED) {
                missingInScan = false;
                missingReported = false;
                updateApproxPlaceable();
            }
        }
        if (paused) return;
        updateApproxPlaceable();
        recalcNearby();
        if (baritone.getPathExecutor() == null
                && Baritone.settings().printerContinuousActions.value
                && Baritone.settings().printerQueueMode.value
                == PrinterQueueMode.MULTI
                && runPrinterPlacementBatch()) {
            return;
        }
        if (baritone.getPathExecutor() != null) {
            // Never construct while travelling through the schematic. The
            // old immediate-action pass could place walls around the player
            // before it reached its selected stance, trapping it inside the
            // building and invalidating the remaining path.
            return;
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
            Goal approach = approachGoal(target, desired);
            if (approach == null) {
                deferFailedTarget(target);
                return;
            }
            // onTick() publishes this goal on the next controller pass.
            // Starting a second executor here races the process scheduler.
            return;
        }
        if (!baritone.getPlayerContext().world().hasChunkAt(target)) {
            deferFailedTarget(target);
            return;
        }
        BlockState current = baritone.getPlayerContext().world()
                .getBlockState(target);
        if (current.isAir() && tryPortalIgnition()) return;
        if (!current.isAir() && trySpecialTransformation(current)) {
            return;
        }
        if (current.getBlock() instanceof LiquidBlock) {
            handleLiquidTarget(current);
            return;
        }
        // Matching only the block type silently accepts wrong facing, slab half,
        // stair shape, waterlogging, etc. Upstream builder treats a differing
        // state as incorrect and replaces it.
        boolean mustBreak = !current.isAir()
                && !printerSatisfied(current, desired);
        if (mustBreak) {
            breakTarget(current);
        } else if (!desired.isAir() && current.isAir()) {
            placeTarget();
        } else {
            target = null;
        }
    }

    /**
     * Printer MULTI mode: commit several independent, already reachable
     * placements in one server tick. Timed breaking and fluid replacement are
     * transactions of their own and therefore terminate the current batch.
     */
    private boolean runPrinterPlacementBatch() {
        int budget = Math.max(1,
                Baritone.settings().printerMaxActionsPerTick.value);
        boolean acted = false;
        for (int action = 0; action < budget; action++) {
            if (target != null && positionComplete(target, desired)) {
                incorrectPositions.remove(target);
                observedCompleted.add(target.immutable());
                target = null;
                desired = null;
            }
            if (!selectImmediateIncorrect()) return acted;
            BlockState current = baritone.getPlayerContext().world()
                    .getBlockState(target);
            if (current.isAir() && tryPortalIgnition()) {
                acted = true;
                continue;
            }
            if (current.getBlock() instanceof LiquidBlock) {
                if (!Baritone.settings().printerPlaceFluids.value) {
                    deferFailedTarget(target,
                            Baritone.settings().printerFailureRetryTicks.value);
                    return acted;
                }
                handleLiquidTarget(current);
                return true;
            }
            if (!current.isAir()) {
                if (trySpecialTransformation(current)) {
                    acted = true;
                    continue;
                }
                breakTarget(current);
                return true;
            }
            if (desired == null || desired.isAir()
                    || Baritone.settings().printerBuildMode.value
                    == PrinterBuildMode.EXCAVATE) {
                target = null;
                desired = null;
                continue;
            }
            BlockState placement = placementStageState(current, desired);
            if (placement == null) return acted;
            if (desired.getBlock() instanceof LiquidBlock
                    && !desired.getFluidState().isEmpty()) {
                if (!desired.getFluidState().isSource()) {
                    deferFailedTarget(target,
                            Baritone.settings()
                                    .printerFailureRetryTicks.value);
                    return acted;
                }
                if (!ensureFluidMaterial(desired.getFluidState())) {
                    pauseForUnavailableAuxiliary();
                    return acted;
                }
                if (!Baritone.settings().printerPlaceFluids.value
                        || !baritone.getFakeInteractionController()
                        .placeFluid(target, desired.getFluidState())) {
                    return acted;
                }
                acted = true;
                continue;
            }
            if (!selectPlacementItem(placement)) {
                requestContainerRefill(placement);
                return acted;
            }
            BlockPos placedAt = target;
            BlockState placedState = placement;
            if (!ensureAuxiliaryMaterial(placedState)) {
                pauseForUnavailableAuxiliary();
                return acted;
            }
            boolean placed = baritone.getFakeInteractionController()
                    .placeSelectedBlockMatching(
                            placedAt, placedState,
                            preview -> sameEnough(preview, placedState));
            if (!placed) return acted;
            acted = true;
            if (positionComplete(placedAt, placedState)) {
                incorrectPositions.remove(placedAt);
                observedCompleted.add(placedAt.immutable());
                target = null;
                desired = null;
            } else {
                // Queue cooling or a multi-step block kept the target pending.
                return true;
            }
        }
        return acted;
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
                if (wanted != null && !printerSatisfied(current, wanted)) {
                    incorrectPositions.add(worldPos.immutable());
                    observedCompleted.remove(worldPos);
                    if (!pathingSupports.contains(worldPos)) {
                        cleaningPathingSupports = false;
                    }
                    if (!wanted.isAir()
                            && !canSatisfy(current, wanted)) {
                        if (requestRequiredMaterial(current, wanted)) {
                            return ScanResult.PENDING;
                        }
                        missingInScan = true;
                    } else if (selectNextIncorrect()) {
                        return ScanResult.FOUND;
                    }
                } else {
                    incorrectPositions.remove(worldPos);
                    observedCompleted.add(worldPos.immutable());
                    pathingSupports.remove(worldPos);
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
            if (Baritone.settings().buildInLayers.value
                    && layer * effectiveLayerHeight()
                    < schematic.heightY()) {
                layer++;
                incorrectPositions.clear();
                continue;
            }
            /*
             * Temporary bridge/pillar blocks survive every construction
             * layer. Only after the entire schematic has no ordinary work do
             * we enter a separate cleanup pass. Add the owned positions
             * explicitly because a bridge may lie outside the schematic.
             */
            pathingSupports.removeIf(pos ->
                    baritone.getPlayerContext().world().hasChunkAt(pos)
                            && baritone.getPlayerContext().world()
                            .getBlockState(pos).isAir());
            if (!pathingSupports.isEmpty()) {
                cleaningPathingSupports = true;
                incorrectPositions.addAll(pathingSupports);
                if (selectNextIncorrect()) return ScanResult.FOUND;
                return ScanResult.PENDING;
            }
            return ScanResult.COMPLETE;
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
            if (pathingSupports.contains(pos)
                    && !cleaningPathingSupports) continue;
            int x = pos.getX() - origin.getX();
            int y = pos.getY() - origin.getY();
            int z = pos.getZ() - origin.getZ();
            boolean loaded = baritone.getPlayerContext().world()
                    .hasChunkAt(pos);
            BlockState current = loaded
                    ? baritone.getPlayerContext().world().getBlockState(pos)
                    : null;
            if (cleaningPathingSupports
                    && pathingSupports.contains(pos)) {
                if (loaded && current.isAir()) {
                    nowCorrect.add(pos);
                    pathingSupports.remove(pos);
                    continue;
                }
                double distance = feet.distSqr(pos);
                if (distance < bestDistance) {
                    best = pos;
                    bestDesired = Blocks.AIR.defaultBlockState();
                    bestDistance = distance;
                }
                continue;
            }
            if (!schematic.inSchematic(x, y, z, current)) {
                nowCorrect.add(pos);
                continue;
            }
            BlockState wanted = schematic.desiredState(
                    x, y, z, current, approxPlaceable);
            if (wanted == null
                    || loaded && printerSatisfied(current, wanted)) {
                nowCorrect.add(pos);
                observedCompleted.add(pos.immutable());
                continue;
            }
            if (!wanted.isAir()
                    && !canSatisfy(current, wanted)) {
                requestRequiredMaterial(current, wanted);
                missingInScan = true;
                continue;
            }
            double distance = feet.distSqr(pos);
            double score = distance
                    + printerDependencyPriority(wanted);
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
        prepareTargetMaterial();
        return true;
    }

    /**
     * Records a block placed by a path movement rather than by the schematic
     * printer. Such blocks remain available as route support until the current
     * layer has no other actionable schematic targets.
     */
    public void recordPathingSupport(BlockPos pos) {
        if (!isActive() || pos == null) return;
        pathingSupports.add(pos.immutable());
        cleaningPathingSupports = false;
    }

    /**
     * Inventory shulkers count as available during schematic scanning, but
     * their contents are not directly selectable. Unpack the selected target's
     * first required stack before walking to it instead of waiting until the
     * fake player has already reached the placement stance.
     */
    private void prepareTargetMaterial() {
        if (target == null || desired == null || desired.isAir()
                || !baritone.getPlayerContext().world().hasChunkAt(target)) {
            return;
        }
        BlockState current = baritone.getPlayerContext().world()
                .getBlockState(target);
        if (current.isAir() && desired.is(Blocks.NETHER_PORTAL)) {
            baritone.getInventoryController()
                    .prepareItemForBuilder(portalIgnitionItem());
            return;
        }
        Predicate<ItemStack> transformation =
                specialTransformationTool(current, desired);
        if (transformation != null) {
            baritone.getInventoryController()
                    .prepareItemForBuilder(transformation);
            return;
        }
        BlockState placement = placementStageState(current, desired);
        if (placement != null
                && placement.getBlock().asItem() != Items.AIR) {
            net.minecraft.world.item.Item item =
                    placement.getBlock().asItem();
            baritone.getInventoryController()
                    .prepareItemForBuilder(stack -> stack.is(item));
        }
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
            if (wanted == null || printerSatisfied(current, wanted)) continue;
            boolean actionable;
            if (current.getBlock() instanceof LiquidBlock) {
                actionable = current.getFluidState().isSource()
                        && ((!wanted.isAir()
                            && canSatisfy(current, wanted))
                        || wanted.isAir() && baritone
                                .getInventoryController()
                                .hasGenericThrowaway());
            } else if (!current.isAir()) {
                actionable = canTransform(current, wanted)
                        || ((Baritone.settings().breakFromAbove.value
                            || pos.getY() >= feet.getY())
                            && baritone.getFakeInteractionController()
                                    .canBreakFromHere(pos));
            } else {
                if (wanted.is(Blocks.NETHER_PORTAL)) {
                    actionable = canIgnitePortal();
                } else {
                    BlockState placement = placementStageState(
                            current, wanted);
                    actionable = placement != null
                        && selectPlacementItem(placement)
                        && baritone.getFakeInteractionController()
                                .canPlaceSelectedBlockMatching(
                                        pos, placement,
                                        preview -> sameEnough(
                                                preview, placement));
                }
            }
            double distance = feet.distSqr(pos)
                    + printerDependencyPriority(wanted);
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

    /**
     * Printer continuous-action ordering. Placing the lower/foot half first
     * lets vanilla create the upper/head half atomically; the subsequent
     * schematic check then consumes no second item and performs no stray
     * click.
     */
    private static double printerDependencyPriority(BlockState state) {
        double priority = 0.0D;
        if (!state.getFluidState().isEmpty()) {
            priority += state.getFluidState().isSource()
                    ? -2.0E6D : 2.0E6D;
        }
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            String value = String.valueOf(state.getValue(property));
            if (name.equals("half")) {
                if (value.equals("lower") || value.equals("bottom")) {
                    priority -= 1.0E6D;
                } else if (value.equals("upper")
                        || value.equals("top")) {
                    priority += 1.0E6D;
                }
            } else if (name.equals("part")) {
                if (value.equals("foot")) priority -= 1.0E6D;
                if (value.equals("head")) priority += 1.0E6D;
            }
        }
        return priority;
    }

    private void recalcNearby() {
        if (!isActive()) return;
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        int radius = Math.max(1,
                Math.max(Baritone.settings().builderTickScanRadius.value,
                        Baritone.settings().printerRange.value));
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
                    if (Baritone.settings().printerScanShape.value
                            == PrinterScanShape.SPHERE) {
                        long dx = x - feet.getX();
                        long dy = y - feet.getY();
                        long dz = z - feet.getZ();
                        if (dx * dx + dy * dy + dz * dz
                                > (long) radius * radius) {
                            continue;
                        }
                    }
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
                            && !printerSatisfied(current, wanted)) {
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
                && Objects.equals(target, publishedGoalTarget)
                ? publishedApproachGoal : null;
        return Objects.equals(assembled, goal);
    }

    /**
     * Keep the selected composite stable for the lifetime of the current
     * Builder target. The target change is the scheduling boundary at which a
     * newly discovered set of actionable positions may replace the old goal.
     */
    private Goal publishedApproachGoal() {
        if (target == null) {
            publishedGoalTarget = null;
            publishedApproachGoal = null;
            publishedTargetChunkLoaded = false;
            return null;
        }
        boolean targetChunkLoaded = baritone.getPlayerContext().world()
                .hasChunkAt(target);
        if (publishedApproachGoal == null
                || !Objects.equals(publishedGoalTarget, target)
                || publishedTargetChunkLoaded != targetChunkLoaded) {
            publishedGoalTarget = target.immutable();
            publishedTargetChunkLoaded = targetChunkLoaded;
            publishedApproachGoal = assembleApproachGoal();
        }
        return publishedApproachGoal;
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
            // Stop outside the not-yet-loaded target chunk. Once it loads,
            // publishedApproachGoal() replaces this coarse goal with exact,
            // world-validated interaction stances.
            return new GoalNear(pos, 12);
        }
        List<BlockPos> stances = safeInteractionStances(pos);
        return stances.isEmpty() ? null
                : new GoalBuilderStance(pos, stances);
    }

    private List<BlockPos> safeInteractionStances(BlockPos target) {
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        List<BlockPos> result = new ArrayList<>();
        double reach = RotationUtils.DEFAULT_BLOCK_REACH_DISTANCE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos stance = target.offset(dx, dy, dz);
                    if (stance.equals(target)
                            || !finalSpacePassable(stance)
                            || !finalSpacePassable(stance.above())
                            || !currentSpacePassable(stance)
                            || !currentSpacePassable(stance.above())) {
                        continue;
                    }
                    BlockPos support = stance.below();
                    BlockState supportState = baritone.getPlayerContext()
                            .world().getBlockState(support);
                    boolean supported = !supportState.getCollisionShape(
                            baritone.getPlayerContext().world(), support)
                            .isEmpty();
                    if (!supported
                            && (!supportState.canBeReplaced()
                            || !baritone.getInventoryController()
                                    .hasGenericThrowaway())) {
                        continue;
                    }
                    double eyeX = stance.getX() + 0.5D;
                    double eyeY = stance.getY() + 1.62D;
                    double eyeZ = stance.getZ() + 0.5D;
                    double tx = target.getX() + 0.5D - eyeX;
                    double ty = target.getY() + 0.5D - eyeY;
                    double tz = target.getZ() + 0.5D - eyeZ;
                    if (tx * tx + ty * ty + tz * tz
                            > reach * reach) continue;
                    if (!canSeeTargetFrom(stance, target)) continue;
                    result.add(stance.immutable());
                }
            }
        }
        result.sort(Comparator.comparingDouble(feet::distSqr));
        return List.copyOf(result);
    }

    private boolean canSeeTargetFrom(
            BlockPos stance, BlockPos target) {
        Vec3 eye = new Vec3(stance.getX() + 0.5D,
                stance.getY() + 1.62D, stance.getZ() + 0.5D);
        Vec3 center = Vec3.atCenterOf(target);
        Vec3[] samples = {
                center,
                center.add(0.499D, 0D, 0D),
                center.add(-0.499D, 0D, 0D),
                center.add(0D, 0.499D, 0D),
                center.add(0D, -0.499D, 0D),
                center.add(0D, 0D, 0.499D),
                center.add(0D, 0D, -0.499D)
        };
        for (Vec3 sample : samples) {
            HitResult hit = baritone.getPlayerContext().world().clip(
                    new ClipContext(eye, sample,
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            baritone.getPlayerContext().player()));
            if (hit.getType() == HitResult.Type.MISS
                    || hit instanceof BlockHitResult blockHit
                    && blockHit.getBlockPos().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean currentSpacePassable(BlockPos pos) {
        BlockState state = baritone.getPlayerContext().world()
                .getBlockState(pos);
        return state.getCollisionShape(
                baritone.getPlayerContext().world(), pos).isEmpty();
    }

    private boolean finalSpacePassable(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        BlockState current = baritone.getPlayerContext().world()
                .getBlockState(pos);
        if (!schematic.inSchematic(x, y, z, current)) return true;
        BlockState wanted = schematic.desiredState(
                x, y, z, current, approxPlaceable);
        return wanted == null || wanted.getCollisionShape(
                baritone.getPlayerContext().world(), pos).isEmpty();
    }

    /**
     * Upstream Builder paths toward many currently actionable positions at
     * once. Placement goals are primary because arriving at one often unlocks
     * several later placements; break goals remain a fallback.
     */
    private Goal assembleApproachGoal() {
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
            if (pathingSupports.contains(pos)
                    && !cleaningPathingSupports) continue;
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
        pathingSupports.clear();
        cleaningPathingSupports = false;
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
                && printerSatisfied(
                baritone.getPlayerContext().world().getBlockState(pos), wanted);
    }

    private boolean printerSatisfied(
            BlockState current, BlockState wanted) {
        if (printerSkipped(wanted)) return true;
        PrinterBuildMode mode =
                Baritone.settings().printerBuildMode.value;
        if (mode == PrinterBuildMode.EXCAVATE) {
            // Excavation clears only schematic-air cells and deliberately
            // ignores blocks the finished schematic intends to contain.
            return !wanted.isAir() || current.isAir();
        }
        if (sameEnough(current, wanted)) return true;
        if (mode == PrinterBuildMode.REPLACE) {
            // Replace mode is intentionally strict: every differing state in
            // the schematic volume is a transaction, independent of the
            // conservative normal-print switches below.
            return false;
        }
        if (!current.isAir()
                && !Baritone.settings().printerBreakWrongBlocks.value) {
            return true;
        }
        return !current.isAir() && !wanted.isAir()
                && !Baritone.settings().printerReplaceWrongBlocks.value;
    }

    private static boolean printerSkipped(BlockState wanted) {
        var block = wanted.getBlock();
        return block instanceof SignBlock
                || block instanceof VineBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                    && !(block instanceof WallSkullBlock);
    }

    private static boolean sameEnough(BlockState current, BlockState wanted) {
        if (current.equals(wanted)
                || current.isAir() && wanted.isAir()) {
            return true;
        }
        if (!current.getFluidState().isEmpty()
                && !wanted.getFluidState().isEmpty()
                && current.getFluidState().getType()
                        == wanted.getFluidState().getType()) {
            // Source cells are still distinguished below by exact equality;
            // flowing levels are transient and are produced by neighbouring
            // sources rather than by spending one bucket per schematic cell.
            if (!wanted.getFluidState().isSource()) return true;
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
                    || property.getName().equals("waterlogged")
                    && !Baritone.settings()
                    .printerPlaceWaterlogged.value
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
            if (desired != null
                    && desired.getFluidState().isSource()) {
                if (!ensureFluidMaterial(desired.getFluidState())) {
                    pauseForUnavailableAuxiliary();
                    return;
                }
                if (!baritone.getFakeInteractionController()
                        .placeFluid(target, desired.getFluidState())) {
                    deferFailedTarget(target,
                            Baritone.settings()
                                    .printerFailureRetryTicks.value);
                }
                return;
            }
            deferFailedTarget(target, 20);
            return;
        }
        if (desired != null && desired.isAir()
                && Baritone.settings().printerPlaceFluids.value) {
            if (!ensureBucketMaterial(Items.BUCKET)) {
                pauseForUnavailableAuxiliary();
                return;
            }
            if (baritone.getFakeInteractionController()
                    .pickupFluid(target)) return;
        }
        boolean selected;
        if (desired != null && !desired.isAir()) {
            selected = selectPlacementItem(desired);
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
            if (desired != null && !desired.isAir()
                    && requestContainerRefill(desired)) {
                return;
            }
            missingInScan = true;
            deferFailedTarget(target,
                    Baritone.settings().printerFailureRetryTicks.value);
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
        if (desired.is(Blocks.NETHER_PORTAL)) {
            tryPortalIgnition();
            return;
        }
        if (!desired.getFluidState().isEmpty()
                && desired.getBlock() instanceof LiquidBlock) {
            if (!desired.getFluidState().isSource()) {
                deferFailedTarget(target,
                        Baritone.settings().printerFailureRetryTicks.value);
                return;
            }
            if (!ensureFluidMaterial(desired.getFluidState())) {
                pauseForUnavailableAuxiliary();
                return;
            }
            if (!Baritone.settings().printerPlaceFluids.value
                    || !baritone.getFakeInteractionController()
                    .placeFluid(target, desired.getFluidState())) {
                deferFailedTarget(target,
                        Baritone.settings().printerFailureRetryTicks.value);
            }
            return;
        }
        BlockState placement = placementStageState(
                Blocks.AIR.defaultBlockState(), desired);
        if (placement == null
                || placement.getBlock().asItem() == Items.AIR) {
            unavailableMaterialKeys.add("unsupported:"
                    + BuiltInRegistries.BLOCK.getKey(
                            desired.getBlock()));
            missingInScan = true;
            deferFailedTarget(target,
                    Baritone.settings().printerFailureRetryTicks.value);
            return;
        }
        if (!placement.canSurvive(
                baritone.getPlayerContext().world(), target)) {
            // Usually a dependency ordering issue (missing support), not a
            // missing material. Let another target unlock this position.
            deferFailedTarget(target,
                    Baritone.settings().printerFailureRetryTicks.value);
            return;
        }
        if (!selectPlacementItem(placement)) {
            if (canPlace(placement)) {
                // InventoryBehavior may intentionally defer the hotbar swap
                // until stationary or until ticksBetweenInventoryMoves has
                // elapsed. Keep the target instead of misreporting it missing.
                return;
            }
            if (requestContainerRefill(placement)) return;
            missingInScan = true;
            target = null;
            desired = null;
            return;
        }
        if (!ensureAuxiliaryMaterial(placement)) {
            pauseForUnavailableAuxiliary();
            return;
        }
        if (baritone.getFakeInteractionController()
                .placeSelectedBlockMatching(
                        target, placement,
                        preview -> sameEnough(preview, placement))) {
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
        // Keep the target; onTick() owns path submission.
    }

    private boolean canPlace(BlockState wanted) {
        if (wanted.getBlock().asItem() == Items.AIR) return false;
        return baritone.getInventoryController().hasAccessibleItem(
                stack -> stack.is(wanted.getBlock().asItem()));
    }

    private boolean selectPlacementItem(BlockState wanted) {
        return wanted != null && wanted.getBlock().asItem() != Items.AIR
                && baritone.getInventoryController().selectItemForBuilder(
                        stack -> stack.is(wanted.getBlock().asItem()));
    }

    private boolean requestContainerRefill(BlockState wanted) {
        if (wanted == null || wanted.isAir()
                || wanted.getBlock().asItem() == Items.AIR) {
            return false;
        }
        net.minecraft.world.item.Item item =
                wanted.getBlock().asItem();
        return requestContainerRefill(
                "item:" + BuiltInRegistries.ITEM.getKey(item),
                stack -> stack.is(item));
    }

    private boolean requestRequiredMaterial(
            BlockState current, BlockState wanted) {
        if (wanted.is(Blocks.NETHER_PORTAL)
                && !canIgnitePortal()) {
            return requestContainerRefill(
                    "tool:nether_portal", portalIgnitionItem());
        }
        BlockState placement = placementStageState(current, wanted);
        if (placement != null && !canPlace(placement)
                && requestContainerRefill(placement)) {
            return true;
        }
        BlockState transformBase = current.isAir()
                ? placement : current;
        Predicate<ItemStack> tool = transformBase == null ? null
                : specialTransformationTool(transformBase, wanted);
        if (tool != null && !baritone.getInventoryController()
                .hasAccessibleItem(tool)) {
            return requestContainerRefill(
                    "tool:" + BuiltInRegistries.BLOCK
                            .getKey(wanted.getBlock()), tool);
        }
        return !ensureAuxiliaryMaterial(wanted)
                && materialRecovery.isActive();
    }

    private boolean requestContainerRefill(
            String key, Predicate<ItemStack> matcher) {
        if (unavailableMaterialKeys.contains(key)) {
            return false;
        }
        if (!Baritone.settings().printerContainerRefill.value) {
            unavailableMaterialKeys.add(key);
            return false;
        }
        materialRecovery.request(key, matcher);
        return materialRecovery.isActive();
    }

    private boolean ensureAuxiliaryMaterial(BlockState wanted) {
        if (Baritone.settings().printerPlaceWaterlogged.value
                && wanted.hasProperty(BlockStateProperties.WATERLOGGED)
                && wanted.getValue(BlockStateProperties.WATERLOGGED)
                && !baritone.getInventoryController()
                        .hasAccessibleItem(
                                stack -> stack.is(Items.WATER_BUCKET))) {
            requestContainerRefill("aux:water_bucket",
                    stack -> stack.is(Items.WATER_BUCKET));
            return false;
        }
        if (wanted.getBlock() instanceof EndPortalFrameBlock
                && wanted.getValue(EndPortalFrameBlock.HAS_EYE)
                && !baritone.getInventoryController()
                        .hasAccessibleItem(
                                stack -> stack.is(Items.ENDER_EYE))) {
            requestContainerRefill("aux:ender_eye",
                    stack -> stack.is(Items.ENDER_EYE));
            return false;
        }
        return true;
    }

    private boolean ensureFluidMaterial(
            net.minecraft.world.level.material.FluidState fluid) {
        if (fluid.is(net.minecraft.world.level.material.Fluids.WATER)) {
            return ensureBucketMaterial(Items.WATER_BUCKET);
        }
        if (fluid.is(net.minecraft.world.level.material.Fluids.LAVA)) {
            return ensureBucketMaterial(Items.LAVA_BUCKET);
        }
        return false;
    }

    private boolean ensureBucketMaterial(net.minecraft.world.item.Item item) {
        Predicate<ItemStack> matcher = stack -> stack.is(item);
        if (baritone.getInventoryController()
                .hasAccessibleItem(matcher)) return true;
        requestContainerRefill(
                "aux:" + BuiltInRegistries.ITEM.getKey(item), matcher);
        return false;
    }

    private void pauseForUnavailableAuxiliary() {
        if (materialRecovery.isActive()) return;
        missingInScan = true;
        if (target != null) {
            deferFailedTarget(target,
                    Baritone.settings().printerFailureRetryTicks.value);
        }
    }

    private boolean canSatisfy(BlockState current, BlockState wanted) {
        if (current.isAir() && wanted.is(Blocks.NETHER_PORTAL)) {
            return canIgnitePortal();
        }
        if (canPlace(wanted) || canTransform(current, wanted)) return true;
        BlockState base = placementBase(wanted);
        return current.isAir() && base != null && canPlace(base)
                && canTransform(base, wanted);
    }

    private static BlockState placementStageState(
            BlockState current, BlockState wanted) {
        if (!current.isAir()) return wanted;
        if (wanted.getBlock().asItem() != Items.AIR) return wanted;
        return placementBase(wanted);
    }

    private static BlockState placementBase(BlockState wanted) {
        if (wanted.is(Blocks.FARMLAND)
                || wanted.is(Blocks.DIRT_PATH)) {
            return Blocks.DIRT.defaultBlockState();
        }
        String wantedId = BuiltInRegistries.BLOCK.getKey(
                wanted.getBlock()).getPath();
        if (wantedId.startsWith("potted_")) {
            return Blocks.FLOWER_POT.defaultBlockState();
        }
        return null;
    }

    private boolean canTransform(BlockState current, BlockState wanted) {
        Predicate<ItemStack> tool = specialTransformationTool(
                current, wanted);
        return tool != null && baritone.getInventoryController()
                .hasAccessibleItem(tool);
    }

    private Predicate<ItemStack> portalIgnitionItem() {
        return stack -> stack.is(Items.FLINT_AND_STEEL)
                || stack.is(Items.FIRE_CHARGE);
    }

    private boolean canIgnitePortal() {
        return baritone.getInventoryController()
                .hasAccessibleItem(portalIgnitionItem());
    }

    private boolean tryPortalIgnition() {
        if (desired == null || !desired.is(Blocks.NETHER_PORTAL)) {
            return false;
        }
        Predicate<ItemStack> ignition = portalIgnitionItem();
        if (!baritone.getInventoryController()
                .selectItemForBuilder(ignition)) {
            if (!baritone.getInventoryController()
                    .hasAccessibleItem(ignition)) {
                requestContainerRefill(
                        "tool:nether_portal", ignition);
            }
            return true;
        }
        BlockPos portalAt = target;
        if (!baritone.getFakeInteractionController()
                .printerActionReady(portalAt)) return true;
        boolean interacted = baritone.getFakeInteractionController()
                .ignitePortalAt(portalAt);
        if (interacted) {
            baritone.getFakeInteractionController()
                    .recordPrinterAction(portalAt);
        }
        if (baritone.getPlayerContext().world()
                .getBlockState(portalAt).is(Blocks.NETHER_PORTAL)) {
            incorrectPositions.remove(portalAt);
            observedCompleted.add(portalAt.immutable());
            target = null;
            desired = null;
        } else if (interacted) {
            deferFailedTarget(portalAt,
                    Baritone.settings().printerFailureRetryTicks.value);
        } else {
            deferFailedTarget(portalAt);
        }
        return true;
    }

    /**
     * Server form of PlacementGuide.Action#clickItems for states produced by
     * interacting with an existing block. Returning true means the target was
     * handled (or is waiting for the normal inventory move cooldown), so the
     * replacement pass must not destroy its usable base block.
     */
    private boolean trySpecialTransformation(BlockState current) {
        if (desired == null) return false;
        Predicate<ItemStack> tool = specialTransformationTool(
                current, desired);
        if (tool == null) return false;
        if (!baritone.getInventoryController()
                .selectItemForBuilder(tool)) {
            if (baritone.getInventoryController()
                    .hasAccessibleItem(tool)) return true;
            requestContainerRefill(
                    "tool:" + BuiltInRegistries.BLOCK
                            .getKey(desired.getBlock()), tool);
            return true;
        }
        BlockPos transformedAt = target;
        if (!baritone.getFakeInteractionController()
                .printerActionReady(transformedAt)) {
            return true;
        }
        boolean interacted = baritone.getFakeInteractionController()
                .useSelectedOnBlock(transformedAt);
        BlockState after = baritone.getPlayerContext().world()
                .getBlockState(transformedAt);
        if (interacted) {
            baritone.getFakeInteractionController()
                    .recordPrinterAction(transformedAt);
        }
        if (sameEnough(after, desired)) {
            incorrectPositions.remove(transformedAt);
            observedCompleted.add(transformedAt.immutable());
            target = null;
            desired = null;
        } else if (interacted
                && specialTransformationTool(after, desired) != null) {
            // A stacked state (snow, candles, pickles, petals...) advances
            // one vanilla click at a time. Keep it at the head of the queue.
            return true;
        } else {
            deferFailedTarget(transformedAt,
                    Baritone.settings().printerFailureRetryTicks.value);
        }
        return true;
    }

    private static Predicate<ItemStack> specialTransformationTool(
            BlockState current, BlockState wanted) {
        if (wanted.is(Blocks.FARMLAND)
                && (current.is(Blocks.DIRT)
                    || current.is(Blocks.GRASS_BLOCK)
                    || current.is(Blocks.DIRT_PATH)
                    || current.is(Blocks.COARSE_DIRT)
                    || current.is(Blocks.ROOTED_DIRT))) {
            return stack -> stack.is(ItemTags.HOES);
        }
        if (wanted.is(Blocks.DIRT_PATH)
                && (current.is(Blocks.DIRT)
                    || current.is(Blocks.GRASS_BLOCK)
                    || current.is(Blocks.COARSE_DIRT)
                    || current.is(Blocks.PODZOL)
                    || current.is(Blocks.MYCELIUM)
                    || current.is(Blocks.ROOTED_DIRT))) {
            return stack -> stack.is(ItemTags.SHOVELS);
        }
        String currentId = BuiltInRegistries.BLOCK.getKey(
                current.getBlock()).getPath();
        String wantedId = BuiltInRegistries.BLOCK.getKey(
                wanted.getBlock()).getPath();
        if (current.is(Blocks.FLOWER_POT)
                && wantedId.startsWith("potted_")) {
            String contentId = wantedId.substring("potted_".length());
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && BuiltInRegistries.BLOCK.getKey(
                            blockItem.getBlock()).getPath()
                            .equals(contentId);
        }
        if (current.getBlock() == wanted.getBlock()
                && needsAdditionalPlacementClick(current, wanted)) {
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == wanted.getBlock();
        }
        if (wantedId.startsWith("stripped_")
                && wantedId.substring("stripped_".length())
                        .equals(currentId)
                || currentId.startsWith("waxed_")
                    && currentId.substring("waxed_".length())
                        .equals(wantedId)) {
            return stack -> stack.is(ItemTags.AXES);
        }
        if (current.getBlock() == wanted.getBlock()
                && wanted.hasProperty(BlockStateProperties.LIT)
                && wanted.getValue(BlockStateProperties.LIT)) {
            return stack -> stack.is(Items.FLINT_AND_STEEL)
                    || stack.is(Items.FIRE_CHARGE);
        }
        return null;
    }

    private static boolean needsAdditionalPlacementClick(
            BlockState current, BlockState wanted) {
        for (String name : List.of(
                "candles", "pickles", "eggs", "layers",
                "flower_amount", "segment_amount")) {
            Optional<Property<?>> currentProperty = current.getProperties()
                    .stream().filter(property ->
                            property.getName().equals(name)).findFirst();
            Optional<Property<?>> wantedProperty = wanted.getProperties()
                    .stream().filter(property ->
                            property.getName().equals(name)).findFirst();
            if (currentProperty.isEmpty()
                    || wantedProperty.isEmpty()) continue;
            int have = Integer.parseInt(String.valueOf(
                    current.getValue(currentProperty.get())));
            int need = Integer.parseInt(String.valueOf(
                    wanted.getValue(wantedProperty.get())));
            if (need > have) return true;
        }
        return false;
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
                .distanceToSqr(Vec3.atCenterOf(pos))
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
        contents.nonEmptyItemCopyStream().forEach(
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
        String requirements = unavailableMaterialKeys.stream()
                .limit(12)
                .map(BuilderProcess::displayMaterialRequirement)
                .collect(Collectors.joining("，"));
        if (!requirements.isEmpty()) {
            details = details.isEmpty() ? requirements
                    : details + "；" + requirements;
        }
        feedback.accept(details.isEmpty()
                ? "当前层没有可执行的建造目标，Builder 已暂停"
                : "缺少建材，Builder 已暂停：" + details);
        missingReported = true;
    }

    private static String displayMaterialRequirement(String key) {
        if (key.startsWith("item:")) {
            return key.substring("item:".length());
        }
        if (key.startsWith("tool:")) {
            return "工具(" + key.substring("tool:".length()) + ")";
        }
        if (key.startsWith("aux:")) {
            return "辅助物品(" + key.substring("aux:".length()) + ")";
        }
        if (key.startsWith("unsupported:")) {
            return "暂不支持(" + key.substring(
                    "unsupported:".length()) + ")";
        }
        return key;
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
        missingInScan = false;
        unavailableMaterialKeys.clear();
        materialRecovery.clear();
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
        if (calcFailed) {
            if (materialRecovery.isActive()) {
                materialRecovery.pathFailed();
            } else if (target != null) {
                deferFailedTarget(target,
                        Baritone.settings().printerFailureRetryTicks.value);
            }
        }
        if (paused) {
            return new PathingCommand(
                    null, PathingCommandType.REQUEST_PAUSE);
        }
        Goal goal = materialRecovery.isActive()
                ? materialRecovery.goal()
                : publishedApproachGoal();
        if (goal == null) {
            return new PathingCommand(
                    null,
                    PathingCommandType.DEFER);
        }
        return new PathingCommandContext(
                goal,
                PathingCommandType.REVALIDATE_GOAL_AND_PATH,
                calculationContext(goal));
    }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        schematic = null; target = null; desired = null; name = null;
        publishedGoalTarget = null;
        publishedApproachGoal = null;
        publishedTargetChunkLoaded = false;
        origin = null;
        paused = false;
        layer = Math.max(0, Baritone.settings().startAtLayer.value);
        scanCursor = 0;
        missingInScan = false;
        completedBuilds = 0;
        failedUntil.clear();
        incorrectPositions.clear();
        observedCompleted.clear();
        pathingSupports.clear();
        cleaningPathingSupports = false;
        unavailableMaterialKeys.clear();
        approxPlaceable = Collections.emptyList();
        missingReported = false;
        materialRecovery.clear();
        feedback = ignored -> { };
    }
    @Override public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    public static class GoalBreak extends GoalGetToBlock {
        public GoalBreak(BlockPos pos) { super(pos); }
        @Override public boolean isInGoal(int x, int y, int z) {
            return !(x == this.x && y == this.y && z == this.z)
                    && y <= this.y && super.isInGoal(x, y, z);
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
            return Math.min(primary.heuristic(x, y, z),
                    fallback.heuristic(x, y, z));
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

    /**
     * Exact, prevalidated player-feet positions from which the Builder may
     * interact with one target. Unlike GoalGetToBlock it never treats the
     * target block itself or an arbitrary future building cell as a goal.
     */
    public static final class GoalBuilderStance
            implements Goal, IGoalRenderPos {
        private final BlockPos target;
        private final Set<BlockPos> stances;

        public GoalBuilderStance(
                BlockPos target, List<BlockPos> stances) {
            this.target = target.immutable();
            this.stances = Set.copyOf(stances);
            if (this.stances.isEmpty()) {
                throw new IllegalArgumentException(
                        "Builder stance goal requires a stance");
            }
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return stances.contains(new BlockPos(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double best = Double.POSITIVE_INFINITY;
            for (BlockPos stance : stances) {
                best = Math.min(best, GoalBlock.calculate(
                        x - stance.getX(), y - stance.getY(),
                        z - stance.getZ()));
            }
            return best;
        }

        @Override
        public BlockPos getGoalPos() {
            return target;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof GoalBuilderStance that)) return false;
            return target.equals(that.target)
                    && stances.equals(that.stances);
        }

        @Override
        public int hashCode() {
            return Objects.hash(target, stances);
        }

        @Override
        public String toString() {
            return "GoalBuilderStance{target=" + target
                    + ", stances=" + stances.size() + "}";
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
