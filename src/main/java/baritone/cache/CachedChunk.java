package baritone.cache;

import baritone.pathing.movement.MovementHelper;
import baritone.utils.pathing.PathingBlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.BitSet;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Compact, immutable two-bit snapshot used only when its live server chunk is
 * no longer loaded. Exact states remain authoritative for loaded chunks.
 */
public final class CachedChunk {
    public static final Set<net.minecraft.world.level.block.Block>
            BLOCKS_TO_KEEP_TRACK_OF =
            new AbstractSet<>() {
                @Override
                public Iterator<net.minecraft.world.level.block.Block> iterator() {
                    return ServerWorldCache.trackedBlocks().iterator();
                }

                @Override
                public int size() {
                    return ServerWorldCache.trackedBlocks().size();
                }

                @Override
                public boolean contains(Object value) {
                    return ServerWorldCache.trackedBlocks().contains(value);
                }
            };
    public final int x;
    public final int z;
    public final int height;
    public final int size;
    public final int sizeInBytes;
    public final long cacheTimestamp;
    private final int minY;
    private final BitSet data;

    private CachedChunk(
            int chunkX, int chunkZ, int minY, int height,
            BitSet data, long capturedAt) {
        this.x = chunkX;
        this.z = chunkZ;
        this.minY = minY;
        this.height = height;
        this.size = size(height);
        this.sizeInBytes = sizeInBytes(size);
        this.data = data;
        this.cacheTimestamp = capturedAt;
    }

    public static CachedChunk fromData(
            int chunkX, int chunkZ, int minY, int height,
            byte[] data, long capturedAt) {
        return new CachedChunk(chunkX, chunkZ, minY, height,
                BitSet.valueOf(data), capturedAt);
    }

    public static CachedChunk pack(LevelChunk chunk) {
        ServerLevel world = (ServerLevel) chunk.getLevel();
        int minY = chunk.getMinY();
        int height = world.getHeight();
        BitSet bits = new BitSet(16 * 16 * height * 2);
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (int y = 0; y < 16; y++) {
                int absoluteY = baseY + y;
                if (absoluteY < minY || absoluteY >= minY + height) continue;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        BlockPos position = new BlockPos(
                                (chunk.getPos().x() << 4) + x, absoluteY,
                                (chunk.getPos().z() << 4) + z);
                        boolean[] type = classify(state, world, position).getBits();
                        int index = index(x, absoluteY - minY, z);
                        bits.set(index, type[0]);
                        bits.set(index + 1, type[1]);
                    }
                }
            }
        }
        return new CachedChunk(chunk.getPos().x(), chunk.getPos().z(),
                minY, height, bits, System.currentTimeMillis());
    }

    /**
     * Performs the expensive compact classification from an immutable exact
     * snapshot. This overload is safe on the cache worker and never touches a
     * live server chunk.
     */
    public static CachedChunk pack(ExactChunkSnapshot snapshot) {
        int minY = snapshot.minY();
        int height = snapshot.maxY() - minY;
        BitSet bits = new BitSet(16 * 16 * height * 2);
        for (int relativeY = 0; relativeY < height; relativeY++) {
            int y = minY + relativeY;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = snapshot.getBlockState(
                            (snapshot.chunkX() << 4) + x, y,
                            (snapshot.chunkZ() << 4) + z);
                    PathingBlockType type = classifySnapshot(state);
                    boolean[] values = type.getBits();
                    int index = index(x, relativeY, z);
                    bits.set(index, values[0]);
                    bits.set(index + 1, values[1]);
                }
            }
        }
        return new CachedChunk(snapshot.chunkX(), snapshot.chunkZ(),
                minY, height, bits, System.currentTimeMillis());
    }

    private static PathingBlockType classifySnapshot(BlockState state) {
        if (state.isAir()) return PathingBlockType.AIR;
        if (state.getFluidState().is(FluidTags.WATER)) {
            return MovementHelper.possiblyFlowing(state)
                    ? PathingBlockType.AVOID : PathingBlockType.WATER;
        }
        if (!state.getFluidState().isEmpty()
                || MovementHelper.avoidWalkingInto(state)) {
            return PathingBlockType.AVOID;
        }
        if (state.getCollisionShape(EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO).isEmpty()) {
            return PathingBlockType.AIR;
        }
        return PathingBlockType.SOLID;
    }

    private static PathingBlockType classify(
            BlockState state, ServerLevel world, BlockPos position) {
        if (state.isAir()) return PathingBlockType.AIR;
        if (state.getFluidState().is(FluidTags.WATER)) {
            return MovementHelper.possiblyFlowing(state)
                    ? PathingBlockType.AVOID : PathingBlockType.WATER;
        }
        if (!state.getFluidState().isEmpty()
                || MovementHelper.avoidWalkingInto(state)) {
            return PathingBlockType.AVOID;
        }
        if (state.getCollisionShape(world, position).isEmpty()) {
            return PathingBlockType.AIR;
        }
        return PathingBlockType.SOLID;
    }

    public BlockState getBlock(int x, int y, int z, ServerLevel world) {
        if (y < minY || y >= minY + height
                || (x >> 4) != this.x || (z >> 4) != this.z) {
            return Blocks.AIR.defaultBlockState();
        }
        int index = index(x & 15, y - minY, z & 15);
        return switch (PathingBlockType.fromBits(data.get(index), data.get(index + 1))) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case AVOID -> Blocks.LAVA.defaultBlockState();
            case SOLID -> world.dimensionType().attributes().applyModifier(
                    EnvironmentAttributes.WATER_EVAPORATES, false)
                    ? Blocks.NETHERRACK.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
        };
    }

    public long capturedAt() {
        return cacheTimestamp;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    public byte[] data() {
        byte[] packed = data.toByteArray();
        int fullLength = (16 * 16 * height * 2 + 7) / 8;
        if (packed.length == fullLength) return packed;
        byte[] full = new byte[fullLength];
        System.arraycopy(packed, 0, full, 0, Math.min(packed.length, full.length));
        return full;
    }

    public byte[] toByteArray() {
        return data();
    }

    public static int size(int dimensionHeight) {
        return 2 * 16 * 16 * dimensionHeight;
    }

    public static int sizeInBytes(int bitSize) {
        return (bitSize + 7) / 8;
    }

    public static int getPositionIndex(int x, int y, int z) {
        return ((y * 16 + z) * 16 + x) * 2;
    }

    public BlockState getBlock(
            int localX, int relativeY, int localZ, DimensionType dimension) {
        if (relativeY < 0 || relativeY >= height) {
            return Blocks.AIR.defaultBlockState();
        }
        int index = index(localX & 15, relativeY, localZ & 15);
        return ChunkPacker.pathingTypeToBlock(
                PathingBlockType.fromBits(data.get(index), data.get(index + 1)),
                dimension);
    }

    private static int index(int x, int relativeY, int z) {
        return ((relativeY * 16 + z) * 16 + x) * 2;
    }
}
