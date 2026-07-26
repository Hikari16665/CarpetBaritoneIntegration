package baritone.api.schematic;

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class FillSchematic extends AbstractSchematic {
    private final BlockOptionalMeta block;
    public FillSchematic(int x, int y, int z, BlockOptionalMeta block) {
        super(x, y, z); this.block = block;
    }
    public FillSchematic(int x, int y, int z, BlockState state) {
        this(x, y, z, new BlockOptionalMeta(state.getBlock()));
    }
    public BlockOptionalMeta getBom() { return block; }
    @Override
    public BlockState desiredState(
            int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        if (block.matches(current)) return current;
        return approxPlaceable.stream().filter(block::matches)
                .findFirst().orElse(block.getAnyBlockState());
    }
}
