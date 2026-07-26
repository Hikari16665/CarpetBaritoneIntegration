package baritone.api.schematic;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public interface ISchematic {
    default boolean inSchematic(int x, int y, int z, BlockState currentState) {
        return x >= 0 && x < widthX()
                && y >= 0 && y < heightY()
                && z >= 0 && z < lengthZ();
    }
    default int size(Direction.Axis axis) {
        return switch (axis) {
            case X -> widthX();
            case Y -> heightY();
            case Z -> lengthZ();
        };
    }
    BlockState desiredState(
            int x, int y, int z, BlockState current, List<BlockState> approxPlaceable);
    default void reset() { }
    int widthX();
    int heightY();
    int lengthZ();
}
