/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.cache.CachedChunk;
import baritone.cache.ExactChunkSnapshot;
import baritone.cache.ServerWorldCache;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Map;

/**
 * Direct server-world block lookup used by path calculation.
 *
 * <p>Unlike upstream Baritone this does not use a client chunk cache. The
 * authoritative {@link ServerLevel} is queried directly.</p>
 */
public final class BlockStateInterface {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    protected final ServerLevel world;
    protected final ServerWorldCache cachedWorld;
    private final Map<Long, ExactChunkSnapshot> exactSnapshots;
    private final Map<Long, CachedChunk> compactSnapshots;
    private final boolean threaded;
    private final int minY;
    private final int maxY;
    private final DimensionType dimensionType;
    public final BlockPos.MutableBlockPos isPassableBlockPos = new BlockPos.MutableBlockPos();
    public final BlockGetter access;
    public final BetterWorldBorder worldBorder;

    public BlockStateInterface(IPlayerContext ctx) {
        this(ctx, false);
    }

    /**
     * The second argument is retained for source compatibility. Server worlds
     * are authoritative, so no client chunk copy is required.
     */
    public BlockStateInterface(IPlayerContext ctx, boolean copyLoadedChunks) {
        this.world = ctx.world();
        this.cachedWorld = ServerWorldCache.get(world);
        this.threaded = copyLoadedChunks;
        this.minY = world.getMinY();
        this.maxY = world.getMaxY();
        this.dimensionType = world.dimensionType();
        this.exactSnapshots = copyLoadedChunks
                ? cachedWorld.exactSnapshotView()
                : Map.of();
        this.compactSnapshots = copyLoadedChunks
                ? cachedWorld.compactSnapshotView()
                : Map.of();
        this.access = world;
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    }

    public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
        if (threaded) {
            return exactSnapshots.containsKey(
                    net.minecraft.world.level.ChunkPos.asLong(
                            blockX >> 4, blockZ >> 4));
        }
        return world.hasChunk(blockX >> 4, blockZ >> 4);
    }

    public boolean isLoaded(int blockX, int blockZ) {
        if (threaded) {
            long key = net.minecraft.world.level.ChunkPos.asLong(
                    blockX >> 4, blockZ >> 4);
            return exactSnapshots.containsKey(key)
                    || (Baritone.settings().chunkCaching.value
                    && compactSnapshots.containsKey(key));
        }
        return worldContainsLoadedChunk(blockX, blockZ)
                || (Baritone.settings().chunkCaching.value
                && cachedWorld.isChunkCached(blockX >> 4, blockZ >> 4));
    }

    public static Block getBlock(IPlayerContext ctx, BlockPos pos) {
        return get(ctx, pos).getBlock();
    }

    public static BlockState get(IPlayerContext ctx, BlockPos pos) {
        return new BlockStateInterface(ctx).get0(pos);
    }

    public BlockState get0(BlockPos pos) {
        return get0(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState get0(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return AIR;
        }
        if (threaded) {
            ExactChunkSnapshot exact = exactSnapshots.get(
                    net.minecraft.world.level.ChunkPos.asLong(
                            x >> 4, z >> 4));
            if (exact != null) return exact.getBlockState(x, y, z);
            if (!Baritone.settings().chunkCaching.value) return AIR;
            CachedChunk chunk = compactSnapshots.get(
                    net.minecraft.world.level.ChunkPos.asLong(
                            x >> 4, z >> 4));
            return chunk == null ? AIR : chunk.getBlock(
                    x & 15, y - chunk.minY(), z & 15, dimensionType);
        }
        if (!worldContainsLoadedChunk(x, z)) {
            if (!Baritone.settings().chunkCaching.value) return AIR;
            CachedChunk chunk = cachedWorld.cachedChunk(x >> 4, z >> 4);
            return chunk == null ? AIR : chunk.getBlock(x, y, z, world);
        }
        isPassableBlockPos.set(x, y, z);
        return world.getBlockState(isPassableBlockPos);
    }
}
