package baritone.cache;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.attribute.EnvironmentAttributes;
import baritone.utils.pathing.PathingBlockType;

/** Original class-name compatibility for the compact server chunk packer. */
public final class ChunkPacker {
    private ChunkPacker() {}

    public static CachedChunk pack(LevelChunk chunk) {
        return CachedChunk.pack(chunk);
    }

    public static BlockState pathingTypeToBlock(
            PathingBlockType type, DimensionType dimension) {
        return switch (type) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case AVOID -> Blocks.LAVA.defaultBlockState();
            case SOLID -> dimension.attributes().applyModifier(
                    EnvironmentAttributes.WATER_EVAPORATES, false)
                    ? Blocks.NETHERRACK.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
        };
    }
}
