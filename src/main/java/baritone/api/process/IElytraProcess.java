package baritone.api.process;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import java.util.List;

public interface IElytraProcess extends IBaritoneProcess {
    void repackChunks();
    BlockPos currentDestination();
    List<BetterBlockPos> getPath();
    void pathTo(BlockPos destination);
    void pathTo(Goal destination);
    void resetState();
    boolean isLoaded();
    boolean isSafeToCancel();
}
