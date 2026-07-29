/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.cache.IWorldScanner;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.server.CarpetInputController;
import baritone.server.ServerInventoryController;
import baritone.server.ServerLookBehavior;
import baritone.server.ServerPathExecutor;
import baritone.server.BlockInteractionTask;
import baritone.server.TrashDiscardController;
import baritone.server.ServerPathingScheduler;
import baritone.server.ServerFakeInteractionController;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.calc.HybridPathFinder;
import baritone.api.pathing.calc.IPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.process.FollowProcess;
import baritone.process.CustomGoalProcess;
import baritone.process.ExploreProcess;
import baritone.process.InventoryPauserProcess;
import baritone.process.GetToBlockProcess;
import baritone.process.MineProcess;
import baritone.process.BackfillProcess;
import baritone.process.FarmProcess;
import baritone.process.BuilderProcess;
import baritone.process.ElytraProcess;
import baritone.process.CollectItemProcess;
import baritone.process.GiveAllProcess;
import baritone.process.CleanProcess;
import baritone.process.PauseProcess;
import baritone.utils.pathing.Favoring;
import baritone.cache.ServerWorldCache;
import baritone.cache.WorldScanner;
import baritone.cache.WorldProvider;
import baritone.utils.PathingControlManager;
import baritone.event.GameEventHandler;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.selection.SelectionManager;
import baritone.command.manager.CommandManager;
import baritone.behavior.InventoryBehavior;
import baritone.behavior.WaypointBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import me.nuoyuan.carpetbaritoneintegration.network.ServerPathSync;

/** One server-side Baritone instance bound to one fake player context. */
public final class Baritone implements IBaritone {

    private final IPlayerContext playerContext;
    private final CarpetInputController inputController;
    private final ServerLookBehavior lookBehavior;
    private final ServerInventoryController inventoryController;
    private final ServerFakeInteractionController fakeInteractionController;
    private final PathingBehavior pathingBehavior;
    private final TrashDiscardController trashDiscardController;
    private final FollowProcess followProcess;
    private final CustomGoalProcess customGoalProcess;
    private final ExploreProcess exploreProcess;
    private final InventoryPauserProcess inventoryPauserProcess;
    private final GetToBlockProcess getToBlockProcess;
    private final MineProcess mineProcess;
    private final BackfillProcess backfillProcess;
    private final FarmProcess farmProcess;
    private final BuilderProcess builderProcess;
    private final ElytraProcess elytraProcess;
    private final CollectItemProcess collectItemProcess;
    private final GiveAllProcess giveAllProcess;
    private final CleanProcess cleanProcess;
    private final PauseProcess pauseProcess;
    private final WorldProvider worldProvider;
    private final PathingControlManager pathingControlManager;
    private final GameEventHandler gameEventHandler;
    private final SelectionManager selectionManager;
    private final CommandManager commandManager;
    private final InventoryBehavior inventoryBehavior;
    private final WaypointBehavior waypointBehavior;
    private ServerPathExecutor pathExecutor;
    private ServerPathExecutor nextPathExecutor;
    private BlockInteractionTask blockTask;
    private Goal activeGoal;
    private int consecutivePathFailures;
    private boolean pathRecalcPending;
    private int cacheScanCursor;
    private int cacheMaintenanceTicks;
    private boolean calcFailedLastTick;
    private HybridPathFinder inProgressPathfinder;
    private boolean inProgressNextSegment;
    private Future<?> calculationFuture;
    private long calculationGeneration;
    private long nextRecalculationTick;
    private Goal deferredProcessRecalculation;
    private CalculationContext deferredProcessCalculationContext;
    private BetterBlockPos selectionPos1;
    private BetterBlockPos selectionPos2;
    private final ConcurrentLinkedQueue<PathCompletion> pathCompletions =
            new ConcurrentLinkedQueue<>();

    private record PathCompletion(
            long generation,
            Goal goal,
            BetterBlockPos start,
            boolean nextSegment,
            Map<Long, Long> snapshotRevisions,
            CalculationContext calculationContext,
            PathCalculationResult result) {}

    public Baritone(IPlayerContext playerContext) {
        this.playerContext = Objects.requireNonNull(playerContext, "playerContext");
        this.inputController = new CarpetInputController(playerContext.player());
        this.lookBehavior = new ServerLookBehavior(inputController);
        this.inventoryController = new ServerInventoryController(playerContext.player());
        this.inventoryController.bind(this);
        this.fakeInteractionController =
                new ServerFakeInteractionController(this);
        this.pathingBehavior = new PathingBehavior(this);
        this.trashDiscardController = new TrashDiscardController(playerContext.player());
        this.followProcess = new FollowProcess(this);
        this.customGoalProcess = new CustomGoalProcess(this);
        this.exploreProcess = new ExploreProcess(this);
        this.inventoryPauserProcess = new InventoryPauserProcess(this);
        this.getToBlockProcess = new GetToBlockProcess(this);
        this.mineProcess = new MineProcess(this);
        this.backfillProcess = new BackfillProcess(this);
        this.farmProcess = new FarmProcess(this);
        this.builderProcess = new BuilderProcess(this);
        this.elytraProcess = new ElytraProcess(this);
        this.collectItemProcess = new CollectItemProcess(this);
        this.giveAllProcess = new GiveAllProcess(this);
        this.cleanProcess = new CleanProcess(this);
        this.pauseProcess = new PauseProcess(this);
        this.worldProvider = new WorldProvider(playerContext);
        this.gameEventHandler = new GameEventHandler(this);
        this.selectionManager = new SelectionManager(this);
        this.commandManager = new CommandManager(this);
        this.inventoryBehavior = new InventoryBehavior(this);
        this.waypointBehavior = new WaypointBehavior(this);
        this.pathingControlManager = new PathingControlManager(this);
        this.gameEventHandler.registerEventListener(waypointBehavior);
        pathingControlManager.registerProcess(followProcess);
        pathingControlManager.registerProcess(customGoalProcess);
        pathingControlManager.registerProcess(exploreProcess);
        pathingControlManager.registerProcess(inventoryPauserProcess);
        pathingControlManager.registerProcess(getToBlockProcess);
        pathingControlManager.registerProcess(mineProcess);
        pathingControlManager.registerProcess(backfillProcess);
        pathingControlManager.registerProcess(farmProcess);
        pathingControlManager.registerProcess(builderProcess);
        pathingControlManager.registerProcess(elytraProcess);
        pathingControlManager.registerProcess(collectItemProcess);
        pathingControlManager.registerProcess(giveAllProcess);
        pathingControlManager.registerProcess(cleanProcess);
        pathingControlManager.registerProcess(pauseProcess);
    }

    @Override
    public IPlayerContext getPlayerContext() {
        return playerContext;
    }

    @Override
    public CommandManager getCommandManager() {
        return commandManager;
    }

    public InventoryBehavior getInventoryBehavior() {
        return inventoryBehavior;
    }

    public ServerFakeInteractionController getFakeInteractionController() {
        return fakeInteractionController;
    }

    public Goal getActiveGoal() {
        return activeGoal;
    }

    @Override
    public IInputOverrideHandler getInputOverrideHandler() {
        return inputController;
    }

    @Override
    public ILookBehavior getLookBehavior() {
        return lookBehavior;
    }

    @Override
    public ServerInventoryController getInventoryController() {
        return inventoryController;
    }

    @Override
    public IWorldScanner getWorldScanner() {
        return WorldScanner.INSTANCE;
    }

    @Override
    public ServerWorldCache getWorldCache() {
        return ServerWorldCache.get(playerContext.world());
    }

    @Override
    public WorldProvider getWorldProvider() {
        return worldProvider;
    }

    @Override
    public PathingControlManager getPathingControlManager() {
        return pathingControlManager;
    }

    @Override
    public GameEventHandler getGameEventHandler() {
        return gameEventHandler;
    }

    @Override public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    public CarpetInputController getInputController() {
        return inputController;
    }

    public void followPath(IPath path) {
        followPath(path, path.getGoal());
    }

    public void followPath(IPath path, Goal goal) {
        cancelPath();
        this.activeGoal = goal;
        this.pathRecalcPending = false;
        this.pathExecutor = new ServerPathExecutor(this, pathingBehavior, path);
    }

    public void tick() {
        tick((int) playerContext.world().getGameTime());
    }

    public void tick(int tickCount) {
        TickEvent pre = new TickEvent(EventState.PRE, TickEvent.Type.IN, tickCount);
        gameEventHandler.onTick(pre);
        gameEventHandler.onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
        drainPathCompletions();
        BlockPos protectedDropOrigin = mineProcess.isActive()
                ? mineProcess.protectedDropOrigin()
                : blockTask == null ? null : blockTask.protectedDropOrigin();
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> protectedDrop =
                mineProcess.isActive()
                        ? mineProcess::isDesiredMiningDrop
                        : blockTask == null ? stack -> false : blockTask::isDesiredMiningDrop;
        pathingControlManager.tick(
                calcFailedLastTick,
                pathExecutor == null || pathExecutor.isSafeToCancel());
        calcFailedLastTick = false;
        boolean suppressTrashDiscard = cleanProcess.isActive()
                || builderProcess.isActive()
                || followProcess.suppressesTrashDiscard()
                || customGoalProcess.suppressesTrashDiscard();
        if (pathExecutor != null && !suppressTrashDiscard) {
            trashDiscardController.observe(
                    pathExecutor.toBreak(), protectedDropOrigin, protectedDrop);
            backfillProcess.observe(pathExecutor.toBreak(), protectedDropOrigin);
        }
        if (pathExecutor == null && pathRecalcPending
                && playerContext.world().getGameTime() >= nextRecalculationTick) {
            pathRecalcPending = false;
            revalidateAndRecalculate();
        }
        if (pathExecutor != null) {
            pathExecutor.tick();
            if (pathExecutor.isFinished()) {
                boolean failed = pathExecutor.failed();
                pathExecutor = null;
                if (activeGoal != null && activeGoal.isInGoal(playerContext.playerFeet())) {
                    activeGoal = null;
                    consecutivePathFailures = 0;
                } else if (activeGoal != null) {
                    if (nextPathExecutor != null
                            && pathContainsCurrentPosition(nextPathExecutor)) {
                        gameEventHandler.onPathEvent(
                                PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
                        pathExecutor = nextPathExecutor;
                        nextPathExecutor = null;
                        consecutivePathFailures = 0;
                    } else {
                        if (nextPathExecutor != null) {
                            gameEventHandler.onPathEvent(PathEvent.DISCARD_NEXT);
                            nextPathExecutor = null;
                        }
                        if (inProgressPathfinder != null
                                && inProgressNextSegment) {
                            gameEventHandler.onPathEvent(PathEvent
                                    .PATH_FINISHED_NEXT_STILL_CALCULATING);
                        } else if (failed) {
                            consecutivePathFailures++;
                            calcFailedLastTick =
                                    scheduleRecalculationBackoff();
                        } else {
                            consecutivePathFailures = 0;
                            revalidateAndRecalculate();
                        }
                    }
                }
            }
        }
        Goal deferred = deferredProcessRecalculation;
        CalculationContext deferredContext =
                deferredProcessCalculationContext;
        deferredProcessRecalculation = null;
        deferredProcessCalculationContext = null;
        if (deferred != null) {
            recalculateForProcess(deferred, deferredContext);
        }
        planAheadAndSplice();
        if (blockTask != null) {
            blockTask.tick();
            if (blockTask.isFinished()) {
                blockTask = null;
            }
        }
        pathingControlManager.mostRecentInControl().ifPresent(controlled -> {
            if (controlled == followProcess) followProcess.serverTick();
            else if (controlled == customGoalProcess) customGoalProcess.serverTick();
            else if (controlled == exploreProcess) exploreProcess.serverTick();
            else if (controlled == getToBlockProcess) getToBlockProcess.serverTick();
            else if (controlled == backfillProcess) backfillProcess.serverTick();
            else if (controlled == farmProcess) farmProcess.serverTick();
            else if (controlled == builderProcess) builderProcess.serverTick();
            else if (controlled == elytraProcess) elytraProcess.serverTick();
            else if (controlled == collectItemProcess) collectItemProcess.serverTick();
            else if (controlled == giveAllProcess) giveAllProcess.serverTick();
            else if (controlled == cleanProcess) cleanProcess.serverTick();
        });
        if (suppressTrashDiscard) {
            trashDiscardController.clear();
        } else {
            trashDiscardController.tick(
                pathExecutor != null || activeGoal != null
                        || mineProcess.isActive() || farmProcess.isActive()
                        || collectItemProcess.isActive()
                        || giveAllProcess.isActive(),
                mineProcess.isActive() ? mineProcess.protectedDropOrigin()
                        : blockTask == null ? null : blockTask.protectedDropOrigin(),
                mineProcess.isActive() ? mineProcess::isDesiredMiningDrop
                        : farmProcess.isActive() ? farmProcess::isDesiredFarmDrop
                        : collectItemProcess.isActive()
                        ? collectItemProcess::isProtectedStack
                        : giveAllProcess.isActive()
                        ? giveAllProcess::isProtectedStack
                        : blockTask == null ? stack -> false : blockTask::isDesiredMiningDrop);
        }
        gameEventHandler.onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
        fakeInteractionController.serverTick();
        TickEvent post = new TickEvent(EventState.POST, TickEvent.Type.IN, tickCount);
        gameEventHandler.onPostTick(post);
        ServerPathSync.tick(this);
    }

    public void cancelPath() {
        boolean hadPath = pathExecutor != null || nextPathExecutor != null
                || activeGoal != null || inProgressPathfinder != null;
        cancelCalculation();
        if (pathExecutor != null) {
            pathExecutor.cancel();
            pathExecutor = null;
        }
        if (nextPathExecutor != null) {
            nextPathExecutor.cancel();
            nextPathExecutor = null;
        }
        activeGoal = null;
        consecutivePathFailures = 0;
        pathRecalcPending = false;
        deferredProcessRecalculation = null;
        deferredProcessCalculationContext = null;
        if (hadPath) gameEventHandler.onPathEvent(PathEvent.CANCELED);
    }

    /**
     * Called through the registry's round-robin world-cache budget. It is
     * deliberately not part of every Baritone tick: ten fake players must not
     * repack ten chunks from the same dimension in one server tick.
     */
    public void tickCacheMaintenance() {
        if (!settings().chunkCaching.value) return;
        cacheScanCursor = getWorldCache().captureIncrementally(
                playerContext.playerFeet(),
                playerContext.server().getPlayerList().getViewDistance(),
                cacheScanCursor, 1);
        if (++cacheMaintenanceTicks % 1200 == 0
                && settings().pruneRegionsFromRAM.value) {
            getWorldCache().pruneFarFrom(playerContext.playerFeet(), 64);
        }
        if (cacheMaintenanceTicks % 6000 == 0) {
            getWorldCache().saveAsync();
        }
    }

    public void pausePath() {
        cancelCalculation();
        if (pathExecutor != null && pathExecutor.isSafeToCancel()) {
            pathExecutor.cancel();
            pathExecutor = null;
        }
    }

    public void setActiveGoal(Goal goal) {
        activeGoal = goal;
    }

    public boolean isPathing() {
        return pathExecutor != null || inProgressPathfinder != null;
    }

    public int getConsecutivePathFailures() {
        return consecutivePathFailures;
    }

    public boolean goalMatches(Goal goal) {
        return Objects.equals(activeGoal, goal);
    }

    public boolean shouldRevalidate(Goal goal, boolean force) {
        if (pathRecalcPending && Objects.equals(activeGoal, goal)
                && playerContext.world().getGameTime()
                < nextRecalculationTick) {
            return false;
        }
        if (inProgressPathfinder != null) {
            // Matches upstream PathingControlManager: do not continuously
            // cancel an in-flight search for a moving/recreated goal. Once a
            // segment exists its destination is revalidated against the new
            // goal and replaced if actually invalid.
            return false;
        }
        if (pathExecutor == null) return true;
        Goal previous = pathExecutor.getPath().getGoal();
        BlockPos destination = pathExecutor.getPath().getDest();
        if (force && !Objects.equals(previous, goal)
                && !goal.isInGoal(destination)) return true;
        return previous != null && previous.isInGoal(destination)
                && !goal.isInGoal(destination);
    }

    public void recalculateForProcess(Goal goal) {
        recalculateForProcess(goal, null);
    }

    public void recalculateForProcess(
            Goal goal, CalculationContext desiredContext) {
        if (pathRecalcPending && Objects.equals(activeGoal, goal)
                && playerContext.world().getGameTime()
                < nextRecalculationTick) return;
        if (inProgressPathfinder != null
                && Objects.equals(activeGoal, goal)) return;
        activeGoal = goal;
        if (pathExecutor != null && !pathExecutor.isSafeToCancel()) return;
        pausePath();
        revalidateAndRecalculate(desiredContext);
    }

    public void deferRecalculationForProcess(Goal goal) {
        deferRecalculationForProcess(goal, null);
    }

    public void deferRecalculationForProcess(
            Goal goal, CalculationContext desiredContext) {
        deferredProcessRecalculation = goal;
        deferredProcessCalculationContext = desiredContext;
    }

    private void revalidateAndRecalculate() {
        revalidateAndRecalculate(null);
    }

    private void revalidateAndRecalculate(
            CalculationContext desiredContext) {
        Goal goal = activeGoal;
        if (goal == null || goal.isInGoal(playerContext.playerFeet())) {
            activeGoal = null;
            consecutivePathFailures = 0;
            return;
        }
        BetterBlockPos start = pathingBehavior.pathStart();
        long multiplier = 1L << Math.min(2, consecutivePathFailures);
        boolean collecting = usesCollectItemCostModel();
        long primaryTimeout = (mineProcess.isActive()
                ? settings().minePrimaryTimeoutMS.value
                : collecting
                ? settings().collectItemPrimaryTimeoutMS.value
                : settings().primaryTimeoutMS.value) * multiplier;
        long failureTimeout = (mineProcess.isActive()
                ? settings().mineFailureTimeoutMS.value
                : collecting
                ? settings().collectItemFailureTimeoutMS.value
                : settings().failureTimeoutMS.value) * multiplier;
        submitPathCalculation(start, goal, null, false,
                primaryTimeout, failureTimeout, desiredContext);
    }

    public void startBlockTask(BlockInteractionTask task) {
        cancelAll();
        this.blockTask = Objects.requireNonNull(task, "task");
    }

    public void cancelAll() {
        cancelPath();
        pathingControlManager.cancelEverything();
        if (blockTask != null) {
            blockTask.cancel();
            blockTask = null;
        }
        trashDiscardController.clear();
    }

    public BlockInteractionTask getBlockTask() {
        return blockTask;
    }

    public void cancelLegacyBlockTask() {
        if (blockTask != null) {
            blockTask.cancel();
            blockTask = null;
        }
    }

    public ServerPathExecutor getPathExecutor() {
        return pathExecutor;
    }

    public ServerPathExecutor getNextPathExecutor() {
        return nextPathExecutor;
    }

    public PathingBehavior getPathingBehavior() {
        return pathingBehavior;
    }

    public FollowProcess getFollowProcess() {
        return followProcess;
    }

    public CustomGoalProcess getCustomGoalProcess() {
        return customGoalProcess;
    }

    public ExploreProcess getExploreProcess() {
        return exploreProcess;
    }

    public InventoryPauserProcess getInventoryPauserProcess() {
        return inventoryPauserProcess;
    }

    public GetToBlockProcess getGetToBlockProcess() {
        return getToBlockProcess;
    }

    public MineProcess getMineProcess() {
        return mineProcess;
    }

    public boolean usesMiningCostModel() {
        return mineProcess != null && mineProcess.isActive();
    }

    public boolean usesCollectItemCostModel() {
        return collectItemProcess != null && collectItemProcess.isActive()
                || giveAllProcess != null && giveAllProcess.isActive();
    }

    public boolean usesCleanCostModel() {
        return cleanProcess != null && cleanProcess.isActive();
    }

    /** True only for work that can still advance while the server is empty. */
    public boolean hasActiveTask() {
        return pathExecutor != null
                || blockTask != null
                || followProcess.isActive()
                || customGoalProcess.isActive()
                || exploreProcess.isActive()
                || getToBlockProcess.isActive()
                || mineProcess.isActive()
                || backfillProcess.isActive()
                || farmProcess.isActive()
                || builderProcess.isActive()
                    && !builderProcess.isPaused()
                || elytraProcess.isActive()
                || collectItemProcess.isActive()
                || giveAllProcess.isActive()
                || cleanProcess.isActive();
    }

    public BackfillProcess getBackfillProcess() {
        return backfillProcess;
    }

    public FarmProcess getFarmProcess() {
        return farmProcess;
    }

    public BuilderProcess getBuilderProcess() {
        return builderProcess;
    }

    public ElytraProcess getElytraProcess() {
        return elytraProcess;
    }

    public CollectItemProcess getCollectItemProcess() {
        return collectItemProcess;
    }

    public GiveAllProcess getGiveAllProcess() {
        return giveAllProcess;
    }

    public CleanProcess getCleanProcess() {
        return cleanProcess;
    }

    public PauseProcess getPauseProcess() {
        return pauseProcess;
    }

    public void setSelectionPos1(BlockPos pos) {
        selectionPos1 = BetterBlockPos.from(pos);
        rebuildCommandSelection();
    }

    public void setSelectionPos2(BlockPos pos) {
        selectionPos2 = BetterBlockPos.from(pos);
        rebuildCommandSelection();
    }

    public BetterBlockPos getSelectionPos1() {
        return selectionPos1;
    }

    public BetterBlockPos getSelectionPos2() {
        return selectionPos2;
    }

    private void rebuildCommandSelection() {
        if (selectionPos1 == null || selectionPos2 == null) return;
        selectionManager.removeAllSelections();
        selectionManager.addSelection(selectionPos1, selectionPos2);
    }

    public void startCleaning() {
        baritone.api.selection.ISelection selection =
                selectionManager.getOnlySelection();
        if (selection == null) {
            throw new IllegalStateException(
                    "请先使用 pos1 和 pos2 设置选区");
        }
        cancelAll();
        cleanProcess.clean(selection, ignored -> { });
    }

    public void startElytra(BlockPos destination) {
        cancelAll();
        elytraProcess.pathTo(destination);
    }

    public void startBuilding(
            String name,
            baritone.api.schematic.ISchematic schematic,
            net.minecraft.core.Vec3i origin
    ) {
        cancelAll();
        builderProcess.build(name, schematic, origin);
    }

    public void startFarming(int range, BlockPos center) {
        cancelAll();
        farmProcess.farm(range, center);
    }

    public void startGetToBlock(net.minecraft.world.level.block.Block block) {
        cancelAll();
        getToBlockProcess.getToBlock(block);
    }

    public void startExploring(int centerX, int centerZ) {
        cancelAll();
        exploreProcess.explore(centerX, centerZ);
    }

    public boolean pathToGoal(Goal goal, long primaryTimeout, long failureTimeout) {
        return pathToGoal(
                goal, primaryTimeout, failureTimeout, null);
    }

    public boolean pathToGoal(
            Goal goal, long primaryTimeout, long failureTimeout,
            CalculationContext desiredContext) {
        if (goal == null) return false;
        if (pathRecalcPending && Objects.equals(activeGoal, goal)
                && playerContext.world().getGameTime()
                < nextRecalculationTick) return true;
        if (inProgressPathfinder != null
                && Objects.equals(activeGoal, goal)) return true;
        activeGoal = goal;
        if (goal.isInGoal(playerContext.playerFeet())) return true;
        if (pathExecutor != null && !pathExecutor.isSafeToCancel()) return true;
        cancelCalculation();
        if (pathExecutor != null) {
            pathExecutor.cancel();
            pathExecutor = null;
        }
        nextPathExecutor = null;
        BetterBlockPos start = pathingBehavior.pathStart();
        return submitPathCalculation(start, goal, null, false,
                primaryTimeout, failureTimeout, desiredContext);
    }

    private boolean submitPathCalculation(
            BetterBlockPos start,
            Goal goal,
            IPath previous,
            boolean nextSegment,
            long primaryTimeout,
            long failureTimeout) {
        return submitPathCalculation(
                start, goal, previous, nextSegment,
                primaryTimeout, failureTimeout, null);
    }

    private boolean submitPathCalculation(
            BetterBlockPos start,
            Goal goal,
            IPath previous,
            boolean nextSegment,
            long primaryTimeout,
            long failureTimeout,
            CalculationContext desiredContext) {
        if (inProgressPathfinder != null) return false;

        /*
         * Context creation and snapshot-table publication happen on the
         * server thread. The expensive graph search only sees immutable exact
         * chunk copies (or the compact persistent cache).
         */
        int startChunkX = start.x >> 4;
        int startChunkZ = start.z >> 4;
        if (!getWorldCache().hasExactSnapshot(
                startChunkX, startChunkZ)) {
            net.minecraft.world.level.chunk.LevelChunk chunk =
                    playerContext.world().getChunkSource().getChunkNow(
                            startChunkX, startChunkZ);
            if (chunk != null) getWorldCache().captureExact(chunk);
        }
        getWorldCache().warmExactSnapshots(
                start,
                Math.min(2, playerContext.server()
                        .getPlayerList().getViewDistance()),
                Math.max(0, settings()
                        .pathingSnapshotWarmupChunkBudget.value));
        CalculationContext context = desiredContext != null
                ? desiredContext
                : builderProcess.isPathingGoal(goal)
                ? builderProcess.calculationContext(goal)
                : new CalculationContext(this, true, goal);
        Map<Long, Long> snapshotRevisions =
                getWorldCache().exactSnapshotRevisions();
        HybridPathFinder finder = new HybridPathFinder(
                start, goal,
                new Favoring(playerContext, previous, context),
                context);
        long generation = ++calculationGeneration;
        inProgressPathfinder = finder;
        inProgressNextSegment = nextSegment;
        pathingBehavior.setInProgress(finder);
        gameEventHandler.onPathEvent(nextSegment
                ? PathEvent.NEXT_SEGMENT_CALC_STARTED
                : PathEvent.CALC_STARTED);
        try {
            calculationFuture = ServerPathingScheduler.submit(() -> {
                PathCalculationResult result =
                        finder.calculate(primaryTimeout, failureTimeout);
                pathCompletions.add(new PathCompletion(
                        generation, goal, start, nextSegment,
                        snapshotRevisions, context, result));
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            inProgressPathfinder = null;
            inProgressNextSegment = false;
            pathingBehavior.setInProgress(null);
            calculationFuture = null;
            pathRecalcPending = !nextSegment;
            nextRecalculationTick =
                    playerContext.world().getGameTime() + 10L;
            return false;
        }
    }

    private void drainPathCompletions() {
        PathCompletion completion;
        while ((completion = pathCompletions.poll()) != null) {
            if (completion.generation != calculationGeneration) {
                continue;
            }
            inProgressPathfinder = null;
            inProgressNextSegment = false;
            pathingBehavior.setInProgress(null);
            calculationFuture = null;
            Optional<IPath> calculated = completion.result.getPath();
            if (settings().diagnosticLogging.value) {
                if (calculated.isPresent()) {
                    long pillars = calculated.get().movements().stream()
                            .filter(move -> move.getClass().getSimpleName()
                                    .equals("MovementPillar")).count();
                    long bridges = calculated.get().movements().stream()
                            .filter(move -> move.getClass().getSimpleName()
                                    .equals("MovementTraverse"))
                            .count();
                    System.out.println("[CBI-DIAG] path-result player="
                            + playerContext.player().getScoreboardName()
                            + " type=" + completion.result.getType()
                            + " nodes=" + calculated.get().positions().size()
                            + " pillars=" + pillars
                            + " traverses=" + bridges
                            + " dest=" + calculated.get().getDest());
                } else {
                    System.out.println("[CBI-DIAG] path-result player="
                            + playerContext.player().getScoreboardName()
                            + " type=" + completion.result.getType()
                            + " noPath goal=" + completion.goal);
                }
            }
            if (!completion.nextSegment
                    && calculated.isPresent()
                    && completion.result.getType()
                    == PathCalculationResult.Type.SUCCESS_TO_GOAL
                    && activeGoal != null
                    && !activeGoal.isInGoal(
                            calculated.get().getDest())) {
                // A process may publish a new composite while the worker is
                // still finishing the previous one. Never execute that stale
                // segment; preserve the newer goal and recalculate from the
                // current server position.
                pathRecalcPending = true;
                nextRecalculationTick =
                        playerContext.world().getGameTime() + 1L;
                gameEventHandler.onPathEvent(PathEvent.CALC_FAILED);
                continue;
            }
            if (calculated.isPresent()
                    && !pathSnapshotStillValid(
                            calculated.get(),
                            completion.snapshotRevisions)) {
                if (completion.nextSegment) {
                    nextPathExecutor = null;
                    gameEventHandler.onPathEvent(
                            PathEvent.DISCARD_NEXT);
                } else {
                    pathRecalcPending = activeGoal != null;
                    nextRecalculationTick =
                            playerContext.world().getGameTime() + 1L;
                }
                continue;
            }
            if (completion.nextSegment) {
                if (calculated.isPresent() && pathExecutor == null
                        && (calculated.get().positions().contains(
                                playerContext.playerFeet())
                        || calculated.get().positions().contains(
                                pathingBehavior.pathStart()))) {
                    pathExecutor = new ServerPathExecutor(
                            this, pathingBehavior, calculated.get(),
                            completion.calculationContext);
                    gameEventHandler.onPathEvent(
                            PathEvent.CALC_FINISHED_NOW_EXECUTING);
                } else if (calculated.isPresent()
                        && pathExecutor != null
                        && calculated.get().getSrc().equals(
                        pathExecutor.getPath().getDest())) {
                    nextPathExecutor = new ServerPathExecutor(
                            this, pathingBehavior, calculated.get(),
                            completion.calculationContext);
                    gameEventHandler.onPathEvent(
                            PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                } else if (completion.result.getType()
                        != PathCalculationResult.Type.CANCELLATION) {
                    if (pathExecutor == null) {
                        consecutivePathFailures++;
                        gameEventHandler.onPathEvent(PathEvent.CALC_FAILED);
                        calcFailedLastTick =
                                scheduleRecalculationBackoff();
                    } else {
                        gameEventHandler.onPathEvent(
                                PathEvent.NEXT_CALC_FAILED);
                    }
                }
                continue;
            }
            if (calculated.isPresent()
                    && (calculated.get().positions().contains(
                            playerContext.playerFeet())
                    || calculated.get().positions().contains(
                            pathingBehavior.pathStart()))) {
                pathExecutor = new ServerPathExecutor(
                        this, pathingBehavior, calculated.get(),
                        completion.calculationContext);
                consecutivePathFailures = 0;
                gameEventHandler.onPathEvent(
                        PathEvent.CALC_FINISHED_NOW_EXECUTING);
            } else if (completion.result.getType()
                    != PathCalculationResult.Type.CANCELLATION) {
                gameEventHandler.onPathEvent(PathEvent.CALC_FAILED);
                consecutivePathFailures++;
                calcFailedLastTick = scheduleRecalculationBackoff();
            }
        }
    }

    private boolean pathSnapshotStillValid(
            IPath path, Map<Long, Long> submittedRevisions) {
        Set<Long> pathChunks = new HashSet<>();
        for (BetterBlockPos pos : path.positions()) {
            pathChunks.add(net.minecraft.world.level.ChunkPos.pack(
                    pos.x >> 4, pos.z >> 4));
        }
        ServerWorldCache cache = getWorldCache();
        for (long key : pathChunks) {
            long submitted = submittedRevisions.getOrDefault(
                    key, Long.MIN_VALUE);
            if (cache.exactSnapshotRevision(key) != submitted) {
                return false;
            }
        }
        return true;
    }

    private void planAheadAndSplice() {
        if (pathExecutor == null || activeGoal == null) return;
        if (nextPathExecutor != null && pathExecutor.isSafeToCancel()
                && nextPathExecutor.snipsnapIfPossible()) {
            gameEventHandler.onPathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
            pathExecutor = nextPathExecutor;
            nextPathExecutor = null;
            return;
        }
        if (nextPathExecutor != null && settings().splicePath.value) {
            ServerPathExecutor spliced =
                    pathExecutor.trySplice(nextPathExecutor);
            if (spliced != pathExecutor) {
                pathExecutor = spliced;
                nextPathExecutor = null;
                return;
            }
        }
        if (nextPathExecutor != null || inProgressPathfinder != null
                || activeGoal.isInGoal(pathExecutor.getPath().getDest())) {
            return;
        }
        double remaining = pathExecutor.getPath().ticksRemainingFrom(
                Math.min(pathExecutor.getPosition() + 1,
                        pathExecutor.getPath().movements().size()));
        if (remaining < settings().planningTickLookahead.value) {
            BetterBlockPos start = pathExecutor.getPath().getDest();
            boolean collecting = usesCollectItemCostModel();
            long primaryTimeout = mineProcess.isActive()
                    ? settings().minePrimaryTimeoutMS.value
                    : collecting
                    ? settings().collectItemPlanAheadPrimaryTimeoutMS.value
                    : settings().planAheadPrimaryTimeoutMS.value;
            long failureTimeout = mineProcess.isActive()
                    ? settings().mineFailureTimeoutMS.value
                    : collecting
                    ? settings().collectItemPlanAheadFailureTimeoutMS.value
                    : settings().planAheadFailureTimeoutMS.value;
            submitPathCalculation(start, activeGoal,
                    pathExecutor.getPath(), true,
                    primaryTimeout, failureTimeout);
        }
    }

    private boolean pathContainsCurrentPosition(ServerPathExecutor executor) {
        return executor.getPath().positions().contains(
                playerContext.playerFeet())
                || executor.getPath().positions().contains(
                        pathingBehavior.pathStart());
    }

    private boolean scheduleRecalculationBackoff() {
        int retryLimit = mineProcess.isActive()
                ? settings().minePathingFailureRetryCount.value
                : settings().pathingFailureRetryCount.value;
        if (activeGoal == null || consecutivePathFailures
                >= Math.max(1, retryLimit)) {
            activeGoal = null;
            pathRecalcPending = false;
            deferredProcessRecalculation = null;
            deferredProcessCalculationContext = null;
            return true;
        }
        pathRecalcPending = true;
        long delay = Math.max(1,
                settings().pathingFailureBackoffTicks.value) << Math.min(3,
                Math.max(0, consecutivePathFailures - 1));
        nextRecalculationTick =
                playerContext.world().getGameTime() + delay;
        return false;
    }

    private void cancelCalculation() {
        calculationGeneration++;
        HybridPathFinder finder = inProgressPathfinder;
        if (finder != null) finder.cancel();
        Future<?> future = calculationFuture;
        ServerPathingScheduler.cancel(future);
        inProgressPathfinder = null;
        inProgressNextSegment = false;
        calculationFuture = null;
        pathingBehavior.setInProgress(null);
    }

    public void startFollowing(java.util.function.Predicate<net.minecraft.world.entity.Entity> filter) {
        cancelAll();
        followProcess.follow(filter);
    }

    public boolean isTrashDrop(ItemEntity entity) {
        if (mineProcess.isActive()
                && mineProcess.isProtectedDesiredDrop(entity)) {
            return false;
        }
        if (farmProcess.isActive()
                && farmProcess.isDesiredFarmDrop(entity.getItem())) {
            return false;
        }
        if (blockTask != null) {
            BlockPos protectedOrigin = blockTask.protectedDropOrigin();
            if (protectedOrigin != null
                    && protectedOrigin.distSqr(entity.blockPosition()) <= 64.0D
                    && blockTask.isDesiredMiningDrop(entity.getItem())) {
                return false;
            }
        }
        return trashDiscardController.isTrash(entity);
    }

    public static Settings settings() {
        return BaritoneAPI.getSettings();
    }
}
