package baritone.api.process;

import net.minecraft.core.BlockPos;

public interface IFarmProcess extends IBaritoneProcess {
    void farm(int range, BlockPos pos);
    default void farm() { farm(0, null); }
    default void farm(int range) { farm(range, null); }
}
