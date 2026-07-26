package baritone.api.process;

import baritone.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.util.List;
import java.util.Optional;

public interface IBuilderProcess extends IBaritoneProcess {
    void build(String name, ISchematic schematic, Vec3i origin);
    boolean build(String name, File schematic, Vec3i origin);
    void buildOpenSchematic();
    void buildOpenLitematic(int index);
    void pause();
    boolean isPaused();
    void resume();
    void clearArea(BlockPos corner1, BlockPos corner2);
    List<BlockState> getApproxPlaceable();
    Optional<Integer> getMinLayer();
    Optional<Integer> getMaxLayer();
}
