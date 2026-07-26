package baritone.cache;

import baritone.api.cache.IWorldScanner;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * On a server the normal scanner already has direct palette access, so the
 * upstream fast-scanner entry point delegates to it.
 */
public enum FasterWorldScanner implements IWorldScanner {
    INSTANCE;

    @Override public List<BlockPos> scanChunkRadius(
            IPlayerContext ctx, BlockOptionalMetaLookup filter, int max,
            int yLevelThreshold, int maxSearchRadius) {
        return WorldScanner.INSTANCE.scanChunkRadius(
                ctx, filter, max, yLevelThreshold, maxSearchRadius);
    }
    @Override public List<BlockPos> scanChunk(
            IPlayerContext ctx, BlockOptionalMetaLookup filter, ChunkPos pos,
            int max, int yLevelThreshold) {
        return WorldScanner.INSTANCE.scanChunk(
                ctx, filter, pos, max, yLevelThreshold);
    }
    @Override public int repack(IPlayerContext ctx) {
        return WorldScanner.INSTANCE.repack(ctx);
    }
    @Override public int repack(IPlayerContext ctx, int range) {
        return WorldScanner.INSTANCE.repack(ctx, range);
    }

    public static List<ChunkPos> getChunkRange(
            int centerX, int centerZ, int chunkRadius) {
        List<ChunkPos> chunks = new ArrayList<>();
        chunks.add(new ChunkPos(centerX, centerZ));
        for (int radius = 1; radius < chunkRadius; radius++) {
            for (int offset = 0; offset <= radius; offset++) {
                chunks.add(new ChunkPos(centerX - offset, centerZ - radius));
                if (offset != 0) {
                    chunks.add(new ChunkPos(centerX + offset, centerZ - radius));
                    chunks.add(new ChunkPos(centerX - offset, centerZ + radius));
                }
                chunks.add(new ChunkPos(centerX + offset, centerZ + radius));
                if (offset != radius) {
                    chunks.add(new ChunkPos(centerX - radius, centerZ - offset));
                    chunks.add(new ChunkPos(centerX + radius, centerZ - offset));
                    if (offset != 0) {
                        chunks.add(new ChunkPos(centerX - radius, centerZ + offset));
                        chunks.add(new ChunkPos(centerX + radius, centerZ + offset));
                    }
                }
            }
        }
        return chunks;
    }
}
