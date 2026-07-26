package baritone.api.cache;

import java.util.Set;

public interface IWaypointCollection {
    void addWaypoint(IWaypoint waypoint);
    void removeWaypoint(IWaypoint waypoint);
    IWaypoint getMostRecentByTag(IWaypoint.Tag tag);
    Set<IWaypoint> getByTag(IWaypoint.Tag tag);
    Set<IWaypoint> getAllWaypoints();
}
