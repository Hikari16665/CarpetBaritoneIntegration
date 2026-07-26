package baritone.api.schematic;

import net.minecraft.world.level.block.state.BlockState;

public final class ShellSchematic extends MaskSchematic {
    public ShellSchematic(ISchematic schematic) { super(schematic); }
    @Override protected boolean partOfMask(int x, int y, int z, BlockState current) {
        return x == 0 || y == 0 || z == 0
                || x == widthX() - 1 || y == heightY() - 1 || z == lengthZ() - 1;
    }
}
