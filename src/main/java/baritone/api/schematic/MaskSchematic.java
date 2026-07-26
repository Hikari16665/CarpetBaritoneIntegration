package baritone.api.schematic;

import baritone.api.schematic.mask.Mask;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

public abstract class MaskSchematic extends AbstractSchematic {
    private final ISchematic schematic;
    public MaskSchematic(ISchematic schematic) {
        super(schematic.widthX(), schematic.heightY(), schematic.lengthZ());
        this.schematic = schematic;
    }
    protected abstract boolean partOfMask(int x, int y, int z, BlockState current);
    @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
        return schematic.inSchematic(x, y, z, current) && partOfMask(x, y, z, current);
    }
    @Override public BlockState desiredState(
            int x, int y, int z, BlockState current, List<BlockState> placeable) {
        return schematic.desiredState(x, y, z, current, placeable);
    }
    @Override public void reset() { schematic.reset(); }

    public static MaskSchematic create(ISchematic schematic, Mask mask) {
        return new MaskSchematic(schematic) {
            @Override
            protected boolean partOfMask(int x, int y, int z, BlockState current) {
                return mask.partOfMask(x, y, z, current);
            }
        };
    }
}
