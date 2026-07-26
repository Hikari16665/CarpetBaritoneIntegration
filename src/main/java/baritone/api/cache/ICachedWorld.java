package baritone.api.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;

public interface ICachedWorld {
    ICachedRegion getRegion(int regionX, int regionZ);
    void queueForPacking(LevelChunk chunk);
    boolean isCached(int blockX, int blockZ);
    ArrayList<BlockPos> getLocationsOf(
            String block, int maximum, int centerX, int centerZ,
            int maxRegionDistanceSq);
    void reloadAllFromDisk();
    void save();
}
