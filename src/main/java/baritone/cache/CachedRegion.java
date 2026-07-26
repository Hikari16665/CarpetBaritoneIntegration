package baritone.cache;

import baritone.api.cache.ICachedRegion;
import net.minecraft.world.level.block.state.BlockState;

/** API-compatible region view backed by the shared server cache. */
public final class CachedRegion implements ICachedRegion {
    private final ICachedRegion delegate;

    CachedRegion(ICachedRegion delegate) {
        this.delegate = delegate;
    }

    @Override public BlockState getBlock(int x, int y, int z) {
        return delegate.getBlock(x, y, z);
    }
    @Override public boolean isCached(int x, int z) {
        return delegate.isCached(x, z);
    }
    @Override public int getX() { return delegate.getX(); }
    @Override public int getZ() { return delegate.getZ(); }
}
