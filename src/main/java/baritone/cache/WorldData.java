package baritone.cache;

import baritone.api.cache.ICachedWorld;
import baritone.api.cache.IWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.dimension.DimensionType;
import java.nio.file.Path;

/** Server-side world data facade backed by the shared dimension cache. */
public final class WorldData implements IWorldData {
    public final CachedWorld cache;
    public final Path directory;
    public final DimensionType dimension;
    private final WaypointCollection waypoints;

    public WorldData(ServerLevel world) {
        cache = new CachedWorld(world);
        String dimension = world.dimension().location().toString()
                .replace(':', '_').replace('/', '_');
        this.directory = world.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("baritone").resolve(dimension);
        this.dimension = world.dimensionType();
        waypoints = new WaypointCollection(directory.resolve("waypoints"));
    }

    @Override
    public ICachedWorld getCachedWorld() {
        return cache;
    }

    @Override
    public WaypointCollection getWaypoints() {
        return waypoints;
    }

    public void onClose() {
        cache.save();
    }
}
