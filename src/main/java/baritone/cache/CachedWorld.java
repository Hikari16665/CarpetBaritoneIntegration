package baritone.cache;

import baritone.api.cache.ICachedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;

/** Original Baritone CachedWorld API name over the dedicated-server cache. */
public final class CachedWorld implements ICachedWorld {
    private final ServerWorldCache delegate;

    public CachedWorld(ServerLevel world) {
        delegate = ServerWorldCache.get(world);
    }

    public ServerWorldCache serverCache() {
        return delegate;
    }

    @Override public CachedRegion getRegion(int regionX, int regionZ) {
        var region = delegate.getRegion(regionX, regionZ);
        return region == null ? null : new CachedRegion(region);
    }
    @Override public void queueForPacking(LevelChunk chunk) {
        delegate.queueForPacking(chunk);
    }
    @Override public boolean isCached(int blockX, int blockZ) {
        return delegate.isCached(blockX, blockZ);
    }
    public boolean regionLoaded(int blockX, int blockZ) {
        return delegate.getRegion(blockX >> 9, blockZ >> 9) != null;
    }
    @Override public ArrayList<BlockPos> getLocationsOf(
            String block, int maximum, int centerX, int centerZ,
            int maxRegionDistanceSq) {
        return delegate.getLocationsOf(
                block, maximum, centerX, centerZ, maxRegionDistanceSq);
    }
    @Override public void reloadAllFromDisk() { delegate.reloadAllFromDisk(); }
    @Override public void save() { delegate.save(); }
    public void tryLoadFromDisk(int regionX, int regionZ) {
        if (delegate.getRegion(regionX, regionZ) == null) {
            delegate.reloadAllFromDisk();
        }
    }
}
