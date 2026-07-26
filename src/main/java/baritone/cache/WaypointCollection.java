package baritone.cache;

import baritone.api.cache.IWaypoint;
import baritone.api.cache.IWaypointCollection;
import baritone.api.cache.Waypoint;
import baritone.api.utils.BetterBlockPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class WaypointCollection implements IWaypointCollection {
    private static final long MAGIC = 121977993584L;
    private final Path directory;
    private final Map<IWaypoint.Tag, Set<IWaypoint>> waypoints =
            new EnumMap<>(IWaypoint.Tag.class);

    public WaypointCollection(Path directory) {
        this.directory = directory;
        try { Files.createDirectories(directory); } catch (IOException ignored) {}
        for (IWaypoint.Tag tag : IWaypoint.Tag.values()) load(tag);
    }

    private synchronized void load(IWaypoint.Tag tag) {
        Set<IWaypoint> values = new HashSet<>();
        waypoints.put(tag, values);
        Path file = directory.resolve(tag.name().toLowerCase() + ".mp4");
        if (!Files.isRegularFile(file)) return;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readLong() != MAGIC) throw new IOException("Bad waypoint magic");
            long count = input.readLong();
            if (count < 0 || count > 1_000_000) throw new IOException("Bad count");
            while (count-- > 0) {
                String name = input.readUTF();
                long timestamp = input.readLong();
                values.add(new Waypoint(name, tag,
                        new BetterBlockPos(input.readInt(), input.readInt(),
                                input.readInt()), timestamp));
            }
        } catch (IOException exception) {
            System.err.println("[Baritone] Failed to load waypoints: "
                    + exception.getMessage());
        }
    }

    private synchronized void save(IWaypoint.Tag tag) {
        Path file = directory.resolve(tag.name().toLowerCase() + ".mp4");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeLong(MAGIC);
            output.writeLong(waypoints.get(tag).size());
            for (IWaypoint waypoint : waypoints.get(tag)) {
                output.writeUTF(waypoint.getName());
                output.writeLong(waypoint.getCreationTimestamp());
                output.writeInt(waypoint.getLocation().getX());
                output.writeInt(waypoint.getLocation().getY());
                output.writeInt(waypoint.getLocation().getZ());
            }
        } catch (IOException exception) {
            System.err.println("[Baritone] Failed to save waypoints: "
                    + exception.getMessage());
        }
    }

    @Override public void addWaypoint(IWaypoint waypoint) {
        if (waypoints.get(waypoint.getTag()).add(waypoint)) save(waypoint.getTag());
    }
    @Override public void removeWaypoint(IWaypoint waypoint) {
        if (waypoints.get(waypoint.getTag()).remove(waypoint)) save(waypoint.getTag());
    }
    @Override public IWaypoint getMostRecentByTag(IWaypoint.Tag tag) {
        return waypoints.get(tag).stream().max(
                Comparator.comparingLong(IWaypoint::getCreationTimestamp)).orElse(null);
    }
    @Override public Set<IWaypoint> getByTag(IWaypoint.Tag tag) {
        return Collections.unmodifiableSet(waypoints.get(tag));
    }
    @Override public Set<IWaypoint> getAllWaypoints() {
        return waypoints.values().stream().flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableSet());
    }
}
