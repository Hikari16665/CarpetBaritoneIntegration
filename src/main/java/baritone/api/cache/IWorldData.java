package baritone.api.cache;

/** Server world data. Waypoints are added independently of the chunk cache. */
public interface IWorldData {
    ICachedWorld getCachedWorld();
    IWaypointCollection getWaypoints();
}
