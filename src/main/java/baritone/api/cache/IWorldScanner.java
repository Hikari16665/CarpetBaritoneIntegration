package baritone.api.cache;

import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** Server-safe form of Baritone's world scanning API. */
public interface IWorldScanner {
    List<BlockPos> scanChunkRadius(
            IPlayerContext ctx, BlockOptionalMetaLookup filter, int max,
            int yLevelThreshold, int maxSearchRadius);

    default List<BlockPos> scanChunkRadius(
            IPlayerContext ctx, List<Block> blocks, int max,
            int yLevelThreshold, int maxSearchRadius) {
        return scanChunkRadius(ctx,
                new BlockOptionalMetaLookup(blocks.toArray(Block[]::new)),
                max, yLevelThreshold, maxSearchRadius);
    }

    List<BlockPos> scanChunk(
            IPlayerContext ctx, BlockOptionalMetaLookup filter, ChunkPos pos,
            int max, int yLevelThreshold);

    default List<BlockPos> scanChunk(
            IPlayerContext ctx, List<Block> blocks, ChunkPos pos,
            int max, int yLevelThreshold) {
        return scanChunk(ctx,
                new BlockOptionalMetaLookup(blocks.toArray(Block[]::new)),
                pos, max, yLevelThreshold);
    }

    int repack(IPlayerContext ctx);

    int repack(IPlayerContext ctx, int range);
}
