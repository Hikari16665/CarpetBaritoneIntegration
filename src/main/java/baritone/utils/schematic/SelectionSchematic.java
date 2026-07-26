package baritone.utils.schematic;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.selection.ISelection;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

public final class SelectionSchematic extends MaskSchematic {
    private final ISelection[] selections;
    public SelectionSchematic(ISchematic schematic, Vec3i origin, ISelection[] selections) {
        super(schematic);
        this.selections = Arrays.stream(selections)
                .map(selection -> selection.shift(Direction.WEST, origin.getX())
                        .shift(Direction.DOWN, origin.getY())
                        .shift(Direction.NORTH, origin.getZ()))
                .toArray(ISelection[]::new);
    }
    @Override protected boolean partOfMask(int x, int y, int z, BlockState current) {
        for (ISelection selection : selections) {
            if (x >= selection.min().x && y >= selection.min().y && z >= selection.min().z
                    && x <= selection.max().x && y <= selection.max().y && z <= selection.max().z) {
                return true;
            }
        }
        return false;
    }
}
