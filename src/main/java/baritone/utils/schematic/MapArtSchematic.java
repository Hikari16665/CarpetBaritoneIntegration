package baritone.utils.schematic;

import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class MapArtSchematic extends MaskSchematic {
    private final int[][] heightMap;
    public MapArtSchematic(IStaticSchematic schematic) {
        super(schematic);
        heightMap = new int[schematic.widthX()][schematic.lengthZ()];
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                heightMap[x][z] = Integer.MAX_VALUE;
                BlockState[] column = schematic.getColumn(x, z);
                for (int y = column.length - 1; y >= 0; y--) {
                    if (!(column[y].getBlock() instanceof AirBlock)) {
                        heightMap[x][z] = y;
                        break;
                    }
                }
            }
        }
    }
    @Override protected boolean partOfMask(int x, int y, int z, BlockState current) {
        return y >= heightMap[x][z];
    }
}
