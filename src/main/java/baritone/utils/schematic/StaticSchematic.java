package baritone.utils.schematic;

import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

public class StaticSchematic extends AbstractSchematic implements IStaticSchematic {
    protected BlockState[][][] states;
    public StaticSchematic() { }
    public StaticSchematic(BlockState[][][] states) {
        this.states = states;
        boolean empty = states.length == 0
                || states[0].length == 0 || states[0][0].length == 0;
        x = empty ? 0 : states.length;
        z = empty ? 0 : states[0].length;
        y = empty ? 0 : states[0][0].length;
    }
    @Override public BlockState desiredState(
            int x, int y, int z, BlockState current, List<BlockState> placeable) {
        return states[x][z][y];
    }
    @Override public BlockState getDirect(int x, int y, int z) { return states[x][z][y]; }
    @Override public BlockState[] getColumn(int x, int z) { return states[x][z]; }
}
