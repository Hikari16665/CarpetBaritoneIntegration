package baritone.cache;

import baritone.api.cache.IWorldScanner;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Server adaptation of the upstream scanner. It reads loaded LevelChunk
 * palettes directly and never loads/generates a chunk as a side effect.
 */
public enum WorldScanner implements IWorldScanner {
    INSTANCE;

    @Override
    public List<BlockPos> scanChunkRadius(
            IPlayerContext ctx, BlockOptionalMetaLookup filter, int max,
            int yLevelThreshold, int maxSearchRadius) {
        if (maxSearchRadius < 0) {
            throw new IllegalArgumentException("maxSearchRadius must be >= 0");
        }
        if (filter == null || filter.blocks().isEmpty() || max == 0) {
            return Collections.emptyList();
        }
        if (max < 0) max = Integer.MAX_VALUE;
        final int resultLimit = max;
        BlockPos feet = ctx.playerFeet();
        int centerX = feet.getX() >> 4;
        int centerZ = feet.getZ() >> 4;
        int radiusSq = maxSearchRadius * maxSearchRadius;
        ServerWorldCache cache = ServerWorldCache.get(ctx.world());
        ServerWorldCache.registerTrackedBlocks(filter.blocks().stream()
                .map(baritone.api.utils.BlockOptionalMeta::getBlock).toList());
        cache.queueCaptureAround(feet, maxSearchRadius);
        List<BlockPos> result = filter.blocks().stream()
                .flatMap(selector -> cache.locationsOfNear(
                        selector.getBlock(), feet.getX(), feet.getZ(),
                        maxSearchRadius, resultLimit).stream())
                .filter(pos -> {
                    int dx = (pos.getX() >> 4) - centerX;
                    int dz = (pos.getZ() >> 4) - centerZ;
                    return dx * dx + dz * dz <= radiusSq;
                })
                .filter(pos -> yLevelThreshold < 0
                        || Math.abs(pos.getY() - feet.getY())
                        <= yLevelThreshold)
                .filter(pos -> !ctx.world().hasChunkAt(pos)
                        || filter.has(ctx.world().getBlockState(pos)))
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new));
        int synchronousBudget = Math.max(0,
                baritone.Baritone.settings()
                        .synchronousWorldScannerChunkBudget.value);
        outer:
        for (int distance = 0;
             distance <= maxSearchRadius
                     && synchronousBudget > 0
                     && result.size() < resultLimit;
             distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                int dzAbs = distance - Math.abs(dx);
                for (int sign : new int[]{-1, 1}) {
                    int dz = dzAbs * sign;
                    LevelChunk chunk = ctx.world().getChunkSource()
                            .getChunkNow(centerX + dx, centerZ + dz);
                    if (chunk != null && !chunk.isEmpty()) {
                        result.addAll(scanLoadedChunk(
                                ctx, filter, chunk,
                                resultLimit - result.size(),
                                yLevelThreshold));
                        synchronousBudget--;
                    }
                    if (dzAbs == 0) break;
                    if (synchronousBudget <= 0
                            || result.size() >= resultLimit) {
                        break outer;
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(feet::distSqr));
        result = result.stream().distinct().collect(
                java.util.stream.Collectors.toCollection(ArrayList::new));
        if (result.size() > max) {
            return new ArrayList<>(result.subList(0, max));
        }
        return result;
    }

    @Override
    public List<BlockPos> scanChunk(
            IPlayerContext ctx, BlockOptionalMetaLookup filter, ChunkPos pos,
            int max, int yLevelThreshold) {
        if (filter == null || filter.blocks().isEmpty() || max == 0) {
            return Collections.emptyList();
        }
        if (max < 0) max = Integer.MAX_VALUE;
        final int resultLimit = max;
        ServerWorldCache cache = ServerWorldCache.get(ctx.world());
        ServerWorldCache.registerTrackedBlocks(filter.blocks().stream()
                .map(baritone.api.utils.BlockOptionalMeta::getBlock).toList());
        LevelChunk chunk = ctx.world().getChunkSource().getChunkNow(
                pos.x, pos.z);
        List<BlockPos> result = new ArrayList<>();
        if (chunk != null) {
            cache.queueForPacking(chunk);
            result.addAll(scanLoadedChunk(
                    ctx, filter, chunk, resultLimit, yLevelThreshold));
        }
        result.addAll(filter.blocks().stream()
                .flatMap(selector -> cache.locationsOfNear(
                        selector.getBlock(), ctx.playerFeet().getX(),
                        ctx.playerFeet().getZ(), 0, resultLimit).stream())
                .filter(block -> (block.getX() >> 4) == pos.x
                        && (block.getZ() >> 4) == pos.z)
                .filter(block -> yLevelThreshold < 0
                        || Math.abs(block.getY()
                        - ctx.playerFeet().getY()) <= yLevelThreshold)
                .filter(block -> !ctx.world().hasChunkAt(block)
                        || filter.has(ctx.world().getBlockState(block)))
                .distinct()
                .limit(max)
                .collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new)));
        result = result.stream().distinct().limit(max).collect(
                java.util.stream.Collectors.toCollection(ArrayList::new));
        result.sort(Comparator.comparingDouble(ctx.playerFeet()::distSqr));
        return result;
    }

    private static List<BlockPos> scanLoadedChunk(
            IPlayerContext ctx, BlockOptionalMetaLookup filter,
            LevelChunk chunk, int maximum, int yLevelThreshold) {
        if (maximum <= 0) return List.of();
        List<BlockPos> result = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0;
             sectionIndex < sections.length
                     && result.size() < maximum;
             sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()
                    || !section.maybeHas(filter::has)) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(
                    sectionIndex) << 4;
            for (int y = 0; y < 16
                    && result.size() < maximum; y++) {
                int worldY = sectionY + y;
                if (yLevelThreshold >= 0
                        && Math.abs(worldY
                                - ctx.playerFeet().getY())
                                > yLevelThreshold) {
                    continue;
                }
                for (int z = 0; z < 16
                        && result.size() < maximum; z++) {
                    for (int x = 0; x < 16
                            && result.size() < maximum; x++) {
                        if (filter.has(section.getBlockState(x, y, z))) {
                            result.add(new BlockPos(
                                    (chunk.getPos().x << 4) + x,
                                    worldY,
                                    (chunk.getPos().z << 4) + z));
                        }
                    }
                }
            }
        }
        return result;
    }

    @Override
    public int repack(IPlayerContext ctx) {
        return repack(ctx, 40);
    }

    @Override
    public int repack(IPlayerContext ctx, int range) {
        if (range < 0) throw new IllegalArgumentException("range must be >= 0");
        return ServerWorldCache.get(ctx.world()).captureLoadedAround(
                ctx.playerFeet(), range);
    }

}
