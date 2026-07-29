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
import net.minecraft.world.level.chunk.LevelChunkSection;

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
    private final LevelChunkSection[] sections;
    private final long revision;

    private ExactChunkSnapshot(
            int chunkX,
            int chunkZ,
            int minSection,
            int minY,
            int maxY,
            LevelChunkSection[] sections,
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
        LevelChunkSection[] source = chunk.getSections();
        LevelChunkSection[] copy = new LevelChunkSection[source.length];
        for (int index = 0; index < source.length; index++) {
            LevelChunkSection section = source[index];
            if (section != null && !section.hasOnlyAir()) {
                copy[index] = section.copy();
            }
        }
        ServerLevel level = (ServerLevel) chunk.getLevel();
        return new ExactChunkSnapshot(
                chunk.getPos().x(),
                chunk.getPos().z(),
                level.getMinSectionY(),
                level.getMinY(),
                level.getMaxY(),
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
        LevelChunkSection existing = sections[index];
        if (existing == null && !state.isAir()) return null;
        LevelChunkSection[] updated = sections.clone();
        if (existing != null) {
            LevelChunkSection section = existing.copy();
            section.setBlockState(
                    pos.getX() & 15, pos.getY() & 15,
                    pos.getZ() & 15, state);
            updated[index] = section.hasOnlyAir() ? null : section;
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
        LevelChunkSection section = sections[index];
        return section == null
                ? AIR
                : section.getBlockState(x & 15, y & 15, z & 15);
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
