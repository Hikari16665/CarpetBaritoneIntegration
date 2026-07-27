/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.api;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Settings are rebuilt incrementally as server-side modules are ported.
 *
 * <p>The field names and {@link Setting} wrapper remain API-compatible with
 * upstream Baritone. This first slice contains the values required by the
 * goal and heuristic model.</p>
 */
public final class Settings {

    public final Setting<Double> costHeuristic = new Setting<>(3.563D);
    public final Setting<Boolean> censorCoordinates = new Setting<>(false);
    public final Setting<Integer> axisHeight = new Setting<>(120);
    public final Setting<Boolean> cutoffAtLoadBoundary = new Setting<>(false);
    public final Setting<Double> pathCutoffFactor = new Setting<>(0.9D);
    public final Setting<Integer> pathCutoffMinimumLength = new Setting<>(30);
    public final Setting<Boolean> remainWithExistingLookDirection = new Setting<>(true);
    public final Setting<Boolean> preferSilkTouch = new Setting<>(false);
    public final Setting<Integer> pathingMaxChunkBorderFetch = new Setting<>(50);
    public final Setting<Boolean> minimumImprovementRepropagation = new Setting<>(true);
    public final Setting<Integer> pathingMapDefaultSize = new Setting<>(1024);
    public final Setting<Float> pathingMapLoadFactor = new Setting<>(0.75F);
    public final Setting<Boolean> slowPath = new Setting<>(false);
    public final Setting<Long> slowPathTimeDelayMS = new Setting<>(100L);
    public final Setting<Long> slowPathTimeoutMS = new Setting<>(40000L);
    public final Setting<Boolean> avoidance = new Setting<>(false);
    public final Setting<Double> mobSpawnerAvoidanceCoefficient = new Setting<>(2.0D);
    public final Setting<Integer> mobSpawnerAvoidanceRadius = new Setting<>(16);
    public final Setting<Double> mobAvoidanceCoefficient = new Setting<>(1.5D);
    public final Setting<Integer> mobAvoidanceRadius = new Setting<>(8);
    public final Setting<Boolean> sprintAscends = new Setting<>(true);
    public final Setting<Double> maxCostIncrease = new Setting<>(10.0D);
    public final Setting<Integer> costVerificationLookahead = new Setting<>(5);
    public final Setting<Boolean> allowOvershootDiagonalDescend = new Setting<>(true);
    public final Setting<Integer> movementTimeoutTicks = new Setting<>(100);
    public final Setting<Integer> maxPathHistoryLength = new Setting<>(300);
    public final Setting<Integer> pathHistoryCutoffAmount = new Setting<>(50);
    public final Setting<Integer> planningTickLookahead = new Setting<>(150);
    public final Setting<Long> primaryTimeoutMS = new Setting<>(500L);
    public final Setting<Long> failureTimeoutMS = new Setting<>(2000L);
    /** CollectItem cannot break through walls, so distant openings need a
     * wider search window than ordinary replaceable navigation. */
    public final Setting<Long> collectItemPrimaryTimeoutMS =
            new Setting<>(1500L);
    public final Setting<Long> collectItemFailureTimeoutMS =
            new Setting<>(8000L);
    public final Setting<Long> collectItemPlanAheadPrimaryTimeoutMS =
            new Setting<>(1000L);
    public final Setting<Long> collectItemPlanAheadFailureTimeoutMS =
            new Setting<>(6000L);
    public final Setting<Long> planAheadPrimaryTimeoutMS = new Setting<>(4000L);
    public final Setting<Long> planAheadFailureTimeoutMS = new Setting<>(5000L);
    public final Setting<Integer> pathingFailureRetryCount = new Setting<>(3);
    public final Setting<Integer> pathingFailureBackoffTicks = new Setting<>(10);
    public final Setting<Long> minePrimaryTimeoutMS = new Setting<>(2000L);
    public final Setting<Long> mineFailureTimeoutMS = new Setting<>(10000L);
    public final Setting<Integer> minePathingFailureRetryCount = new Setting<>(5);
    public final Setting<Integer> mineBlacklistCooldownTicks = new Setting<>(600);
    public final Setting<Double> mineBlockBreakAdditionalPenalty =
            new Setting<>(0.0D);
    public final Setting<Boolean> splicePath = new Setting<>(true);
    public final Setting<Boolean> cancelOnGoalInvalidation =
            new Setting<>(true);
    public final Setting<Boolean> blacklistClosestOnFailure = new Setting<>(true);
    public final Setting<Integer> mineMaxOreLocationsCount = new Setting<>(64);
    public final Setting<Integer> minYLevelWhileMining = new Setting<>(0);
    public final Setting<Integer> maxYLevelWhileMining = new Setting<>(2031);
    public final Setting<Boolean> allowOnlyExposedOres = new Setting<>(false);
    public final Setting<Integer> allowOnlyExposedOresDistance = new Setting<>(1);
    public final Setting<Double> followOffsetDistance = new Setting<>(0.0D);
    public final Setting<Float> followOffsetDirection = new Setting<>(0.0F);
    public final Setting<Integer> followRadius = new Setting<>(3);
    public final Setting<Integer> followTargetMaxDistance = new Setting<>(0);
    public final Setting<Integer> worldExploringChunkOffset = new Setting<>(0);
    public final Setting<Integer> exploreChunkSetMinimumSize = new Setting<>(10);
    public final Setting<Integer> exploreMaintainY = new Setting<>(64);
    public final Setting<Boolean> disableCompletionCheck = new Setting<>(false);
    public final Setting<Boolean> rightClickContainerOnArrival = new Setting<>(true);
    public final Setting<Boolean> enterPortal = new Setting<>(true);
    public final Setting<Integer> mineGoalUpdateInterval = new Setting<>(5);
    public final Setting<Boolean> exploreForBlocks = new Setting<>(true);
    public final Setting<Boolean> mineScanDroppedItems = new Setting<>(true);
    public final Setting<Boolean> legitMine = new Setting<>(false);
    public final Setting<Integer> legitMineYLevel = new Setting<>(-59);
    public final Setting<Boolean> legitMineIncludeDiagonals =
            new Setting<>(false);
    public final Setting<Long> mineDropLoiterDurationMSThanksLouca =
            new Setting<>(250L);
    public final Setting<Boolean> forceInternalMining = new Setting<>(true);
    public final Setting<Boolean> internalMiningAirException = new Setting<>(true);
    public final Setting<Boolean> chunkCaching = new Setting<>(true);
    public final Setting<Integer> chunkPackerQueueMaxSize = new Setting<>(2000);
    public final Setting<Integer> maxCachedWorldScanCount = new Setting<>(10);
    public final Setting<Integer> synchronousWorldScannerChunkBudget =
            new Setting<>(2);
    public final Setting<Boolean> extendCacheOnThreshold = new Setting<>(false);
    public final Setting<Boolean> repackOnAnyBlockChange = new Setting<>(true);
    public final Setting<Boolean> pruneRegionsFromRAM = new Setting<>(true);
    public final Setting<Long> cachedChunksExpirySeconds = new Setting<>(-1L);
    public final Setting<Boolean> backfill = new Setting<>(false);
    public final Setting<Boolean> replantCrops = new Setting<>(true);
    public final Setting<Boolean> replantNetherWart = new Setting<>(false);
    public final Setting<Integer> farmMaxScanSize = new Setting<>(256);
    public final Setting<Boolean> elytraAutoJump = new Setting<>(false);
    public final Setting<Boolean> elytraAutoSwap = new Setting<>(true);
    public final Setting<Integer> elytraMinimumDurability = new Setting<>(5);
    public final Setting<Integer> elytraMinFireworksBeforeLanding =
            new Setting<>(5);
    public final Setting<Boolean> elytraAllowEmergencyLand = new Setting<>(true);
    public final Setting<Integer> elytraCruiseAltitude = new Setting<>(1000);
    public final Setting<Integer> elytraGlideLowAltitude = new Setting<>(400);
    public final Setting<Integer> elytraBoostIntervalTicks = new Setting<>(30);
    public final Setting<Integer> elytraLandingApproachDistance = new Setting<>(80);
    public final Setting<Boolean> doBedWaypoints = new Setting<>(true);
    public final Setting<Boolean> doDeathWaypoints = new Setting<>(true);
    public final Setting<Double> blockReachDistance = new Setting<>(4.5D);
    public final Setting<Boolean> buildInLayers = new Setting<>(false);
    public final Setting<Boolean> layerOrder = new Setting<>(false);
    public final Setting<Integer> layerHeight = new Setting<>(1);
    public final Setting<Integer> startAtLayer = new Setting<>(0);
    public final Setting<Boolean> skipFailedLayers = new Setting<>(false);
    public final Setting<Boolean> buildOnlySelection = new Setting<>(false);
    public final Setting<Boolean> okIfWater = new Setting<>(false);
    public final Setting<List<Block>> okIfAir =
            new Setting<>(new ArrayList<>());
    public final Setting<List<Block>> buildIgnoreBlocks =
            new Setting<>(new ArrayList<>());
    public final Setting<Boolean> buildIgnoreExisting =
            new Setting<>(false);
    public final Setting<Boolean> buildIgnoreDirection =
            new Setting<>(false);
    public final Setting<List<String>> buildIgnoreProperties =
            new Setting<>(new ArrayList<>());
    public final Setting<Map<Block, List<Block>>> buildValidSubstitutes =
            new Setting<>(new HashMap<>());
    public final Setting<Boolean> schematicOrientationX = new Setting<>(false);
    public final Setting<Boolean> schematicOrientationY = new Setting<>(false);
    public final Setting<Boolean> schematicOrientationZ = new Setting<>(false);
    public final Setting<Map<Block, List<Block>>> buildSubstitutes =
            new Setting<>(new HashMap<>());
    public final Setting<Mirror> buildSchematicMirror = new Setting<>(Mirror.NONE);
    public final Setting<Rotation> buildSchematicRotation = new Setting<>(Rotation.NONE);
    public final Setting<List<Block>> buildSkipBlocks = new Setting<>(new ArrayList<>());
    public final Setting<Boolean> mapArtMode = new Setting<>(false);
    public final Setting<List<Item>> acceptableThrowawayItems = new Setting<>(new ArrayList<>(List.of(
            Blocks.DIRT.asItem(),
            Blocks.COBBLESTONE.asItem(),
            Blocks.NETHERRACK.asItem(),
            Blocks.STONE.asItem()
    )));
    public final Setting<List<Item>> trashItems =
            new Setting<>(new ArrayList<>(List.of(
                    Blocks.STONE.asItem(),
                    Blocks.COBBLESTONE.asItem(),
                    Blocks.DEEPSLATE.asItem(),
                    Blocks.COBBLED_DEEPSLATE.asItem(),
                    Blocks.DIRT.asItem(),
                    Blocks.GRAVEL.asItem(),
                    Blocks.NETHERRACK.asItem(),
                    Blocks.GRANITE.asItem(),
                    Blocks.DIORITE.asItem(),
                    Blocks.ANDESITE.asItem(),
                    Blocks.TUFF.asItem()
            )));

    // Walking path calculation and execution settings.
    public final Setting<Boolean> allowBreak = new Setting<>(true);
    public final Setting<List<Block>> allowBreakAnyway = new Setting<>(new ArrayList<>());
    public final Setting<Boolean> allowDiagonalAscend = new Setting<>(false);
    public final Setting<Boolean> allowDiagonalDescend = new Setting<>(false);
    public final Setting<Boolean> allowDownward = new Setting<>(true);
    public final Setting<Boolean> allowJumpAtBuildLimit = new Setting<>(false);
    public final Setting<Boolean> allowParkour = new Setting<>(false);
    public final Setting<Boolean> allowParkourAscend = new Setting<>(true);
    public final Setting<Boolean> allowParkourPlace = new Setting<>(false);
    public final Setting<Boolean> allowPlace = new Setting<>(true);
    public final Setting<Boolean> allowPlaceInFluidsFlow = new Setting<>(true);
    public final Setting<Boolean> allowPlaceInFluidsSource = new Setting<>(true);
    public final Setting<Boolean> allowSprint = new Setting<>(true);
    public final Setting<Boolean> allowVines = new Setting<>(false);
    public final Setting<Boolean> allowWalkOnBottomSlab = new Setting<>(true);
    public final Setting<Boolean> allowWalkOnMagmaBlocks = new Setting<>(false);
    public final Setting<Boolean> allowWaterBucketFall = new Setting<>(true);
    public final Setting<Boolean> assumeExternalAutoTool = new Setting<>(false);
    public final Setting<Boolean> assumeSafeWalk = new Setting<>(false);
    public final Setting<Boolean> assumeStep = new Setting<>(false);
    public final Setting<Boolean> assumeWalkOnLava = new Setting<>(false);
    public final Setting<Boolean> assumeWalkOnWater = new Setting<>(false);
    public final Setting<Boolean> autoTool = new Setting<>(true);
    public final Setting<Boolean> avoidUpdatingFallingBlocks = new Setting<>(true);
    public final Setting<Double> backtrackCostFavoringCoefficient = new Setting<>(0.5D);
    public final Setting<Double> blockBreakAdditionalPenalty = new Setting<>(2.0D);
    public final Setting<Double> blockPlacementPenalty = new Setting<>(20.0D);
    public final Setting<List<Block>> blocksToAvoid =
            new Setting<>(new ArrayList<>(List.of(Blocks.TRIPWIRE)));
    public final Setting<List<Block>> blocksToDisallowBreaking =
            new Setting<>(new ArrayList<>());
    public final Setting<Double> jumpPenalty = new Setting<>(2.0D);
    public final Setting<Integer> maxFallHeightBucket = new Setting<>(20);
    public final Setting<Integer> maxFallHeightNoWater = new Setting<>(3);
    public final Setting<Boolean> overshootTraverse = new Setting<>(true);
    public final Setting<Boolean> pauseMiningForFallingBlocks = new Setting<>(true);
    public final Setting<Boolean> sprintInWater = new Setting<>(true);
    public final Setting<Boolean> strictLiquidCheck = new Setting<>(false);
    public final Setting<Double> walkOnWaterOnePenalty = new Setting<>(3.0D);
    public final Setting<Boolean> walkWhileBreaking = new Setting<>(true);
    public final Setting<Boolean> considerPotionEffects = new Setting<>(true);
    public final Setting<Boolean> useSwordToMine = new Setting<>(true);
    public final Setting<Boolean> itemSaver = new Setting<>(false);
    public final Setting<Integer> itemSaverThreshold = new Setting<>(10);
    public final Setting<List<Block>> blocksToAvoidBreaking = new Setting<>(
            new ArrayList<>(List.of(
                    Blocks.CRAFTING_TABLE,
                    Blocks.FURNACE,
                    Blocks.CHEST,
                    Blocks.TRAPPED_CHEST
            ))
    );
    public final Setting<Double> avoidBreakingMultiplier = new Setting<>(0.1D);

    public static final class Setting<T> {
        public final T defaultValue;
        public T value;

        public Setting(T defaultValue) {
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public void reset() {
            this.value = this.defaultValue;
        }
    }
}
