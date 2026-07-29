/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Immutable, palette-backed copy of a loaded server chunk.
 *
 * <p>A server {@link LevelChunk} must never be read by Baritone's path
 * calculation threads.  Copying each section keeps the compact palettes while
 * severing all mutable world/chunk references.  Instances are published by
 * {@link ServerWorldCache} and can consequently be shared by every fake
 * player calculating in the same dimension.</p>
 */
public final class ExactChunkSnapshot {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final int chunkX;
    private final int chunkZ;
    private final int minSection;
    private final int minY;
    private final int maxY;
    private final BlockState[][] sections;
    private final long revision;

    private ExactChunkSnapshot(
            int chunkX,
            int chunkZ,
            int minSection,
            int minY,
            int maxY,
            BlockState[][] sections,
            long revision) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSection = minSection;
        this.minY = minY;
        this.maxY = maxY;
        this.sections = sections;
        this.revision = revision;
    }

    public static ExactChunkSnapshot copyOf(LevelChunk chunk, long revision) {
        var source = chunk.getSections();
        BlockState[][] copy = new BlockState[source.length][];
        for (int index = 0; index < source.length; index++) {
            var section = source[index];
            if (section != null && !section.hasOnlyAir()) {
                BlockState[] states = new BlockState[16 * 16 * 16];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            states[index(x, y, z)] =
                                    section.getBlockState(x, y, z);
                        }
                    }
                }
                copy[index] = states;
            }
        }
        ServerLevel level = (ServerLevel) chunk.getLevel();
        return new ExactChunkSnapshot(
                chunk.getPos().x,
                chunk.getPos().z,
                level.getMinSection(),
                level.getMinBuildHeight(),
                level.getMaxBuildHeight(),
                copy,
                revision);
    }

    /**
     * Copy-on-write update for a single changed block. Returns {@code null}
     * when an all-air section must be materialized; the caller can then copy
     * the authoritative chunk once instead.
     */
    public ExactChunkSnapshot withBlock(
            BlockPos pos, BlockState state, long newRevision) {
        if ((pos.getX() >> 4) != chunkX
                || (pos.getZ() >> 4) != chunkZ
                || pos.getY() < minY || pos.getY() >= maxY) {
            return this;
        }
        int index = (pos.getY() >> 4) - minSection;
        if (index < 0 || index >= sections.length) return this;
        BlockState[] existing = sections[index];
        if (existing == null && !state.isAir()) return null;
        BlockState[][] updated = sections.clone();
        if (existing != null) {
            BlockState[] section = existing.clone();
            section[index(pos.getX() & 15, pos.getY() & 15,
                    pos.getZ() & 15)] = state;
            boolean onlyAir = true;
            for (BlockState candidate : section) {
                if (candidate != null && !candidate.isAir()) {
                    onlyAir = false;
                    break;
                }
            }
            updated[index] = onlyAir ? null : section;
        }
        return new ExactChunkSnapshot(
                chunkX, chunkZ, minSection, minY, maxY,
                updated, newRevision);
    }

    public BlockState getBlockState(int x, int y, int z) {
        if ((x >> 4) != chunkX || (z >> 4) != chunkZ
                || y < minY || y >= maxY) {
            return AIR;
        }
        int index = (y >> 4) - minSection;
        if (index < 0 || index >= sections.length) return AIR;
        BlockState[] section = sections[index];
        return section == null
                ? AIR
                : section[index(x & 15, y & 15, z & 15)];
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public long revision() {
        return revision;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }
}
