package baritone.api.schematic;

import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SubstituteSchematic extends AbstractSchematic {
    private final ISchematic schematic;
    private final Map<Block, List<Block>> substitutions;
    private final Map<BlockState, Map<Block, BlockState>> stateCache = new HashMap<>();

    public SubstituteSchematic(ISchematic schematic, Map<Block, List<Block>> substitutions) {
        super(schematic.widthX(), schematic.heightY(), schematic.lengthZ());
        this.schematic = schematic;
        this.substitutions = substitutions;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        return schematic.inSchematic(x, y, z, current);
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
        BlockState desired = schematic.desiredState(x, y, z, current, placeable);
        List<Block> substitutes = substitutions.get(desired.getBlock());
        if (substitutes == null || substitutes.isEmpty()) {
            return desired;
        }
        if (!(current.getBlock() instanceof AirBlock) && substitutes.contains(current.getBlock())) {
            return withBlock(desired, current.getBlock());
        }
        for (Block substitute : substitutes) {
            if (substitute instanceof AirBlock) {
                return current.getBlock() instanceof AirBlock ? current : Blocks.AIR.defaultBlockState();
            }
            for (BlockState candidate : placeable) {
                if (candidate.is(substitute)) {
                    return withBlock(desired, substitute);
                }
            }
        }
        return withBlock(desired, substitutes.get(0));
    }

    private BlockState withBlock(BlockState source, Block block) {
        Map<Block, BlockState> cached = stateCache.computeIfAbsent(source, ignored -> new HashMap<>());
        BlockState result = cached.get(block);
        if (result != null) {
            return result;
        }
        result = block.defaultBlockState();
        for (Property<?> property : source.getProperties()) {
            if (result.hasProperty(property)) {
                result = copyProperty(source, result, property);
            }
        }
        cached.put(block, result);
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(
            BlockState source, BlockState target, Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }

    @Override
    public void reset() {
        schematic.reset();
    }
}
