package baritone.api.schematic;

import net.minecraft.world.level.block.state.BlockState;

public interface IStaticSchematic extends ISchematic {
    BlockState getDirect(int x, int y, int z);
    default BlockState[] getColumn(int x, int z) {
        BlockState[] result = new BlockState[heightY()];
        for (int y = 0; y < result.length; y++) result[y] = getDirect(x, y, z);
        return result;
    }
}
