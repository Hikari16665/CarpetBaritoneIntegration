package baritone.api.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface IBlockTypeAccess {
    BlockState getBlock(int x, int y, int z);

    default BlockState getBlock(BlockPos pos) {
        return getBlock(pos.getX(), pos.getY(), pos.getZ());
    }
}
