package baritone.api.cache;

public interface ICachedRegion extends IBlockTypeAccess {
    boolean isCached(int blockX, int blockZ);
    int getX();
    int getZ();
}
