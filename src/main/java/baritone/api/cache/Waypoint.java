package baritone.api.cache;

import baritone.api.utils.BetterBlockPos;
import java.util.Date;

public class Waypoint implements IWaypoint {
    private final String name;
    private final Tag tag;
    private final long creationTimestamp;
    private final BetterBlockPos location;

    public Waypoint(String name, Tag tag, BetterBlockPos location) {
        this(name, tag, location, System.currentTimeMillis());
    }
    public Waypoint(
            String name, Tag tag, BetterBlockPos location, long timestamp) {
        this.name = name;
        this.tag = tag;
        this.location = location;
        this.creationTimestamp = timestamp;
    }
    @Override public String getName() { return name; }
    @Override public Tag getTag() { return tag; }
    @Override public long getCreationTimestamp() { return creationTimestamp; }
    @Override public BetterBlockPos getLocation() { return location; }
    @Override public int hashCode() {
        return name.hashCode() ^ tag.hashCode() ^ location.hashCode();
    }
    @Override public boolean equals(Object value) {
        return value instanceof IWaypoint other
                && name.equals(other.getName()) && tag == other.getTag()
                && location.equals(other.getLocation());
    }
    @Override public String toString() {
        return name + " " + location + " " + new Date(creationTimestamp);
    }
}
